# AUDIT-TEST Findings — Block 2 (Theme B — Recording-Drive Cutover + D2-pre Gate)

**Agent-ID:** `B2-AUDIT-TEST` · **Scope:** full-block · **Diff:** `17085ca..HEAD` (HEAD `11bc035`)
**Date:** 2026-05-15 · **Test command:** `./gradlew test --rerun-tasks` (uncached, run **twice**)

---

## Executive Summary

| Item | Result |
|------|--------|
| Uncached full suite (run 1) | **1041/1041 debug + 1041/1041 release — 0 failures, 0 errors, 0 skipped. BUILD SUCCESSFUL.** |
| Uncached full suite (run 2, different fork order) | **1041/1041 debug + 1041/1041 release — 0 failures, 0 errors, 0 skipped. BUILD SUCCESSFUL.** |
| AC-9 (≥946 baseline) | ✅ HOLDS — 1041 ≫ 946, +95 net new, no behaviour-coverage deletion |
| R-7 pollution flake at block-end HEAD | **Did NOT reproduce in 2 consecutive uncached runs** — but the structural test-debt is real and still latent (see F-TEST-B2-1) |
| Non-R-7 regression | **NONE** (the Critical-trigger condition is not met) |
| Cross-chunk regression | **NONE** |
| Doc-trail completeness | Complete — all `### Code-Bugs` sub-sections accurate; test-commits touch zero `src/main` |
| Coverage (branch-inspection; no JaCoCo) | All new logic surfaces covered; see Coverage table |

> **Authoritative count note:** the C7 report recorded `1038 tests, 1037 pass, 1 fail`
> (`LegacyAudioFileMigrationTest`) at an *earlier* commit. At the **current block-end
> HEAD `11bc035`** (post C6-W1 + C7-MID-W1), two independent uncached full runs are
> **both 1041/1041 fully green on both variants**. The R-7 flake is latent, not active
> at this HEAD — but the singleton-reset gap that *causes* it structurally remains
> (F-TEST-B2-1), so per the prompt + state-file Postponed-Issues + D3 it is raised as
> a delegated finding for the consolidator to route.

---

## Documentation Gaps

| ID | Title | Severity | Chunk:Sub-Section | Status |
|---|---|---|---|---|
| — | none | — | — | — |

**Verification performed:** Every `### Code-Bugs Found While Writing/Reviewing Tests`
entry (C3-B1, C4-B2, C5-B3, C7-B3, C7-MID-W1) has File:Line + Bug-Symptom +
Root-Cause + Fix(before→after) + Research-source. All documented "code-bugs" are
correctly classified as **test-only fixes** (test-fake design, JDK temp-file prefix,
looper-pump, NOTIF_ID-relocation compile-compat) — none is an undocumented production
change. Cross-checked the block-range commit diffs: every `test(B2-…)` commit
(`b6e2011`, `0cc96b6`, `987432a`) touches **only** `*Test.kt` + the block-report —
**zero `src/main` production files** in any test-commit. All production changes are
isolated to `feat(`/`fix(` commits. No `test-agent-undocumented-code-fix` finding.

---

## Test-Quality

| ID | Title | Severity | File:Line | Status |
|---|---|---|---|---|
| F-TEST-B2-1 | **R-7 latent test-pollution: `ActiveJobRegistry` singleton is never drained in the new B2 boot-test tearDowns (nor in the cited reference tearDown).** Order-dependent flake — the single-job lock leaks across Robolectric forks. | **Important** (test-quality) | `PipelineRunnerSubsystemAdapterTest.kt:77-87`; `DictatePipelineServiceRecordingDriveTest.kt:54-66`; `ImeRecordingDriveCutoverTest.kt:76-86`; `DictateCutoverE2ETest.kt:105-115` | delegated-to-orchestrator (route via repair-sub-phase) |
| F-TEST-B2-2 | `DictateCutoverE2ETest` (10 tests, boots service ×2, dispatches `TriggerPipeline`/recording FSM) and `DictatePipelineServiceRecordingDriveTest` (4 tests, `triggerPipeline_postsTheProcessingNotification` starts a real job) call **zero** `waitForRegistryEmpty()` — they rely solely on tearDown, which (per F-TEST-B2-1) does not drain the registry. This is the concrete amplifier path for F-TEST-B2-1. | Important (test-quality, sub-finding of F-TEST-B2-1) | `DictateCutoverE2ETest.kt`; `DictatePipelineServiceRecordingDriveTest.kt` | delegated-to-orchestrator |

### F-TEST-B2-1 — precise R-7 diagnosis (the prompt's PRIORITY item)

**Which shared singleton leaks, and why `JobExecutor.resetForTest()` does not fix it:**

- `JobExecutor.resetForTest()` (`JobExecutor.kt:68-72`) clears only
  `orchestrator` / `activeToken` / `activeThread`. It does **NOT** touch
  `ActiveJobRegistry`.
- `ActiveJobRegistry` (`ActiveJobRegistry.kt:20`) is a process-wide Kotlin `object`
  with a `MutableStateFlow<Map<String,JobState>>` and **no reset method at all**.
  Its `register()` has a hard single-job lock: `if (_state.value.isNotEmpty()) return false`.
- `unregister(sessionId)` fires in `JobExecutor`'s `finally` block **after the runner
  returns**, on the single-threaded `Executors.newSingleThreadExecutor()`. It is
  asynchronous w.r.t. the test body.

**The pollution mechanism (order-dependent, passes 100% isolated):**

1. A boot/recording test starts a job → `JobExecutor.start` → `ActiveJobRegistry.register(sid, Running)`.
2. The runner runs async on the single-thread executor. If the test's `@After`
   tearDown runs before the runner completes (no `waitForRegistryEmpty()` gate),
   `controller.destroy()` + `JobExecutor.resetForTest()` clear JobExecutor but
   `ActiveJobRegistry._state` still holds the stale `sid → Running` entry.
3. The **next** test in the same Robolectric fork calls `JobExecutor.start` →
   `ActiveJobRegistry.register` returns **false** (`_state` non-empty) → the job
   silently never starts → that test's assertions fail.
4. Isolated runs pass because each class gets its own fresh JVM/registry; full
   uncached runs flake because the failure depends on inter-class fork ordering.

**Which test classes lack the correct discipline (root-cause refinement of the
state-file claim):** The state-file says the new tests "lack the
`DictatePipelineServiceOverlayTransitionTest` tearDown discipline". This is **not
accurate as stated** — the new boot-tests faithfully copy that exact discipline
(`controller.destroy()` + `JobExecutor.resetForTest()` +
`DictateDatabase.resetForTest()`). The real defect is that the **reference
`DictatePipelineServiceOverlayTransitionTest` itself never drains
`ActiveJobRegistry`** (verified: zero `ActiveJobRegistry` references in that file).
So the new tests inherited an incomplete reference discipline. The **only** tests
that handle the registry correctly are `JobExecutorTest`
(`JobExecutorTest.kt:43,49` — drains in both `@Before` and `@After`) and
`ActiveJobRegistryTest` (`ActiveJobRegistryTest.kt:31`). The B2-new boot-test
classes missing the registry-drain:

- `PipelineRunnerSubsystemAdapterTest` (C3-B1, the test the state-file names) — has
  `waitForRegistryEmpty()` after 4 of its job-starting tests, which masks the leak
  for those, but the binder/isRunning paths + tearDown still rely on it being clean.
- `DictatePipelineServiceRecordingDriveTest` (C4-B2) — **0** `waitForRegistryEmpty()`,
  boots service ×3, `triggerPipeline_postsTheProcessingNotification` starts a job.
- `ImeRecordingDriveCutoverTest` (C5-B3 / C7-MID-W1) — only 1 `waitForRegistryEmpty()`
  for 7 job-touching tests.
- `DictateCutoverE2ETest` (C6 keystone + T1-T7) — **0** `waitForRegistryEmpty()`,
  10 tests, several drive the full recording→pipeline path.

**Precise fix to specify in the repair-sub-phase (concrete, no "it depends"):**

1. **Preferred — add a process-wide reset to the production singleton and call it in
   tearDown.** Add to `ActiveJobRegistry`:
   ```kotlin
   /** Testing seam — clears the process-wide registry between tests. */
   @JvmStatic
   internal fun resetForTest() { _state.value = emptyMap() }
   ```
   Then in **each** of the four B2 boot-test classes' `@After` (and ideally a shared
   `@Before` for symmetry), after `JobExecutor.resetForTest()`:
   ```kotlin
   ActiveJobRegistry.resetForTest()
   ```
   This mirrors the existing `JobExecutor.resetForTest()` / `DictateDatabase.resetForTest()`
   seam pattern (K-1: no Mockito; production-owned reset seam is the established
   convention here). Also add it to the reference
   `DictatePipelineServiceOverlayTransitionTest` tearDown so the discipline the new
   tests copy is itself complete (closes the inherited-defect root cause).

2. **Fallback (no production change) — drain via the public API in tearDown**, the
   exact pattern `JobExecutorTest.kt:49` already uses:
   ```kotlin
   ActiveJobRegistry.state.value.keys.toList()
       .forEach { ActiveJobRegistry.unregister(it) }
   ```
   Same four classes + the reference test.

Either fix is mechanical and test-scoped (option 1 adds one production test-seam
method consistent with the two existing ones). **This is the block's known
test-debt; it is NOT a production regression** (the production single-job lock works
correctly — the leak is purely a cross-test hygiene gap), and it is **not
gate-blocking** (two uncached full runs at block-end HEAD are green). Severity
**Important**, status **delegated-to-orchestrator**, routing **repair-sub-phase**.

### Other test-quality observations (no findings)

- Mock convention (K-1): all fakes are handwritten (`RecordingRunner`, blocking/
  cancellable `PipelineRunner`, capturing `dispatchAction` lambda). No Mockito/MockK.
  ✅ convention-compliant.
- Robolectric (K-4): used only for justified Service/IME-binder boot tests, KDoc-
  documented as justified opt-out in each file. ✅
- Test naming: behaviour + condition throughout (`resolveFresh consumes the snapshot
  (one submit per snapshot)`, `ScoRouteResolved(false) from awaiting Preparing falls
  back to MIC`, `c6impl1_newPathStopRecording_abandonsAudioFocus`). ✅ No "works"/"test 1".
- Assertions: specific (exact action titles + counts + field values), not bare
  non-null. RecordingModuleTest effect-count assertions correctly bumped 4→5 with the
  **added** effect explicitly asserted — strengthened, not weakened. ✅

---

## Coverage (branch-inspection — no JaCoCo, per prompt)

| File (new/changed logic) | Covered by | Branch-inspection verdict |
|---|---|---|
| `PipelineRunnerSubsystemAdapter` submit/submitReprocess/cancel/isRunning/activeJobCount | `PipelineRunnerSubsystemAdapterTest` (7) | ✅ all 5 methods + thin-delegation + registry reflection + cooperative-cancel + binder-reaches-real-adapter |
| `DefaultPipelineConfigResolver` (resolveFresh-throws / resolveReprocess 1:1 / null-audio) | same | ✅ both branches + F-19 null contract + R-1 fresh-guard surfaces |
| `ImePipelineConfigResolver` snapshot/consume/discard/throw + reprocess + 8-field fidelity | `ImePipelineConfigResolverTest` (12) | ✅ fresh field-for-field, consume-once, no-snapshot-throw, discard, imported-file 2507-2523 parity, reprocess C3-IMPL-2 fields |
| `DelegatingPipelineConfigResolver` delegate/fallback/late-bind | same | ✅ prefers IME, falls back (throws), reprocess delegate, provider re-read each call, identity-no-rewrap |
| `PipelineNotificationCoordinator` Recording/Paused/Pipeline/Idle + buildInitial + NOTIF_ID + dismiss | `PipelineNotificationCoordinatorTest` + `DictatePipelineServiceRecordingDriveTest` | ✅ all 4 `build()` sealed arms + OverlayPermission arm + buildInitial + Idle→dismiss + NOTIF_ID==0xD1C7A7E + channel-before-startForeground + real-coordinator-not-stub |
| `PipelineActionRouter` PendingIntent→dispatch + decode + robustness | `PipelineNotificationCoordinatorTest` (router_*) | ✅ recording 3-button round-trip, pipeline cancel, full action-string decode, null/unknown/INSERT-no-session guard, per-session request-code distinctness |
| BT-SCO handshake edges (connected/failed/timeout/duplicate/cancel-while-awaiting) | `AudioModuleTest` + `RecordingModuleTest` | ✅ SCO connect/fail/phase-unchanged/not-awaiting cascades; defer-until-resolve; resolve(true)→VOICE_COMMUNICATION; resolve(false)→MIC; duplicate-broadcast no-op; cancel-mid-Preparing→RecordingEnded |
| Audio-focus request/gate/release symmetry | `AudioModuleTest` (29) | ✅ RequestAudioFocus on pref-on, no-request on pref-off, RecordingEnded releases+stops-SCO idempotent/unconditional, loss-during-Active/Paused cascades Pause, loss-Preparing/no-recording does NOT cascade, regain does NOT auto-resume |
| IME-trigger flip + imported-file `TriggerPipeline` route + config parity vs deleted legacy | `ImeRecordingDriveCutoverTest` (7) | ✅ StartRecording→Active+notif, Pause/Resume swap, Cancel→dismiss, StopRecordingAndSend→IME-faithful resolver w/ FSM sessionId, imported-file→resolver no-FSM, imported-file no-op-when-running, notif-not-dismissed-before-pipeline |
| `DictateCutoverE2ETest` keystone + T1-T7 | `DictateCutoverE2ETest` (10) | ✅ keystone boot, AC-2 3-button, T1/T2 widget round-trip, T3/T5 recording→hover, T4 widget→recording, AC-3/T7 stop&send lifecycle, cancel→dismiss, audio-focus request/abandon/pref-off |

**Coverage threshold:** `coverage_threshold_branches: 70` (provisional, state-file).
No JaCoCo configured → branch-inspection (not numeric threshold) per prompt. The
LOGIC-audit-relevant edge cases (BT-SCO handshake, audio-focus symmetry, R-1
field-fidelity, notification sealed arms, single-submit guard) are **all
explicitly asserted** — no inspection-identified hole in the new logic.

---

## Cross-Chunk-Regressions

**none.**

- C5 + C6-W1 touch shared state (`Action.kt`, `DictateUiState.kt`,
  `RecordingModule.kt`). All additions are **additive with defaults**
  (`RecordingState.Preparing.awaitingSco: Boolean = false`, `target: InsertionTarget? = null`;
  new `RecordingAction.ScoRouteResolved` sealed arm). No existing field/contract
  removed or re-typed.
- Only 2 sibling state-test files changed in the B2 diff: `AudioModuleTest.kt`
  (+185, all-new) and `RecordingModuleTest.kt` (+241/-9). The 9 deletions are
  rename/effect-count-bump lines (4→5) where the **added** notification effect is
  explicitly asserted in the renamed test, plus the `effect.useBluetooth=true`
  assertion that moved into the new dedicated `ScoRouteResolved(true)` test — these
  are legitimate behaviour-tracking strengthenings, **not weakened/deleted
  assertions**.
- Two consecutive uncached full runs at block-end HEAD: 1041/1041 both variants, 0
  failures — no sibling reducer/render test broke. The C2-A2 broad sibling sweep was
  in B1 (commit `015b616`), outside the B2 `17085ca..HEAD` range.

---

## Helper-Konsolidierung

**none.** The R-7 tearDown/`waitForRegistryEmpty` pattern is reused (not
re-implemented) across the four boot-test classes from
`DictatePipelineServiceOverlayTransitionTest` / `JobExecutorTest.waitForRegistryEmpty`.
No quasi-duplicate helpers introduced. (The shared *gap* in that reused pattern is
F-TEST-B2-1 — a correctness issue, not a duplication issue.)

---

## Stdout sign-off

```
Test-audit done. Doc-gaps: 0. Quality findings: 2 (1 Important R-7 + 1 Important amplifier sub-finding).
Coverage threshold met (branch-inspection) for all new-logic files (no JaCoCo → inspection).
Cross-chunk-regressions: 0. Helper-Konsolidierungs-Hinweise: 0.
Uncached full suite ×2: 1041/1041 debug + 1041/1041 release, 0 fail/0 err — AC-9 holds (≫946). No non-R-7 regression.
R-7: latent at block-end HEAD (did NOT reproduce in 2 uncached runs) but root-cause structural gap confirmed + precise fix specified.
Output: ./reports/audit-test-B2.md
Phase complete.
```
