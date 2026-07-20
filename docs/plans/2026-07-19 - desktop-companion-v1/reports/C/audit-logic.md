# Block C — Logic Audit

**Topic:** logic · **Block:** C · **Timestamp:** 2026-07-20T00:40:00+02:00
**Range:** `git diff c46cfe8..HEAD` (file-scoped to BLOCK_FILES) · **HEAD:** b856d82
**Grounding:** `knowledge-sql` (NULL-safety / CHECK constraints), project `CLAUDE.md` (Double-Enum, Room)

I audited the block for logic errors and edge cases — boundaries, null/empty, off-by-one,
ordering, races, error-path coverage, and byte-stability invariants — across the pure `:shared`
config core (CanonicalJson / ContentHash / CatalogCodec / ConfigValidations / Entities), the
Android persistence + migration layer (ConfigRepository, ConfigEntityMapper, MigrationTo12,
ConfigEntityMigration, ProfileResolver), and the C3 settings/import/export UI logic.

Overall the block is logically sound. The hash/canonical core is careful (envelope stripping
top-object-only, signed-byte mask, deterministic key sort, no floating-point in the payload),
the migration is idempotent over deterministic ids, the resolver's fallbacks never crash, and the
Room Double-Enum + CHECK pairing is complete. Three findings below; none is a crash or data-corruption
bug, one is a latent integrity/forward-compat contradiction worth a decision.

## Findings

### logic-C-1 — `importV3` §5.3 hash-recompute rejects forward-compatible v3 files as "tampered" (Important, latent)

**Files:** `app/src/main/java/net/devemperor/dictate/config/CatalogImport.kt:81-109`,
`shared/src/main/kotlin/net/devemperor/dictate/shared/config/CatalogCodec.kt:68-71`

`CatalogCodec.json` sets `ignoreUnknownKeys = true` with the explicit, documented intent that
"an additive field from a newer peer must not break an older reader" (CatalogCodec.kt:64-67).
But `CatalogImport.importV3` then recomputes each entity's `contentHash` over the *decoded* object
and compares it to the carried hash (CatalogImport.kt:82-109). When a file authored by a **newer app
version** carries a payload field this reader does not know, `decode` silently drops that field, so
the recomputed hash omits it while the carried hash (computed by the newer writer, which included the
field) does not — the two diverge and the file is rejected with
`"contentHash mismatch (corrupt or tampered file)"`.

**Failure scenario:** User A on a future app version exports a catalog whose `ProfileEntity` gained
one additive payload field; User B on the current version imports it → decode drops the field →
recomputed hash ≠ carried hash → import fails with a "corrupt or tampered" message on a perfectly
legitimate file. This is exactly the cross-version file-sharing scenario the feature exists for, and
it defeats the deliberate `ignoreUnknownKeys` forward-compat mechanism.

Currently **dormant**: within Block C the schema is single-version, so no unknown payload field can
occur and no test can trip it. It becomes live the first time the v3 payload schema gains an additive
field. (Note the Block E peer path uses `CatalogCodec.decode` directly, without this recompute, so it
is unaffected — the tension is specific to the SAF file-import path.)

**Suggested fix (needs a decision):** either (a) drop `ignoreUnknownKeys` for the file-import codec so
an unknown field is an honest `Malformed`/`Invalid` rather than a silent-then-mismatched rejection, or
(b) gate the recompute check so an unknown-key decode is reported distinctly from a genuine tamper
(e.g. re-serialize round-trip equality to detect dropped keys), or (c) accept and document that v3
files are not forward-compatible across payload-schema additions and the codec comment is aspirational
for the peer path only.

### logic-C-2 — `setTranscriptionKeyterms` silently no-ops without an active transcription ModelRef (Nice-to-have)

**File:** `app/src/main/java/net/devemperor/dictate/config/ActiveProfile.kt:109-115`

`setTranscriptionKeyterms` early-returns when `transcriptionModelRef(sp, db)` is null (no active
profile, or the active profile has no transcription model). The pref-based predecessor stored
`ElevenLabsKeytermsParsed` unconditionally. So keyterms entered before a transcription model exists on
the active profile are dropped without feedback.

**Failure scenario:** A user opens the keyterms editor on a profile whose transcription ModelRef is
unset (e.g. right after creating a fresh profile), types keyterms, saves → the write is a no-op and
the terms vanish on the next read. Low impact (keyterms only affect ElevenLabs, whose model ref would
normally exist by then), but the silent discard is a behavior regression from the pref path.

**Suggested fix:** surface the no-op to the caller (return Boolean / toast "select a transcription
model first"), or persist to the active profile's transcription ModelRef creating it if the active
transcription provider is ElevenLabs.

### logic-C-3 — `pos = dao.count()` can collide with an existing prompt row's pos when the table has gaps (Nice-to-have)

**Files:** `app/src/main/java/net/devemperor/dictate/config/CatalogImport.kt:146` (`upsertPromptRow`
new-row branch) and `:176-186` (`appendLegacyPrompts`)

New prompt rows are appended with `pos = dao.count()`. `count()` is the row *count*, not `max(pos)+1`.
If the `prompts` table has a gap in `pos` (e.g. after a middle row was deleted: positions `0,1,3`,
count `3`), a newly imported/appended prompt gets `pos = 3`, colliding with the existing row at `pos 3`.
`prompts.pos` has no UNIQUE constraint, so both rows persist with the same `pos` and their relative
order in the pill list becomes undefined.

**Failure scenario:** delete a prompt from the middle of the list, then import a v3/legacy prompt file
→ the appended prompt shares a `pos` with an existing prompt; the overview/keyboard ordering between the
two is non-deterministic. Pre-existing pattern (the same `count()`-as-position idiom predates this
block), reused verbatim in the new v3 import path — flagged because the v3 importer is new code.

**Suggested fix:** derive the append position from `MAX(pos)+1` (add a DAO query) rather than
`COUNT(*)`, or resequence after bulk insert.

## Out-of-scope observations (for the consolidator)

- **convention / plan-fidelity:** the migration + onboarding build **per-function** provider/credential/
  model chains (discriminator `"${function}:${provider}"`), so one provider used for both transcription
  and completion produces two `provider_configs` + two `api_credentials` rows (same key stored twice) and
  two rows in the settings hub. This is the documented C2 deviation ("per-function chains", defensible under
  D4); noting only because the doubled provider rows in `rebuildProviderList` may read as duplicates to a user.
- **efficiency:** `CatalogImport.upsertPromptRow` calls `dao.getAll()` inside the per-entity `forEach`
  (O(n·m) on import) and `importV3` / settings writes run on the main thread (existing `allowMainThreadQueries`
  pattern) — large imports risk an ANR. Not a logic defect.

## Coverage

**Audited (read in full at HEAD + diff):** CanonicalJson, ContentHash, CatalogCodec, ConfigValidations,
Entities, ConfigEnums (`:shared`); ConfigEntityMigration, ProfileResolver, ConfigRepository,
ConfigEntityMapper, ConfigWireMapping, ConfigSecrets, PrefsBackup, PromptHashing, PromptProvenance,
ActiveProfile, ConfigEntitySetup, CatalogImport, CatalogExport, ProfileListMutations,
MigrationTo12, ConfigRoomEntities, ConfigDaos, ProviderEditActivity, APISettingsActivity,
AndroidAiFactory, ProfilePromptConfig, rewording/PromptListMutations, and the DictateApplication /
DictateInputMethodService integration diffs.

**Verified sound (no finding):**
- Signed-byte mask in `ContentHash`/`fingerprint` (`and 0xFF`) — correct hex; guarded by test.
- `CanonicalJson` key sort (UTF-16 code units = JCS), envelope strip top-object-only, no float in payload,
  minimal RFC-8259 escaping — byte-stable; `%04x`/`%02x` hex is not locale-affected.
- `AmbiguityMode.persistKey` values (`ALWAYS_INSERT`/`AUTO`/`ALWAYS_REVIEW`) equal the wire enum names,
  so `toWire`/`toAmbiguityMode` cannot silently collapse to the default (checked the enum source).
- Nested `kind` discriminator: outer `CatalogEntry.kind` (sealed) vs inner `ProviderConfigEntity.kind`
  (concrete enum field under `entity`) do not collide.
- `ProfileResolver` fallbacks (no profile / no modelRef / missing credential) all return safe empties,
  never crash; completion-param reconstruction iterates the same `ParameterRegistry` defs as the migration
  stored (byte-equal by construction).
- `upsertProfile` REPLACE + CASCADE + explicit delete/insert of `profile_prompts` is order-correct under
  FK-on and FK-off; `deleteProvider` orphan-credential check reads `getAll()` after the provider row is
  already deleted (correct).
- `ProfileListMutations.moved` guards out-of-range/equal indices; `ordered` drops unknown ids and appends
  missing ones; move-up at index 0 / move-down at last index are no-ops.
- MigrationTo12 DDL: all finite-set columns carry their CHECK; `prompts` recreate preserves rows and the
  ADR-0024 `type` CHECK; inert defaults keep pill rows valid pre-backfill.

**Not executed:** instrumented `MigrationTo12Test` (emulator-gated, same status as `MigrationTo11Test`).
No files skipped.
