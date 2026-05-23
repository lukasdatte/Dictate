# AUDIT-TEST Findings — Block 3

**Agent-ID:** `B3-AUDIT-TEST` · **Scope:** full-block (`git diff d39891a..HEAD`)
**Test command:** `./gradlew testDebugUnitTest --rerun-tasks` + `./gradlew testReleaseUnitTest --rerun-tasks` (uncached, authoritative)
**Block B3 chunks audited:** C8-C1 (LanguageController removal), C9-C2 (audioFile field removal). C10-C3 produced no test-bearing diff (OQ-1 KDoc only; blocked by C10-IMPL-2, out of AUDIT-TEST scope — owned by Theme C-R).

---

## Authoritative uncached test results — BOTH variants

| Variant | Command | tests | skipped | failures | errors | Verdict |
|---|---|---|---|---|---|---|
| Debug | `testDebugUnitTest --rerun-tasks` | 1048 | 0 | **1** | 0 | ❌ FAIL |
| Release run 1 | `testReleaseUnitTest --rerun-tasks` | 1048 | 0 | **1** | 0 | ❌ FAIL |
| Release run 2 | `testReleaseUnitTest --rerun-tasks` (diff order) | 1048 | 0 | **1** | 0 | ❌ FAIL |

**Reproducibility: 3/3 uncached runs failed**, always `LegacyAudioFileMigrationTest`,
always the `DurationHealingJob` mutation fingerprint (`status→FAILED` and/or
`last_error_message → "Audio file not found during healing"`), **method-varying**
per run (debug→kt:244, release-1→kt:220, release-2→kt:244) — the textbook
non-deterministic shared-state-pollution signature. It is highly reproducible
under `--rerun-tasks` (uncached full-suite), not the "passes in full
testDebugUnitTest" the C8/C9 reports claimed.

**AC-9 (≥946):** 1048 ≥ 946 — test *count* gate met. **Net behaviour-coverage: not deleted** (see Cross-Chunk-Regressions — all `LanguageControllerTest` deletions are ported or obsolete-mechanism).

**The single failure in every failing run is `LegacyAudioFileMigrationTest` — and it is the C8-IMPL-1 pollution flake, NOT a C8/C9 regression.** Failure method **varies per run** (the non-deterministic signature):

| Run | Failing method | Line | ComparisonFailure |
|---|---|---|---|
| Debug | `run leaves non-legacy-path sessions untouched` | kt:244 | `non-legacy-path row keeps its status expected:<[RECORDING]> but was:<[FAILED]>` |
| Release-1 | `run preserves last_error_message on already-FAILED rows` | kt:220 | `historical error must survive … expected:<[openai_rate_limit]> but was:<[Audio file not found during healing]>` |
| Release-2 | `run leaves non-legacy-path sessions untouched` | kt:244 | `non-legacy-path row keeps its status expected:<[RECORDING]> but was:<[FAILED]>` |

**No non-C8-IMPL-1 failure was observed in any run. No real regression. The 1047 non-migration tests are green in every run.**

> [!IMPORTANT]
> The C8-C1 and C9-C2 block-report claims that **`testDebugUnitTest` was fully
> green (1048/0/0)** are **falsified by the authoritative uncached run**: the
> flake **also manifests in the DEBUG suite** (kt:244 this run), not release-only.
> The reports characterised it as release-fork-specific; it is not. This does
> not change the *root cause* or the *not-a-regression* verdict, but the
> "debug always green" framing in the C8/C9 reports is inaccurate and is itself
> a documentation finding (AUDIT-TEST-B3-2).

---

## C8-IMPL-1 — ROOT-CAUSE DIAGNOSIS (the priority finding)

**Severity:** Important · **Status:** delegated-to-orchestrator → repair-sub-phase (per D3, NOT re-postponed — this is the block's known test-debt, the consolidator routes it and B3-VAL repairs it).

### The polluter is `DurationHealingJob`, spawned async by `DictateApplication.onCreate()`

**Smoking-gun evidence (string-level proof):** Both failing runs show the polluted
row carrying `last_error_message = "Audio file not found during healing"` and/or
`status = FAILED`. That exact string exists in **exactly one place in the entire
codebase**:

```
app/src/main/java/net/devemperor/dictate/database/DurationHealingJob.kt:61
    "Audio file not found during healing"
```

It is **NOT** `LegacyAudioFileMigration.REASON` (`= "audio_file_path_legacy_purged"`).
The migration provably did not produce these rows — `DurationHealingJob.heal()` did.

### The exact mechanism

1. `app/src/main/AndroidManifest.xml:39` registers `android:name=".DictateApplication"`.
   **Every Robolectric test in the JVM fork** instantiates `DictateApplication`
   and runs `onCreate()`.
2. `DictateApplication.onCreate()` (`DictateApplication.java:64-70`):
   ```java
   final DictateDatabase db = DictateDatabase.getInstance(this);
   final RecordingRepository recordingRepository = new RecordingRepository(this);
   final ExecutorService executor = Executors.newSingleThreadExecutor();
   executor.execute(() -> DurationHealingJob.INSTANCE.heal(db.sessionDao(), recordingRepository));
   executor.shutdown();   // ← NON-BLOCKING. No awaitTermination. No cancel seam.
   ```
   `executor.shutdown()` lets the submitted task finish *eventually* but **does
   not block** `onCreate()` and **provides no production seam to cancel or await
   the in-flight heal thread**.
3. `DurationHealingJob.heal()` runs `dao.findWithMissingDuration()` whose SQL
   (`SessionDao.kt:60-65`) is:
   ```sql
   SELECT * FROM sessions WHERE audio_file_path IS NOT NULL AND audio_duration_seconds = 0
   ```
   — **no status filter, no legacy-path filter**. For every matched row whose
   `File(audio_file_path).exists()` is false, it does
   `dao.updateStatus(id, FAILED)` + `dao.updateError(id, UNKNOWN, "Audio file not
   found during healing")` (`DurationHealingJob.kt:57-62`).
4. The shared `DictateDatabase` singleton is process-wide across the Robolectric
   fork. When `LegacyAudioFileMigrationTest` inserts its test rows (e.g.
   `recording-other` → `audio/rec_42_dead.m4a`, duration `0`, file absent;
   or the `old-failed` row → legacy path, duration `0`), a **`DurationHealingJob`
   thread still in flight** — spawned by *this* test's own `DictateApplication`
   bootstrap or by a sibling Robolectric test (any of the 14 `DictatePipelineService*`
   boot-tests / `ImeRecordingDriveCutoverTest` / `DictateCutoverE2ETest`) — runs
   `findWithMissingDuration()`, **matches those rows**, and overwrites their
   `status`/`last_error_message`, racing the test's assertion. Method-varying
   because *which* row the thread catches depends on fork scheduling.

### Why the existing F-9 seam does not close it

The migration test **already** calls `DictateDatabase.resetForTest(context)` in
both `@Before` and `@After` (added at commit `6164453`, an **ancestor of the
block-start `d39891a`** — i.e. the seam is present in B3 HEAD and the B3 diff does
**not** touch this test file). `DictatePipelineServiceOverlayTransitionTest`
also calls it in `@After`. But `DictateDatabase.resetForTest()` only
closes+deletes the DB file and nulls the singleton — it has **no seam to cancel
or await the `DurationHealingJob` executor thread**. The heal thread can run
*after* `resetForTest()` rebuilds the DB, mutating the **next** test's rows.
This is the distinct shared-state axis the prompt names: a
**`DurationHealingJob`/DB-singleton async axis**, orthogonal to B2-VAL-W1's
`ActiveJobRegistry.resetForTest()` (job-lock) axis.

### Confirmed NOT a C8/C9 regression

- C8-C1 diff is **language-only** (`LanguageController`/`LanguageResolver`/
  `LanguageModule`/`Action.RefreshFromPref`). C9-C2 diff is the IME `audioFile`
  field + `RecordingState.audioFileOrNull`. **Zero code-path overlap** with
  `migration/` / `DurationHealingJob` / `SessionDao` / `DictateApplication`
  healing-spawn.
- Block B3's own new/changed tests (`LanguageResolverTest`, `LanguageModuleTest`,
  `DictateUiStateTest`) are pure-JVM `FakeSharedPreferences` tests — **none are
  Robolectric / DB-singleton**, so they cannot be the polluter and cannot have
  introduced the flake.
- The flake is pre-existing R-7-class pollution (the `LegacyAudioFileMigration`
  Robolectric set on the shared DB singleton), surfaced by C8 and reproduced by
  C9; it is the block's forwarded Postponed test-debt.

### EXACT fix to add (mirrors the established production-seam convention)

The codebase convention for cross-test shared-state is a production-owned
`@JvmStatic [internal] fun resetForTest()` that drains the shared axis, called
from affected tests' `@Before`/`@After` (precedent: `DictateDatabase.resetForTest`,
`JobExecutor.resetForTest`, `ActiveJobRegistry.resetForTest`). The
`DurationHealingJob` async axis has **no such seam** — add one:

1. **Extract the healing executor into a production-owned holder with a drain
   seam.** Move the `ExecutorService` out of the `DictateApplication.onCreate()`
   local into a small production object (e.g.
   `database/DurationHealingScheduler.kt`, or a static field on
   `DictateApplication`) that owns the executor and exposes:
   ```kotlin
   @JvmStatic @VisibleForTesting
   internal fun resetForTest() {
       executor?.shutdownNow()
       executor?.awaitTermination(5, TimeUnit.SECONDS)   // drain the in-flight heal
       executor = null
   }
   ```
   `DictateApplication.onCreate()` calls `DurationHealingScheduler.schedule(db, repo)`
   instead of inlining the executor. Behaviour is identical in production
   (still async, still single-shot, still shut down after enqueue).
2. **Call the new `resetForTest()` from the `migration/` Robolectric tests'
   `@Before` AND `@After`** — alongside the existing
   `DictateDatabase.resetForTest(context)` (drain the heal thread *before*
   `DictateDatabase.resetForTest()` so it cannot touch the rebuilt DB), and
   ideally from the `DictatePipelineService*` boot-test teardowns that amplify
   the pollution (they each run `DictateApplication.onCreate` → spawn a heal
   thread). The single mandatory site to fix the observed failure is
   `LegacyAudioFileMigrationTest` `@Before`/`@After`; the boot-test teardowns
   are the belt-and-suspenders source-side fix (the same "complete the
   discipline at the source" pattern B2-VAL-W1 F-6 applied to
   `ActiveJobRegistry`).

> Repair-sub-phase: this is a **test-infra** fix (one new production holder with
> a test seam + tearDown wiring). No behavioural production change. After the
> seam lands, re-run `testDebugUnitTest --rerun-tasks` AND
> `testReleaseUnitTest --rerun-tasks` ≥2× to confirm determinism.

---

## Documentation Gaps

| ID | Title | Severity | Chunk:Sub-Section | Status |
|---|---|---|---|---|
| AUDIT-TEST-B3-1 | C8-C1 `### Issues` IMPL-1 + C9-C2 `### Resend + migration still work` claim "`testDebugUnitTest` (uncached) all green" / "full `testDebugUnitTest` is green (1048/0/0)". The authoritative uncached `testDebugUnitTest --rerun-tasks` **fails** (`LegacyAudioFileMigrationTest.kt:244`, 1048/1). The flake is **not** release-only as both reports state. Doc-trail inaccuracy — repair: correct the C8/C9 sub-sections to state the flake manifests in BOTH variants (non-deterministically), no code revert needed. | Important | C8-C1 `### Issues` / C9-C2 `### Resend + migration still work` | open (delegated) |
| AUDIT-TEST-B3-2 | C8/C9 `### Code-Bugs Found While Writing Tests` sub-sections are otherwise **accurate**: C8's "DictateOrchestratorTest.kt (14 sites) + ModuleServicesTest.kt (2 sites)" verified exactly (14 + 2 `RefreshFromPref("en")` additions); C9's "(none)" verified. Recorded as a positive — no gap here. | — (none) | — | n/a |

Test-commits verified clean (no hidden production change):
- `93f86d6` (B3-C8-C1 test): only `*Test.kt` + `FakePipelineUiStateReader.kt` (testutil) + block-report `.md`. **No production file.** ✓
- `ccb38c2` (B3-C9-C2 test): only `DictateUiStateTest.kt` + block-report. **No production file.** ✓
- `6de54b1` (C8 prod) / `bd82070` (C9 prod) / `185f3f6` (C10 prod): **zero `*Test` files** — disjoint commit boundaries hold exactly as documented.

---

## Test-Quality

| ID | Title | Severity | File:Line | Status |
|---|---|---|---|---|
| — | none — block-3 test changes are high quality (see Coverage). `LanguageResolverTest` 13 cases, `assertEquals`/`assertSame`/`assertTrue`, behaviour-named, `FakeSharedPreferences` (K-1 convention, no Mockito). `DictateUiStateTest` +5 use `assertSame` for the load-bearing R-5 handle-identity invariant. `LanguageModuleTest` strengthened: the Phase-1 placeholder `RefreshFromPref returns null` test was **replaced** by 3 real behavioural tests + 1 real-orchestrator AC-5 propagation test. No weak/snapshot/order-dependent assertions. | — | — | — |

---

## Coverage (branch-inspection)

| File | Branch coverage assessment | Untested-Branches |
|---|---|---|
| `preferences/LanguageResolver.kt` | **Adequate.** 5 decision points: `langs.isEmpty()` (effective→"en" fallback), `code !in allowed()` (log-warn), `code !in curated` (auto-curate), `preferActive ?: activeCodeOrNull`, `pos in curated.indices`. `LanguageResolverTest` 13 cases exercise **every** branch (corrupt-envelope fallback, unknown-code filter, auto-curate vs already-present, preferActive present/falls-out, still-present-active kept, cross-instance freshness). | The `Log.w` line itself (line 107) is not asserted — acceptable (logging ≠ behaviour; the branch *is* exercised). |
| `state/modules/LanguageModule.kt` reduce | **Complete.** `RefreshFromPref`: `effective != state.effective` (write) + else (idempotent null) — both covered (`writes the resolved effective language` / `with an unchanged effective returns null`). `SetOverride`: install / clear-null / idempotent-same-value — all 3 covered (pre-existing). `RefreshFromPref does not clear an active override` covers field-independence. | none |
| `state/DictateUiState.kt` `RecordingState.audioFileOrNull` | **100% (exhaustive sealed `when`).** All 4 arms: Preparing/Active/Paused→file, Idle→null. +the R-5 `assertSame` identity invariant across Preparing→Active→Paused. | none |
| AC-5 settings-language-propagation | **Covered by a real `DictateOrchestrator`** (`settings language change propagates to LanguageState_effective via dispatch`) — boots production `LanguageModule`, asserts `"system"` sentinel → dispatch `RefreshFromPref("de")` → `Applied` + `store.snapshot.language.effective == "de"`. This is the real dispatch path, not a fake. ✓ |
| R-3 `onServiceConnected` re-push | **Coverage gap (Nice-to-have).** `DictateInputMethodService.onServiceConnected` re-calling `pushPermanentLanguageToOrchestrator()` (Java IME service, `:397`) has **no automated test** — the boot-before-bind race fix is verified by inspection/KDoc only. The `LanguageModuleTest` real-orchestrator test covers the *reducer* end of the dispatch but not the IME binder-lifecycle timing. Consistent with the project's existing pattern of not Robolectric-testing the IME service binder lifecycle; recording as a known gap, not a blocker. |
| R-5 non-recording-source edges | **Covered.** C9's `audioFileOrNull` accessor (all sealed arms + identity) is the state-side R-5 surface; the IME-side per-site re-source is exhaustively traced in the C9 report (resend reads DB `session.getAudioFilePath()`, migration reads `Pref.LastFileName` — neither ever touched the field). The LOGIC audit's R-5 concern is covered at the accessor level. |

> The LOGIC-audit's R-3 race / R-5 non-recording-source edge cases: R-5 is
> covered (accessor + trace). R-3 is **partially** covered — reducer path tested
> via real orchestrator, IME `onServiceConnected` re-push timing is NOT
> unit-tested (Nice-to-have gap above; not introduced by B3, it is inherent to
> the Java IME service's untestable binder lifecycle).

---

## Cross-Chunk-Regressions

**No true cross-chunk regression.** The only failing test (`LegacyAudioFileMigrationTest`)
is the **pre-existing C8-IMPL-1 pollution flake** (`DurationHealingJob` async
axis, zero overlap with the B3 diff — see root-cause above), not a case of one
chunk breaking another chunk's previously-green path.

**C8 deleted `LanguageControllerTest` (388 lines) + edited 6 sibling tests —
scrutinised, all legitimate:**

| Edited test | Change | Verdict |
|---|---|---|
| `LanguageControllerTest.kt` (DELETED) | 16 cases removed | **Coverage preserved.** Permanent-path cases (effective-resolution, auto-curation, pos-resync K-3, curated-replace, fresh-install) → **ported** to `LanguageResolverTest` (13 cases). ReprocessStaging-override cases → **moved** to the `LanguageModule.SetOverride` axis (`LanguageModuleTest`). `init/dispose/onEffectiveLanguageChanged` callback-lifecycle cases → **legitimately obsolete** (the `PipelineUiCallback`/cache mechanism was structurally removed by C8 — stateless `LanguageResolver` has no observer). No behavioural assertion silently dropped. |
| `DictateOrchestratorTest.kt` | 14× `RefreshFromPref` → `RefreshFromPref("en")` + comment "data object leaf"→"data-class leaves" | Mechanical compile-fix for the Dev-1 API change. **Pure placeholder usage** (routing/cascade tests) — no language-semantic assertion weakened. |
| `ModuleServicesTest.kt` | 2× same substitution | Same — mechanical, no assertion weakened. |
| `LanguageModuleTest.kt` | Placeholder `RefreshFromPref returns null in Phase 1` test **replaced** by 3 real behavioural tests + 1 real-orchestrator AC-5 propagation test | **Strengthened, not weakened.** A no-op placeholder became real behavioural + integration coverage. |
| `MultiCallbackForwardingTest.kt` | KDoc only (LC consumer reference removed) | Doc-only; no assertion change. |
| `VersionedPluginRegistryTest.kt` | 1-line comment ("LanguageController"→"LanguageResolver") | Doc-only. |
| `FakePipelineUiStateReader.kt` | testutil, LC-reference scrub | Test helper; no assertion. |

No weakened/deleted behavioural assertion is hidden as a "fix". The `git diff`
of all 6 edited siblings is mechanical-substitution + doc-update + the one
deliberate strengthening of `LanguageModuleTest`.

---

## Helper-Konsolidierung

none — C8 reuses the hoisted `net.devemperor.dictate.testutil.FakeSharedPreferences`
(`LanguageResolverTest` + the existing migration/registry tests share it, no
First-Use duplicate). C9 adds no new helper (5 inline `DictateUiStateTest` cases).
No quasi-duplicate helpers introduced across B3 chunks.
