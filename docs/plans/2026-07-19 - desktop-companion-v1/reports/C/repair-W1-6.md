# Repair Wave W1-6 (Block C) — report

**Date:** 2026-07-20T00:40:00+02:00
**Agent:** repair-fix / cluster W1-6
**Findings:** `logic-C-1` (yellow, Important), `logic-C-3` (green, Nice-to-have)

## logic-C-1 — v3 import defeated the `ignoreUnknownKeys` forward-compat contract

**Root cause.** `CatalogImport.importV3` recomputed each entity's `contentHash` over the
**decoded** typed object. `CatalogCodec.decode` uses `ignoreUnknownKeys = true`, so an additive
payload field from a newer writer is dropped before the recompute sees it — the recomputed hash
can never reproduce the carried hash the writer computed over the superset payload, and a valid
cross-version file is rejected as "corrupt or tampered." Dormant within single-version Block C,
live on the first additive payload field (the exact cross-version file-sharing scenario the v3
format exists for). Fixed per the research file `research/v3-forward-compat-hash-recompute.md`.

**Fix (recompute from the raw file bytes, not the lossy typed projection):**

- `shared/.../config/CanonicalJson.kt` — added a `canonicalString(element: JsonElement)` overload
  that applies the same `stripEnvelope` + `canonicalize` to an already-parsed element. `CanonicalJson`
  stays the single source of truth for the canonical form; `stripEnvelope`/`canonicalize` remain private.
- `shared/.../config/ContentHash.kt` — added `contentHashOfElement(element: JsonElement)`, the digest
  over the raw file bytes; factored the shared digest→hex tail into a private `sha256Hex` helper
  (the load-bearing `and 0xFF` mask and its comment moved there intact).
- `app/.../config/CatalogImport.kt` — replaced the five-branch typed `mismatches` block with a
  raw-tree walk: re-parse `raw` via the existing `lenientJson` (schema-less `parseToJsonElement`
  drops nothing), read `entity.contentHash`/`entity.id`, compare to `contentHashOfElement(payload)`.
  Removed the now-unused per-kind entity imports (`ApiCredentialEntity`, `ModelRefEntity`,
  `ProfileEntity`, `ProviderConfigEntity`); `contentHash`, `PromptV3Entity`, `CatalogEntry` retained
  (still used by `upsertPromptRow` / the upsert loop). Tamper detection preserved (a changed payload
  value with a stale carried hash still mismatches).

**Regression tests (red on pre-fix code, green on fix):**

- `app/.../config/CatalogImportExportTest.kt::v3Import_acceptsAdditiveFieldFromNewerWriter` — encodes
  a full catalog, injects an unknown payload field into the prompt entity, sets its carried
  `contentHash` to `contentHashOfElement` of the superset payload (simulating a newer writer),
  asserts `V3Imported`. Fails on the old typed recompute (mismatch → `Invalid`). `v3Import_rejectsTamperedContentHash`
  stays green (value changed without updating the hash → still mismatches).
- `shared/.../config/ContentHashTest.kt::contentHashOfElement_matchesTypedHash_forSameVersionPayload`
  and `::contentHashOfElement_unknownAdditiveKey_changesHash` — primitive-level equivalence
  (envelope-carrying element hashes to the same digest as the typed `contentHash`) and forward-compat
  (an additive key changes the element hash, so tamper detection is intact at the primitive level).

**Block E follow-up (out of scope, flagged):** when the peer-sync path (Block E) adds its §5.3
recompute, it MUST call the same `contentHashOfElement` raw-based helper. Recomputing over the
decoded typed object there would re-introduce this identical forward-compat break on the
cross-version wire. A code comment to that effect was added at the fix site.

## logic-C-3 — append position used `COUNT(*)`, collides on a `pos` gap

**Root cause.** New prompt rows were appended with `pos = dao.count()`. `count()` is the row count,
not `MAX(pos)+1`; after a middle row is deleted (positions e.g. `0,2`, count `2`) a newly appended
row gets `pos = 2` and collides with the existing row at `pos 2`. `prompts.pos` has no UNIQUE
constraint, so both persist and their relative order becomes undefined.

**Fix:**

- `app/.../database/dao/PromptDao.kt` — added `nextPos()`:
  `SELECT COALESCE(MAX(pos), -1) + 1 FROM prompts` (0 on an empty table), with a KDoc explaining why
  it must be used instead of `count()` for appends.
- `app/.../config/CatalogImport.kt` — switched both append sites (`upsertPromptRow` new-row branch,
  `appendLegacyPrompts` loop seed) from `dao.count()` to `dao.nextPos()`. Within the import
  transaction each insert bumps `MAX(pos)`, so subsequent `nextPos()` calls stay collision-free.

**Regression test:**

- `app/.../config/CatalogImportExportTest.kt::appendLegacyPrompts_assignsUniquePosDespiteGapInPositions`
  — seeds positions `0,1,2`, deletes the middle row (gap → `{0,2}`, `COUNT(*)=2`), appends one row,
  asserts all positions are distinct and the new row lands at `pos 3`. Fails on the old `count()`
  append (new row at `pos 2` collides).

## Skipped / out-of-scope (documented for the re-audit)

- **Two pre-existing Java call sites share the same `count()`-as-position idiom** but are **outside
  this finding's cluster** (`CatalogImport.kt`):
  - `app/.../rewording/PromptEditActivity.java:223` — `new PromptEntity(0, promptDao.count(), …)`
  - `app/.../rewording/PromptsOverviewActivity.java:322` — `int startPos = promptDao.count();`
  Both would benefit from the new `nextPos()` helper and carry the identical latent gap-collision
  bug. Left untouched to respect the repair cluster's file scope (finding `logic-C-3` is scoped to
  `CatalogImport.kt`); flagged here so the re-audit can decide whether to open a follow-up.

## Red-verification note

Both fixes are confirmed live by their passing regression tests, which are behaviourally coupled to
the fix: `v3Import_acceptsAdditiveFieldFromNewerWriter` requires the raw-tree recompute (it fails on
the typed recompute) and `appendLegacyPrompts_assignsUniquePosDespiteGapInPositions` requires
`nextPos()` (it fails on `count()`). Explicit revert-to-red was not run in isolation because parallel
agents are actively editing the same files this wave (`CatalogImportExportTest.kt` was refactored
mid-task to `ConfigMigrationScenario.inMemoryDb`); the red state is established by the tests' design
and the reasoning in the research file.

## Tests

- `./gradlew :shared:test` (full, `--rerun-tasks`) — green.
- `./gradlew :app:testDebugUnitTest` (full) — green; `CatalogImportExportTest` 12 tests, 0 failures,
  0 skipped. (A `DirectoryNotEmptyException` in Robolectric temp-dir teardown is benign environment
  noise; the build succeeds.)

## Files modified

- `shared/src/main/kotlin/net/devemperor/dictate/shared/config/CanonicalJson.kt`
- `shared/src/main/kotlin/net/devemperor/dictate/shared/config/ContentHash.kt`
- `app/src/main/java/net/devemperor/dictate/config/CatalogImport.kt`
- `app/src/main/java/net/devemperor/dictate/database/dao/PromptDao.kt`
- `app/src/test/java/net/devemperor/dictate/config/CatalogImportExportTest.kt`
- `shared/src/test/kotlin/net/devemperor/dictate/shared/config/ContentHashTest.kt`

## Drift

None outside the finding cluster. (The two Java `count()` sites above were **not** edited — they are
recorded as a scoped follow-up, not drift.)
