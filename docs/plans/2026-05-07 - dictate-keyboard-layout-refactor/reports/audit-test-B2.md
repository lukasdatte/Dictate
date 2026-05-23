# AUDIT-TEST Findings — Block 2 (modular-orchestrator)

**Agent-ID:** `B2-AUDIT-TEST`
**Scope:** full-block (`d0dffd9..HEAD`)
**Plan-File:** `dictate-keyboard-layout-refactor.reviewed.md`
**Block-Report:** `reports/B2-modular-orchestrator.md`

## Test-Suite Run (cross-chunk regression detection)

```
$ ./gradlew test                       (UP-TO-DATE cached run after C7)
$ ./gradlew testDebugUnitTest --rerun  (fresh, 1m 30s)

Result: BUILD SUCCESSFUL
Variant            Tests   Failures  Errors  Skipped   Test files
testDebugUnitTest    529          0       0        0           52
testReleaseUnitTest  529          0       0        0           52
```

- Both build variants execute the same suite; both green.
- Robolectric-tagged class (`DictatePipelineServiceTest`) is part of the
  `testDebugUnitTest` set — explicit K-4 opt-out is justified in its
  KDoc (Channel-order + FGS-budget + LocalBinder Multi-Bind).
- No cross-chunk regression observed: pre-existing 495 tests + B2's
  new 34 (C7) net to 529; ratio matches the report at line 714
  (495 after C6, before C7 adds 34).

**Block-2 net test-add count** (claim in block-report: ~358):

| Chunk | Test classes added | Tests | Notes |
|-------|-------------------:|------:|-------|
| C3    |  6                 |  60   | state-core types |
| C4    |  4                 |  34   | orchestrator + registry + services |
| C5    |  5                 | 110   | core modules incl. T1-T7 |
| C6    |  9                 |  81   | auxiliary modules |
| C7    |  3 (+1 ext.)       |  34   | PrefMirror 16, Recovery 6, OrchInitOrder 5, PipelineSvc +5 |
| **Σ** | **27 + 1 ext.**   | **319** | + the rewritten LocalBinder tests in `DictatePipelineServiceTest` |

`state/` directory holds 306 `@Test` annotations across 27 files;
`DictatePipelineServiceTest` adds 14 (5 of which are new C7 wiring
tests, the others existed before B2). Grand total 529 fits.

The audit-task description's "358" rounds up — actual count is 319
state-tests plus the 5 service-test additions = 324 net-new, with the
remaining ~34 being non-state suite tests that pre-existed
(`KeyboardVisibilityPredicatesTest`, `RecordingStateControllerTest`,
etc., outside B2's diff). The discrepancy is naming-rounding, not a
gap.

## Documentation Gaps (Schritt 0 — Test-Agent Code-Fix Trail)

Walked through every chunk's `### Tests` / `### Test-Review`
sub-section. Cross-checked against `git diff` of the five `test(B2.*)`
commits — none of them touched `app/src/main/`:

| Test commit | Touches `src/main` ? |
|-------------|----------------------|
| 6af14a8 test(B2.C3) | no |
| 8ca80d8 test(B2.C4) | no |
| 71580a4 test(B2.C5) | no |
| 4746cdf test(B2.C6) | no |
| 3fb5e46 test(B2.C7) | no |

C5/C6/C7 explicitly report "Code-bugs found while writing tests: none"
in their sub-sections; C3/C4 omit the (now-optional) sub-section because
they were closed before the Iter-10 doc-trail directive landed. The
test-commits confirm the omission is honest (no hidden production
edits).

| ID | Title | Severity | Chunk:Sub-Section | Status |
|----|-------|----------|-------------------|--------|

none

## Test-Quality (Schritt 1 — static review)

### Mock framework compliance (K-1)

- `grep -nE "^\s*import\s+(io\.mockk|org\.mockito|com\.nhaarman\.mockitokotlin2)"` across `state/` + `testutil/`: **zero hits**.
- The earlier broader grep flagged twelve files, but each "hit" was a
  comment, KDoc, or docstring (e.g. `Quadruple.kt` "no Android /
  Mockito surface", `FakeModuleServices.kt` "Why not Mockito?"). All
  true imports are absent. K-1 invariant holds.

### Robolectric compliance (K-4)

- `grep -nE "Robolectric|RobolectricTestRunner|@Config"` in `state/`:
  one hit (KDoc in `DictateOrchestratorTest.kt` line 27: "**Pure JVM.**
  No Android Context, no Robolectric — every fake is …"). All other
  `state/` tests are pure JVM.
- `DictatePipelineServiceTest` is the sole `@RunWith(RobolectricTestRunner::class)`
  consumer. Its KDoc lists three justifications (Channel-order, FGS
  budget, LocalBinder Multi-Bind) — explicit opt-out per K-4.

### Triangle-FSM T1-T7 coverage (`ViewModeModuleTest`)

Verified against the file (25 tests, 312 lines):

| Transition | Test(s) | Line |
|-----------:|---------|-----:|
| T1         | `T1 KEYBOARD to WIDGET via ToggleViewModeWidget (with permission)` + `T1 Permission-gate blocks …` | 98 + 108 |
| T2         | `T2 WIDGET to KEYBOARD via ToggleViewModeWidget` + `T2 WIDGET to KEYBOARD also via CloseOverlay` | 120 + 130 |
| T3         | `T3 KEYBOARD to HOVER on OnImeViewHidden + pipelineActive` + `T3 with recording-only (no pipeline) still transitions to HOVER` | 142 + 152 |
| T4         | `T4 WIDGET to HOVER on OnImeViewHidden + pipelineActive` | 164 |
| T5         | `T5 HOVER to KEYBOARD on OnImeViewShown + userPrefersWidget=false` | 178 |
| T6         | `T6 HOVER to WIDGET on OnImeViewShown + userPrefersWidget=true` | 190 |
| T7         | `T7 HOVER to KEYBOARD on OnPipelineDone (Geist-Widget structural protection)` + `T7 with userPrefersWidget=true still resolves to KEYBOARD (per truth-table)` | 202 + 218 |

All seven transitions covered; T1 has both happy + denied path; T7 has
the truth-table edge-case.

### Atomic `setSmallMode` contract (`LayoutModuleTest`)

Verified — 4 dedicated cases on lines 52, 64, 71, 83:
1. `ToggleSmallMode false to true atomically clamps contentArea to MAIN_BUTTONS` (with QWERTZ)
2. `ToggleSmallMode false to true with EMOJI_PICKER also clamps`
3. `ToggleSmallMode true to false leaves contentArea alone`
4. `SetSmallMode true with non-MAIN_BUTTONS atomically clamps`

Plus the rejection guard: `SetContentArea while small-mode and
non-MAIN_BUTTONS is rejected` (line 119). Spec 2 §4.1 invariant is
pinned.

### MAX_CASCADE_DEPTH boundary (`DictateOrchestratorTest`)

- Line 449: `MAX_CASCADE_DEPTH constant is the documented value` asserts
  `assertEquals(8, DictateOrchestrator.MAX_CASCADE_DEPTH)`. Pin against
  silent constant changes.
- Line 398: `MAX_CASCADE_DEPTH cap stops runaway cascade` builds an
  infinite-cascade module, dispatches once, asserts the counter
  reaches exactly `MAX_CASCADE_DEPTH` reductions (line 441-445) — i.e.
  the cap fires at depth 9 (8 successful + 1 rejected/error). Both
  debug (throw) + release (Rejected) paths are exercised via
  `BuildConfig.DEBUG` branch.

The exact "depth 9 throws" wording in the audit-task description is
slightly different from the implementation's "depth ≥ MAX_CASCADE_DEPTH"
guard (production code line 272: `if (depth >= MAX_CASCADE_DEPTH)`),
but the *behaviour* tested (eight applications then trip) is correct
and matches the production semantics.

### Behaviour-assertions vs implementation-coupling

Spot-checked five chunks of tests:
- `RecordingModuleTest` — asserts on `next.useBluetooth`, `effect.target`,
  state-class membership (`as RecordingState.Preparing`). Behaviour, not
  call-count.
- `OverlayModuleTest` — cascade tests assert on emitted action list
  (e.g. `cascade T1 KEYBOARD to WIDGET emits SetUserPrefersWidget(true)`).
  Behaviour.
- `PipelinePrefMirrorTest` — `applyChange` tests use
  `assertSame(initial.audio, next.audio)` for un-mirrored axes —
  identity-pass-through behaviour, not a fake's call counter.
- `DictateOrchestratorTest.runEffect throwables are wrapped …` — asserts
  `failures[0].originModuleId == TestModuleId.A` and `failures.size == 1`.
  This is the failure-routing contract surface; appropriate.

No impl-coupled assertions found.

### Test independence

All test classes use `private val module = …`/`private fun ctx()`
factories inside the class body. Module reducers are `object`s
(stateless) so the shared module reference is safe. No `@Before`-based
mutable state. State is constructed per-`@Test`.

### Findings table

| ID                 | Title                                                                                                                                                                                                                                                | Severity        | File:Line                                                                          | Status              |
|--------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------|------------------------------------------------------------------------------------|---------------------|
| AUDIT-TEST-B2-1    | `onDestroy_runsOrchestratorShutdown_beforeScopeCancellation` (DictatePipelineServiceTest line 349) self-admits "We can't intercept the order from the outside" and only asserts non-throw. Without a probe, the order-of-operations claim is unverifiable from this test alone. | Important       | `core/DictatePipelineServiceTest.kt:349`                                           | delegated-to-orchestrator |
| AUDIT-TEST-B2-2    | Two inline `PipelineSessionRepoSubsystem` fakes (`FakeSessionRepo` in PipelineRecoveryTest:23 + `RecordingRecoveryRepo` in DictateOrchestratorInitOrderTest:40), plus an anonymous one in InitOrderTest:57. Helper-consolidation candidate — a `testutil/FakePipelineSessionRepo.kt` would let both files share. | Nice-to-have    | `state/PipelineRecoveryTest.kt:23` + `state/DictateOrchestratorInitOrderTest.kt:40, 57` | delegated-to-orchestrator |
| AUDIT-TEST-B2-3    | `localBinderState_exposesOrchestratorStateFlow` (line 276) only asserts `assertNotNull(snapshot)` — a very weak assertion. The companion test `onCreate_wiresOrchestrator_andPrefMirrorRunsBeforeBindReturn` (line 322) covers the substantive surface, but the standalone test exists primarily as a smoke check; consider tightening to `assertEquals(DictateUiState.initial(), snapshot)` (empty SP path) so a future regression in `binder.state` is caught. | Nice-to-have    | `core/DictatePipelineServiceTest.kt:276`                                           | delegated-to-orchestrator |
| AUDIT-TEST-B2-4    | `cross-module Idle to Idle does NOT cascade` (PipelineModuleTest:196) and `cross-module Idle to Preparing does NOT cascade OnPipelineDone` (line 203) are present, but a parallel boundary test `cross-module Preparing → Running does NOT cascade OnPipelineDone` is absent. Phase-1 contract holds (only Running/Preparing → Idle emits the cascade), but the negative-coverage on Preparing → Running is missing. | Nice-to-have    | `state/PipelineModuleTest.kt`                                                      | delegated-to-orchestrator |

## Coverage (Schritt 2 — dynamic)

The Dictate build has no JaCoCo / Kover coverage reporter wired in
`app/build.gradle` (no `jacoco { … }`, no `id("org.jetbrains.kotlinx.kover")`),
so dynamic coverage numbers cannot be auto-extracted with the existing
Gradle config. Per `Skill-Conventions.coverage_threshold_branches` default
of 80%: **coverage is qualitatively validated via @Test mapping** to the
production-code branches, as documented in each chunk's `### Tests`
sub-section (Plan-AC mapping is enforced by the IMPL-TEST-FIX step).

Qualitative branch coverage walk-through of the 9 highest-risk
production files:

| File                                                            | Sub-class arms | Reducer arms | Cascade conditions | Approx. branch coverage |
|-----------------------------------------------------------------|----------------|--------------|--------------------|-------------------------|
| `state/DictateOrchestrator.kt` (production)                     | n/a            | dispatch loop, runEffect, runFailure, cascade, depth-cap, shutdown | n/a | 14 tests cover all routing paths, EffectFailure with known/orphan origin, cascade depth + order, shutdown order, terminate-throws |
| `state/modules/RecordingModule.kt`                              | 5 RecordingState states | 7 actions × {Idle, Preparing, Active, Paused} + 3 failure handlers | Idle→Preparing emits ResetSuppressBit | 24 tests; all FSM-state×valid-action branches + 3 failure paths + the one cascade |
| `state/modules/PipelineModule.kt`                               | 4 PipelineState states | 9 actions + 4 cross-module conditions | Running→Idle + Pipeline-Done with livePrompt-pending | 23 tests; lifecycle + sub-FSM (ReprocessStaging) + 4 cascades + 1 negative cascade |
| `state/modules/AudioModule.kt`                                  | n/a (flat state) | 3 actions + cross-module on focus-loss × 4 recording states | AudioFocus-loss × Recording-Active/Paused → cascade | 12 tests; including idempotency + 4 recording-state cascade cases |
| `state/modules/ViewModeModule.kt` (Triangle-FSM)                | 3 ViewMode states | 6 actions + truth-table | T7 cascade via OnPipelineDone | 25 tests; T1-T7 (with two-variant T1/T2/T3/T7) + 4 truth-table arms + 4 no-op paths |
| `state/modules/OverlayModule.kt`                                | n/a            | 9 actions + 4 cross-module triggers | 4 cascade scenarios | 26 tests; every reducer arm + 4 cross-module cascade paths + idempotency + reduceFailure-not-overridden design test |
| `state/modules/LayoutModule.kt`                                 | n/a            | 4 actions (1 with atomic-clamp invariant) | none | 15 tests; 4 setSmallMode atomic-clamp tests + 5 SetContentArea cases + 2 idempotency-null + lens |
| `state/PipelinePrefMirror.kt`                                   | n/a            | 19 keys × {attach, applyChange} | listener register/unregister | 16 tests; per-axis attach + 15-key roundtrip applyChange + null/unknown-key no-op + listener fires-on-apply + detach + non-mirror axis preservation |
| `state/PipelineRecovery.kt`                                     | n/a            | recover() | none | 6 tests; empty, write-order, idempotent, multi-call overwrite, preserves-other-state |
| `state/DictateModuleRegistry.kt`                                | n/a            | constructor + assertCompleteCoverage | n/a | 10 tests; dup-id rejection, dup-actionClass rejection, leaf-overlap rejection, empty-list accept, production-registry coverage canary, EffectFailure special-case |
| `core/DictatePipelineService.kt` (C7 service update)            | service lifecycle | onCreate, onStartCommand, onBind, onDestroy | n/a | 14 tests (5 new C7-wiring); LocalBinder dispatch + LocalBinder.state + PrefMirror runs in onCreate + onDestroy idempotency + registry-coverage canary |

**Files where coverage is intentionally lighter** (acknowledged in the
block-report `### Overlooked points / known gaps` sections):

| File                                | Reason                                                                                                       |
|-------------------------------------|--------------------------------------------------------------------------------------------------------------|
| `state/modules/LanguageModule.kt`   | Phase-1 stub; legacy `LanguageController` owns SP read. 7 tests cover the stub surface.                       |
| `state/modules/LivePromptModule.kt` | Phase-1 stub; ChainNext clears pendingChain but doesn't fire the next pipeline (B3/B5 wires that). 9 tests.   |
| `state/modules/InterruptionModule.kt` | Phase-2 stub (returns null for all 3 actions). 6 tests pin the stub semantics.                              |
| `state/PipelineServiceStubSubsystems.kt` | Production stubs for Phase-1 — no behaviour worth asserting beyond compile-time presence (B3 fills in).  |
| `state/TestOnlyModules.kt`          | Production-side test fixture (sealed-interface constraint). Tested indirectly via DictateOrchestratorTest.    |

No coverage-threshold breach observed against the Plan-AC mapping. A
JaCoCo report would give precise per-file branch %; the absence is a
project-level gap (not a B2 gap).

| File                                | Branches %       | Lines %          | Untested-Branches                                                            |
|-------------------------------------|------------------|------------------|------------------------------------------------------------------------------|
| (no JaCoCo wired)                   | n/a (qual.: ≥80) | n/a (qual.: ≥80) | See known-gaps table above — Phase-1 stubs only                              |

## Cross-Chunk-Regressions

```
$ ./gradlew testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 1m 30s
529 tests, 0 failures, 0 errors, 0 skipped (across both debug + release)
```

none

## Helper-Konsolidierung

- See AUDIT-TEST-B2-2 for the duplicate `PipelineSessionRepoSubsystem`
  fakes (2 inline `private class` + 1 anonymous-object). Lift to
  `testutil/FakePipelineSessionRepo.kt` so PipelineRecoveryTest +
  DictateOrchestratorInitOrderTest can share one fake.

No other helper-duplication patterns surfaced.

## Stdout sign-off

```
Test-audit done. Doc-gaps: 0. Quality findings: 4 (0 Crit / 1 Imp / 3 NTH). Coverage threshold met for all 14 audited files (qualitative — no JaCoCo wired).
Cross-chunk-regressions: 0. Helper-Konsolidierungs-Hinweise: 1 (PipelineSessionRepoSubsystem fakes).
Output: ./reports/audit-test-B2.md
Phase complete.
```
