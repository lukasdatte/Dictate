# Block C — AUDIT-TEST (topic: test)

**Agent:** Block-C AUDIT-TEST · **Timestamp:** 2026-07-20T00:40:00+02:00
**Diff base:** `c46cfe8..HEAD` (filtered to Block-C `BLOCK_FILES`, test pattern + helpers)
**Verdict:** suites green; 2 Important + 2 Nice-to-have coverage/quality findings. Fixes nothing (audit).

## Conventions grounded (Step 0)

- `CONVENTIONS.coverage_command = none` → no numeric branch-coverage table possible; coverage judged
  by inspection against the spec acceptance criteria (AK1–AK9) and `docs/DATABASE-PATTERNS.md`.
- Instrumented tests are **local-only, not run in CI** by established project convention
  (`MigrationTo11Test` precedent, restated in `MigrationTo12Test` header).
- `test-first-patterns` (CLAUDE.md rule): every **bug fix** lands with a regression test that fails
  on the unfixed code; **pending** tests carry a `pending:` prefix + tracking artefact.
- Robolectric + `FakeSharedPreferences` + in-memory Room is the app-test idiom; no ad-hoc mock
  framework expected.

## Dynamic run

| Suite | Result |
|---|---|
| `./gradlew :shared:test` (`--rerun-tasks`) | **168 tests, 0 failures, 0 errors, 0 skipped** |
| `./gradlew :app:testDebugUnitTest` | **2492 tests, 0 failures, 0 errors, 1 skipped** |

The single skip is `NoLegacyKeyReadTest.secretPrefs_areReferencedOnlyInDefinitionAndMigration`
(`@Ignore`d — see Finding 2). All Block-C classes green: ConfigEntityMigrationTest(6),
ProfileResolverCharacterizationTest(9), ConfigWireEnumParityTest(6), ConfigEntityMapperTest(4),
MigrationTo12MetadataTest(2), CatalogImportExportTest(10), ProfileListMutationsTest(9),
ApiSettingsNavigationTest(3), NoMigratedPrefUiReferenceTest(2), plus the `:shared` config suites
CanonicalJson/ContentHash/ConfigValidations/CatalogCodec.

**Cross-chunk regressions:** none. No test that was green in its own chunk is red now; the full app
suite (all blocks) is green, so C's DB-schema/read-path/UI changes did not break A/B/D tests.

**Doc-trail (production changes during testing):** clean. Every inline production fix is documented —
C1 hash-mask hardening, C2 `onCreate` NOT-NULL prompt columns + `PromptEntity @JvmOverloads`,
C3 `enableEdgeToEdge()`/add-model-gating/`fingerprint internal`. No undocumented source edit in the
config layer.

## Static test quality — strengths

The `:shared` config suites are exemplary: byte-snapshot pinning with sorted-key expectations,
an **independent** SHA-256 renderer cross-checking `contentHash` (catches the signed-byte bug the
spec sample had), the full Malformed-vs-Invalid split with indexed error paths, and envelope-exclusion
proven both ways. `ProfileResolverCharacterizationTest` is a real behaviour-neutrality proof
(pref-path snapshot captured *before* B2 wipes the keys, then reproduced through B2→C2→resolver).
Names describe behaviour+condition throughout; assertions are concrete, not snapshot-happy.

## Findings

### 1. [Important] C3-3 bug fix (`PromptProvenance`) shipped with no regression test

`config/PromptProvenance.kt` is the fix for C3-3 ("prompt UI writes reset v12 uuid/provenance
columns"). It is wired into 5 production write seams (`PromptEditActivity` insert+update,
`PromptsOverviewActivity` ×2, `PromptListMutations.copyOf`) but has **zero** direct tests, and the
existing `rewording/PromptListMutationsTest` was **not** updated (no diff) and asserts no
`uuid`/`contentHash`. `PromptHashing.contentHashOf` is touched only transitively via
`CatalogImportExportTest.legacyImport…` (the `CatalogImport.appendLegacyPrompts` path, a *different*
code path). **Failure scenario:** revert any write seam to the historical 7-arg `PromptEntity`
constructor — the exact C3-3 defect (uuid/content_hash silently reset on prompt edit/duplicate) —
and every test stays green. `test-first-patterns` requires a regression test with each bug fix.
**Suggested:** a `PromptProvenanceTest` asserting `stamped` assigns-when-empty/keeps-when-present,
`edited` re-hashes while preserving uuid + `sourcePeerId`, `localCopy` resets provenance + mints a
fresh uuid.

### 2. [Important] `NoLegacyKeyReadTest` still `@Ignore`d after C3 — §2.6 invariant unverified

The pending guard's own header says "Remove `@Ignore` when C3 re-points the last writer", yet it
stays disabled (the 1 skip). Reason (C3-1): `ai/adapter/AndroidAiConfig.kt` still references the 11
secret pref constants, so the end-state assertion (only `DictatePrefs.kt` + `SecretsMigration.kt`
may name them) is false. **Effect:** the secretstore §2.6 "no legacy key read anywhere" invariant is
**not enforced by any running test** — a new `Pref.…ApiKey…` read added to production code would not
be caught. Already tracked as **C3-1 (delegated, Important)**; this corroborates it from the
coverage lens and needs the orchestration decision C3 requested (retire AndroidAiConfig's key reads
into test sources, or extend the allow-list per §2.6) so the guard can be un-ignored.

### 3. [Nice-to-have] Double-Enum CHECK behaviour (AK4) is exercised only in the un-run instrumented suite

`MigrationTo12Test` (accept/reject of each CHECK, `profile_prompts` CASCADE, schema validation) is
local-only and not run in CI; the JVM `MigrationTo12MetadataTest` pins only the 11→12 version pair.
So the CHECK constraints — the entire point of the Double-Enum pattern per `docs/DATABASE-PATTERNS.md`
— go unexecuted in every automated run. This matches the existing `MigrationTo11Test` convention
(hence Nice-to-have), but the guarantee is unverified until an emulator run happens. Worth an E2E /
Phase-4.5 runbook line so the instrumented migration suite is actually executed once before release.

### 4. [Nice-to-have] Duplicated B2→C2 startup scaffold across tests

`AndroidKeystoreSecretStore(FakeSharedPreferences(), InMemoryKekProvider(), hardwareBacked=false)` +
`SecretsMigration.run` + in-memory Room build + `ConfigEntityMigration.run` is near-identically
rebuilt in `ConfigEntityMigrationTest` and `ProfileResolverCharacterizationTest`; the store+repo+DB
build recurs again in `CatalogImportExportTest`/`ApiSettingsNavigationTest`. A shared
`testutil/ConfigMigrationScenario` helper would consolidate the First-Use scaffolding. Low priority
(the duplication is small and readable).

## Working-tree note (not a finding)

Two Block-C test files carry **uncommitted** improvements at audit time (a concurrent edit, not mine):
`shared/…/CanonicalJsonTest.kt` (raw U+0001 char → visible `` escape) and
`app/…/ConfigEntityMigrationTest.kt` (adds a forced-re-run assertion proving deterministic-id
*replace*, not just the flag-guard no-op). Both committed and working-tree versions compile and pass;
the dynamic run above used the working tree. If these edits are dropped rather than committed, coverage
falls back to the (still-adequate) committed versions. Whoever authored them should commit.
