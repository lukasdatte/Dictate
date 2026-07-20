# Block C — Audit Consolidation (validated findings)

**Mode:** initial · **Block:** C · **Timestamp:** 2026-07-20T00:40:00+02:00
**Consolidator:** audit-consolidator (validate / dedupe / classify — fixes nothing)
**Inputs:** 4 parallel audit reports (plan-and-api, convention, logic, test) → 14 raw findings.

## Summary

All 14 raw findings were validated against the code at HEAD; **every one is real** (no
false positives eliminated). Two findings describe the **same underlying issue** from
different angles and were **merged** into one. Result: **13 surviving findings** —
**11 green** (fix is clear), **2 yellow** (need a decision/research first).

Severity roll-up of survivors: 4 Important (3 green, 1 yellow) + 1 Important yellow
(cross-block, already delegated) + 8 Nice-to-have green. No Critical.

The block is a faithful, high-quality implementation (all four auditors concur; full
`:app` + `:shared` unit suites green). The findings are drift/coverage polish, one
latent forward-compat contradiction, and one already-open cross-block test-gate.

## Dedupe

- **`plan-and-api-C-1` + `convention-C-2` → merged (`enum-parity-aifunction-modelfunction`).**
  Both flag the `AIFunction`↔`ModelFunction` pair: plan-and-api from the "spec-mandated
  parity test is missing" angle (§4.8 assertion 3), convention from the "conversion is
  hand-inlined in two chunks, bypassing `ConfigWireMapping`" angle. The convention fix
  (add `AIFunction.toWire()` to `ConfigWireMapping`, replace both inline `if`s, add the
  parity case) fully subsumes the plan-and-api fix. Max severity **Important**; sources:
  plan-and-api-C-1, convention-C-2, plus corroborating notes in audit-convention
  (out-of-scope `[test]`) and audit-test.

## Cross-cut patterns noted

- **Same-operation-two-ways** is the dominant convention theme (canonical decimal ×2,
  AIFunction→ModelFunction ×2, SourceRef guard ×2, charset spelling ×2, enum-label
  rendering). Each is an independent small extraction; none share a file cluster, so
  they fix independently.
- **`config/`-level helper home:** three green findings (`convention-C-1`,
  `convention-C-2`, `convention-C-4`) all want a small shared helper lifted into
  `config/` next to `ConfigEntityMapper`. A fixer touching one should place them
  consistently.

---

## Green findings (real, fix is clear)

### G1 — `enum-parity-aifunction-modelfunction` (Important) — merged plan-and-api-C-1 + convention-C-2

**Files:** `app/.../config/ConfigWireMapping.kt`, `app/.../config/ConfigEntityMigration.kt:175`,
`app/.../config/ConfigEntitySetup.kt:100`, `app/src/test/.../config/ConfigWireEnumParityTest.kt`

**Verified:** `ConfigWireMapping` centralizes `AIProvider`/`AmbiguityMode`/`PromptMode`
domain↔wire conversions (each pinned by `ConfigWireEnumParityTest`, 6 tests). The fourth
pair `AIFunction`↔`ModelFunction` is **not** in the mapping; instead it is hand-inlined
byte-identically at `ConfigEntityMigration.kt:175` and `ConfigEntitySetup.kt:100`
(`if (function == AIFunction.TRANSCRIPTION) ModelFunction.TRANSCRIPTION else ModelFunction.COMPLETION`),
and there is **no** parity test for it (grep-confirmed; enums both `{TRANSCRIPTION,
COMPLETION}`). Spec §4.8 enumerates this as required assertion 3; §2.1 acceptance =
"Enum-Paritäts-Tests grün"; it was the exact deliverable C1 delegated to C2 (issue C1-1,
`blocks-following`) — C2 closed 3 of 4. Runtime blast radius today is low (explicit if/else
mis-maps to COMPLETION rather than throwing), but the spec-mandated safety net the other
three enums have is absent, and the Block-D/E `ModelFunction` mirror depends on the same
value set.

**Fix:** add `fun AIFunction.toWire(): ModelFunction` to `ConfigWireMapping`, replace both
inline `if` expressions with `function.toWire()`, and add
`@Test fun \`AIFunction names match ModelFunction names\`()` (set-equality of `.name`) to
`ConfigWireEnumParityTest`.

### G2 — `convention-C-1` (Important) — canonical-decimal helper duplicated across C2 and C3

**Files:** `app/.../config/ConfigEntityMigration.kt:242-243` (`toCanonicalDecimal`),
`app/.../settings/ParameterMapEditor.kt:166-167` (`canonicalFloat`)

**Verified:** both are byte-identical
`BigDecimal(value.toString()).stripTrailingZeros().toPlainString()`, each declaring itself
the §8.3 canonical parameter-value form (ParameterMapEditor's comment: "same canonical form
the migration writes (§8.3)"). This string feeds `parameterDefaults`/`parameterOverrides` →
`contentHash`, so the two copies are a **shared hash contract**: a future edit to one
(locale, precision, exponent) missing the other → divergent bytes for the same value →
divergent `contentHash` → broken Block-E dedup.

**Fix:** extract one `canonicalDecimal(Float): String` helper in `config/` (next to
`ConfigEntityMapper.encodeParams`) and call it from both sites.

### G3 — `convention-C-3` (Nice-to-have) — `ProfileRoomEntity` diverges from the Double-Enum convention its file header states

**File:** `app/.../config/entity/ConfigRoomEntities.kt:113-139` (`ProfileRoomEntity`)

**Verified:** the three sibling entities use `Enum.name` defaults + `xxxEnum` accessors
(`visibilityEnum`, `subscriptionModeEnum`, etc.); `ProfileRoomEntity` instead hardcodes
`= "PREDEFINED"` / `= "ALWAYS_INSERT"` string-literal defaults for `style_prompt_mode` /
`system_prompt_mode` / `ambiguity_mode` and exposes **no** enum accessor (the parse is
pushed into `ConfigEntityMapper`'s private helpers). SQL CHECKs exist, so not a data-safety
bug, but the header's "all finite-set columns …" claim is false and an enum rename won't
reach the literals.

**Fix:** replace the string literals with `PromptSelectionMode.PREDEFINED.name` /
`AmbiguityModeValue.ALWAYS_INSERT.name`, add `stylePromptModeEnum`/`systemPromptModeEnum`/
`ambiguityModeEnum` accessors, move the fallback parse out of `ConfigEntityMapper`.

### G4 — `convention-C-4` (Nice-to-have) — `SourceRef`-from-nullable-triple reconstruction duplicated

**Files:** `app/.../config/ConfigEntityMapper.kt:44-49` (private `sourceRef(...)`),
`app/.../config/CatalogExport.kt:71-76` (identical null-guard inlined in `toPromptDto`)

**Verified:** identical `(peerId, originalId, originalHash) → SourceRef?` guard. CatalogExport
works from a legacy `PromptEntity` so it cannot reach the mapper's private helper — which
argues for lifting the guard into a small shared helper.

**Fix:** lift the guard into an `internal` shared function in `config/` and call from both.

### G5 — `convention-C-5` (Nice-to-have) — charset spelled two ways + one dead import

**Files:** `app/.../config/ConfigEntitySetup.kt:60` (`StandardCharsets.UTF_8`) vs
`ProviderEditActivity.kt:162` / `APISettingsActivity.kt:342` (`Charsets.UTF_8`);
`app/.../config/ConfigEntityMigration.kt:28` imports `java.nio.charset.StandardCharsets`
but the file uses only bare `.toByteArray()` — **dead import** (grep-confirmed).

**Fix:** use `Charsets.UTF_8` in `ConfigEntitySetup`; remove the unused `StandardCharsets`
import from `ConfigEntityMigration`.

### G6 — `convention-C-6` (Nice-to-have) — provider type / ambiguity mode rendered raw enum in some UIs, displayName/localized in others

**Files:** `app/.../settings/APISettingsActivity.kt:137` (`providerTypeName = type.name`),
`ProfileEditActivity.kt:178` (`ambiguityModes.map { it.name }`) vs
`ProviderEditActivity.kt:105` (`displayName`) and `ProfileEditActivity.kt:157-161`
(localized `getString`)

**Verified:** the hub subtitle shows the raw wire token (`OPENAI`/`CUSTOM`) while the
provider editor uses `displayName`; the prompt-mode spinner is localized but the ambiguity
spinner shows raw tokens (`ALWAYS_INSERT`/`ALWAYS_REVIEW`). Cross-chunk presentation
inconsistency (straddles convention/UX/i18n).

**Fix:** render `ProviderType` via `AIProvider.displayName` (or a shared label mapper) in
the hub subtitle; give the ambiguity spinner localized labels like the prompt-mode spinner.

### G7 — `logic-C-2` (Nice-to-have) — `setTranscriptionKeyterms` silently no-ops without an active transcription ModelRef

**File:** `app/.../config/ActiveProfile.kt:109-115`

**Verified:** `transcriptionModelRef(sp, db) ?: return` — keyterms typed before a
transcription model exists on the active profile are dropped with no feedback; the
pref-based predecessor persisted `ElevenLabsKeytermsParsed` unconditionally. Low impact
(ElevenLabs-only) but a silent behaviour regression.

**Fix (low-risk):** surface the no-op to the caller (return `Boolean` / toast "select a
transcription model first"). (Auto-creating the ModelRef is the heavier alternative.)

### G8 — `logic-C-3` (Nice-to-have) — `pos = dao.count()` can collide when the prompts table has a gap

**Files:** `app/.../config/CatalogImport.kt:145` (`upsertPromptRow` new-row branch),
`:180` (`appendLegacyPrompts`)

**Verified:** append position is `dao.count()`, not `max(pos)+1`. With a gap (positions
0,1,3, count 3) a new row gets `pos = 3`, colliding with the existing row at 3; `prompts.pos`
has no UNIQUE constraint, so ordering between the two becomes undefined. Pre-existing
`count()`-as-position idiom, reused verbatim in the new v3 import path.

**Fix:** derive the append position from `MAX(pos)+1` via a dedicated DAO query, or
resequence after the bulk insert.

### G9 — `C-TEST-1` (Important) — C3-3 bug fix (`PromptProvenance`) shipped with no regression test

**Files:** `app/.../config/PromptProvenance.kt`, `app/.../rewording/PromptListMutations.kt`,
`app/src/test/.../rewording/PromptListMutationsTest.kt` (unchanged)

**Verified:** no `PromptProvenanceTest` exists (find-confirmed). `PromptProvenance.stamped/
edited/localCopy` have zero direct tests; the 5 production write seams have no uuid/hash
assertions; the existing `PromptListMutationsTest` was not updated. `contentHashOf` is
touched only transitively via a different path (`CatalogImport.appendLegacyPrompts`).
Reverting any seam to the historical 7-arg `PromptEntity` constructor (the exact C3-3
defect) leaves the whole suite green — violates the `test-first-patterns` regression-test
rule.

**Fix:** add `PromptProvenanceTest` asserting `stamped()` mints-when-empty / keeps-when-set,
`edited()` re-hashes while preserving uuid + `sourcePeerId`, `localCopy()` clears provenance
+ mints a fresh uuid. Optionally extend `PromptListMutationsTest.copyOf` with uuid/hash
assertions.

### G10 — `C-TEST-3` (Nice-to-have) — Double-Enum CHECK behaviour (AK4) exercised only in the un-run instrumented suite

**Files:** `app/src/androidTest/.../migration/MigrationTo12Test.kt`,
`app/src/test/.../migration/MigrationTo12MetadataTest.kt`

**Verified:** accept/reject of each new table's CHECK + `profile_prompts` CASCADE lives only
in the instrumented `MigrationTo12Test` (local-only, not in CI, per `MigrationTo11Test`
convention); the JVM metadata test pins only the 11→12 version pair. So the CHECK constraints
— the point of the Double-Enum pattern — go unexecuted in every automated run. Matches
project convention (hence low), but unverified until an emulator run.

**Fix:** add the instrumented migration suite to the Phase-4.5 E2E runbook so
`MigrationTo12Test` runs on an emulator at least once before release.

### G11 — `C-TEST-4` (Nice-to-have) — duplicated B2→C2 startup scaffold across tests

**Files:** `app/src/test/.../config/ConfigEntityMigrationTest.kt`,
`.../ai/adapter/ProfileResolverCharacterizationTest.kt`,
`.../config/CatalogImportExportTest.kt`

**Verified:** `AndroidKeystoreSecretStore(FakeSharedPreferences(), InMemoryKekProvider(),
hardwareBacked=false)` + `SecretsMigration.run` + in-memory Room + `ConfigEntityMigration.run`
is near-identically rebuilt across these tests. Small, readable duplication.

**Fix:** extract a `testutil/ConfigMigrationScenario` helper returning `(db, sp, store)`.
Low priority.

---

## Yellow findings (real, need a decision/research first)

### Y1 — `logic-C-1` (Important, latent) — `importV3` §5.3 hash-recompute rejects forward-compatible v3 files as "tampered"

**Research topic:** `v3-forward-compat-hash-recompute`
**Files:** `app/.../config/CatalogImport.kt:81-109`,
`shared/.../config/CatalogCodec.kt:63-71`

**Verified:** `CatalogCodec.json` sets `ignoreUnknownKeys = true` with the documented intent
"an additive field from a newer peer must not break an older reader". But `importV3`
recomputes each entity's `contentHash` over the **decoded** object and compares to the
carried hash. A file from a newer app version carrying an unknown payload field → decode
drops it → recomputed hash omits it while the carried hash included it → rejected as
`"contentHash mismatch (corrupt or tampered file)"`. This defeats the deliberate forward-
compat mechanism on the SAF file-import path (the Block-E peer path uses `CatalogCodec.decode`
without the recompute and is unaffected). **Dormant** in Block C (single-version payload
schema) — becomes live the first time the payload gains an additive field, i.e. the exact
cross-version file-sharing scenario the feature targets.

**Why yellow:** the auditor offers three mutually exclusive resolutions, each with a
different forward-compat contract — a design decision, not a mechanical fix:
(a) drop `ignoreUnknownKeys` for the file-import codec (unknown field → honest `Malformed`);
(b) distinguish unknown-key decode from genuine tamper before the recompute (round-trip
re-serialize equality); or
(c) accept + document that v3 files are not forward-compatible across payload-schema
additions and scope the `ignoreUnknownKeys` promise to the peer path only.

### Y2 — `C-TEST-2` (Important) — `NoLegacyKeyReadTest` still `@Ignore`d; §2.6 invariant enforced by no running test

**Research topic:** `androidaiconfig-secret-pref-retirement`
**Files:** `app/src/test/.../secrets/NoLegacyKeyReadTest.kt`,
`app/.../ai/adapter/AndroidAiConfig.kt`

**Verified:** the guard is the run's single skipped test; its header says to remove `@Ignore`
once C3 re-points the last writer, but `AndroidAiConfig.kt` still references the secret pref
constants (29 `Pref.`/`ApiKey` hits), so the end-state assertion (allow-list
`{DictatePrefs.kt, SecretsMigration.kt}`) is false and stays disabled. Effect: the
secretstore §2.6 invariant (no code outside `DictatePrefs`/`SecretsMigration` reads the 11
secret prefs) is enforced by **no** running test.

**Why yellow / dedup note:** this is the **same** unresolved item already filed as **C3-1
(delegated, Important)** in `C3-impl.md` and re-surfaced (as an out-of-scope note) by the
plan-and-api auditor. It is a **Block-B-coupled orchestration decision**, not a self-
contained fix: either retire `AndroidAiConfig`'s key-pref reads (move the parity/
characterization baseline into test sources) **or** extend the test's allow-list per §2.6,
then remove the `@Ignore`. Route to the existing C3-1 decision rather than spawn duplicate
work.

---

## Eliminated findings

None. All 14 raw findings validated as real against HEAD.

## Files audited during consolidation (spot-validation)

`ConfigWireEnumParityTest.kt`, `ConfigWireMapping.kt`, `ConfigEntityMigration.kt`,
`ConfigEntitySetup.kt`, `ParameterMapEditor.kt`, `ConfigRoomEntities.kt`,
`ConfigEntityMapper.kt`, `CatalogExport.kt`, `CatalogImport.kt`, `CatalogCodec.kt`,
`ActiveProfile.kt`, `APISettingsActivity.kt`, `ProviderEditActivity.kt`,
`ProfileEditActivity.kt`, `NoLegacyKeyReadTest.kt`, `AndroidAiConfig.kt` (ref-count only),
`AIProvider.kt` / `ConfigEnums.kt` (enum values).
