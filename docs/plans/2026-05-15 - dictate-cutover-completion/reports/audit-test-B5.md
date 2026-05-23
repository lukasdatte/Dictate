# AUDIT-TEST Findings — Block 5 (Theme C-R render-path cutover)

**Agent-ID:** `B5-AUDIT-TEST` · **Date:** 2026-05-17 · **Scope:** full-block
**Diff:** `c92ebd1..HEAD` (HEAD = `a6f5420`) · **Worktree:** `/home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor`

> NOTE on path: this output is written under the worktree's plan-dir
> (`worktrees/feature/dictate-cutover-completion/...`) which resolves to the
> same plan directory the orchestrator reads (`{{OUTPUT_FILE}}`).

## Executive summary (stdout-mirror)

- **Uncached pass/fail, BOTH variants, ≥2× different order — ALL GREEN:**
  - `testDebugUnitTest --rerun-tasks` run 1: **1153 / 0 fail / 0 err / 0 skip**
  - `testDebugUnitTest --rerun-tasks -Dtest.parallel.forks=1` run 2 (diff order): **1153 / 0 / 0 / 0**
  - `testReleaseUnitTest --rerun-tasks` run 1: **1153 / 0 / 0 / 16 skip**
  - `testReleaseUnitTest --rerun-tasks -Dtest.parallel.forks=1` run 2 (diff order): **1153 / 0 / 0 / 16 skip**
  - The 16 release-skips = `assumeTrue(BuildConfig.DEBUG)` audit-logger/ledger
    tests (`RenderGateTest` 2, `KeyboardLayoutManagerTest` 1,
    `OverlayCharactersControllerTest` 1, `RenderPathCutoverGateTest` 5,
    `VisibilityWriteAuditLoggerTest` 7). Expected by design — the
    `VisibilityWriteAuditLogger` early-returns in release; the identical
    proofs run green under `testDebugUnitTest`. **Not a coverage loss.**
- **Baseline 1153 ≥ AC-9 (≥946) and ≥ block-report-claimed 1153** — confirmed.
- **R-7 3rd axis: did NOT reproduce in any of my 4 uncached runs**
  (`PipelineRunnerSubsystemAdapterTest` was 7/7, 0 failures, in BOTH release
  runs). Per the prompt this still requires a structural root-cause + precise
  preventive fix — see **Finding AUDIT-TEST-B5-1** (Important,
  delegated-to-orchestrator).
- **No non-R-7 regression.** Zero failures across both variants ×2 uncached
  different-order. CR1–CR-DEL render cutover is regression-clean.
- **One build-infra flake observed + cleared:** the first `./gradlew test
  --rerun-tasks` aborted at `kspDebugKotlin` with
  `FileNotFoundException: app/build/kspCaches/debug/symbols`. This is a
  **KSP incremental-cache race** (concurrent debug+release KSP under
  `--rerun-tasks` wiping a shared cache dir), NOT a code defect — `rm -rf
  app/build/kspCaches && ./gradlew clean assembleDebug` → BUILD SUCCESSFUL,
  and all 4 subsequent split-invocation test runs green. Noted as
  **AUDIT-TEST-B5-2** (Nice-to-have, environmental).

## Documentation Gaps

| ID | Title | Severity | Chunk:Sub-Section | Status |
|---|---|---|---|---|
| — | none | — | — | — |

**Step-0 doc-completeness verdict: PASS.** All 6 test-commits (`598534b`
CR1, `590049e` CR2, `5765432` CR3, `b5e224e` CR4, `282604b` CR-RGATE,
`0c2c196` CR-DEL) contain **zero `src/main/` production files** (verified
`git show --name-only`). The CR-EXTRACT mid-chunk production fixes
(`bd74258`) and every feat-commit's production file-set match the block
report's documented scope exactly (per-commit `git show --name-only`
cross-checked against each `### Implementation`/`### Inline-fixed items`
subsection). The only `### Code-Bugs` entries are:

- CR1/CR2/CR3/CR4/CR-EXTRACT: "None" + explicitly-documented test-harness
  artifacts (Robolectric `performLongClick` NPE, async `view.animate()`
  read-back, `MaterialButton` tint read-back) — all are *test-side*
  workarounds, no production change (consistent with the zero-prod
  test-commits).
- CR-DEL: one **test-harness** id-collision bug (`captured.size + 5000`
  always 5000 → key overwrite), fixed test-side with a `capId++`
  counter; explicitly documented as NOT a production bug, and the
  production `EditBarController.applyTheme` passed on first run once ids
  were distinct. File:Line/symptom/root-cause/fix-snippet all present.

Doc-trail is accurate and complete; no `test-agent-undocumented-code-fix`.

## Test-Quality

| ID | Title | Severity | File:Line | Status |
|---|---|---|---|---|
| — | none | — | — | — |

Static review of every B5-added/changed test file (`test_file_pattern`
= `*Test.kt`):

- **Mock convention (K-1):** honoured throughout — handwritten fakes
  (`RecordingButton`, `FakeInputConnection`, capturing `MaterialButton`,
  `RecordingCallbackFake`, `RecordingRunner`); no Mockito/MockK anywhere
  in the B5 diff.
- **Robolectric (K-4):** used only where an Android `View`/`InputConnection`
  is genuinely needed (`ImeViewBackendTest`, `SpecialTouchHandlerInstallerTest`,
  the controller tests, `RenderPathCutoverGateTest`, `DictateCutoverE2ETest`);
  pure-JVM for the logger/gate/reducer tests (`RenderGateTest`,
  `VisibilityWriteAuditLoggerTest`, `RecordingModuleTest`,
  `ActionResolversTest`) with explicit per-class K-4 opt-out KDoc.
- **Naming:** behaviour+condition throughout (e.g.
  `OnRecordLongPress from Idle is null (Idle launch is IME-side - A1)`,
  `installDormant_attaches_no_touch_listener_to_live_views`,
  `recording_active_useBluetooth_true_when_pipeline_idle`). No
  "works"/"test 1".
- **Assertions:** concrete (real `View.visibility` VISIBLE/GONE, real
  `ledger.doubleWriteCount`/`soleLiveWriterOf`, `assertSame` on cached
  handlers, field-by-field `JobRequest` parity). No order-dependent or
  date/UUID snapshot anti-patterns.
- **Helper consistency:** the CR2/CR-EXTRACT `installDormant`/`attachToViews`
  + `RenderGate` dormant/`arm()` patterns are reused, not re-forked
  per-chunk; the gate-harness reuses the documented
  `DictatePipelineServiceOverlayTransitionTest` tearDown discipline
  verbatim. No quasi-duplicate-helper consolidation debt.

## Coverage (branch-inspection, block-wide)

`coverage_threshold_branches`: no JaCoCo task configured in this project
(`./gradlew test` has no coverage reporter); per the AUDIT-TEST prompt's
fallback this is a **branch-inspection** review of the new owners against
their test suites (test-count + branch-path mapping), not a JaCoCo %.

| New owner (file) | Tests | Branch-inspection verdict |
|---|---|---|
| `ImeViewBackend` (ctor / applyTheme / longClickResolver / imeSideAffordance) | `ImeViewBackendTest` 24 | ✅ ctor keyPressAnimator default + injected; `applyTheme` legacy accent tiers per owned button; CR4 long-press widened RESEND-only→every-slot; `imeSideAffordance(RECORD,true)` + `imeSideAffordance(RESEND,false)` both asserted alongside the catalog dispatch; press-anim skip SPACE/BACKSPACE/ENTER |
| `SpecialTouchHandlerInstaller` (3 §11.7 builders + single-owner) | `SpecialTouchHandlerInstallerTest` 11 | ✅ all 3 builders distinct+non-null (`installDormant_builds_all_three_handlers_distinct`); single-owner invariant (`installDormant_attaches_no_touch_listener_to_live_views`, `ownerOf_is_null_before_installDormant`, `second_installDormant_still_attaches_no_live_listener`); CR4 flip (`attachToViews_*`); §11.7 SPACE body verbatim + null-IC short-circuit; G3 cancel-cascade wire |
| `RenderGate` + `VisibilityWriteAuditLogger` | `RenderGateTest` 5 + `VisibilityWriteAuditLoggerTest` 7 | ✅ `doubleWriteCount==0` for single live writer; **two distinct LIVE writers = double-write**; dormant report ≠ conflict (RR-2 core); CR4 flip → armed gate becomes sole `live=true` writer; generation-boundary reset; null-logger no-crash |
| `EditBarController` / `EmojiController` / `OverlayCharactersController` | 13 / 12 / 7 | ✅ build-but-dormant single-owner; `attachToViews` flip; ported listener inventory; `applyTheme` legacy-tier parity (EditBar `pipelineCancel`-not-themed parity noted); OverlayChars `RenderGate` dormant/`arm()` + `doubleWriteCount==0` |
| `PipelineStepRowRenderer` (G13) | `PipelineStepRowRendererTest` 14 | ✅ all 6 deleted `KeyboardUiControllerTest` methods ported verbatim + 8 new (startPipeline/addRunningStep/completeStep/stopPipeline, toggleAutoEnter Running-only, ReprocessStaging View-side BLEIBT incl. F-6 note, multi-callback `PipelineUiStateReader` contract, `renderer_is_a_PipelineUiStateReader`) |
| `QwertzRecordingController` (G9) | `QwertzRecordingControllerTest` 5 | ✅ QWERTZ rec-button active/inactive icon, enterPipelineDisplay/updatePipelineTimer (n/m + ↵ indicator), onTimerTick two-line, prompts-controls wiring, null-rec-button safe-no-op |
| `ContentAreaController` (incl. 4th `editButtonsContainer` axis) | `ContentAreaControllerTest` 11 | ✅ 4th axis VISIBLE in MAIN/QWERTZ, GONE in EMOJI (byte-identical to deleted KSM rule); nullable-default backward-compat (3-arg callers unchanged); dormant-gate routing |
| `OnRecordLongPress` reducer arm | `RecordingModuleTest` 40 (4 directly) | ✅ Active→Idle discard-stop, Paused→Idle discard-stop, Idle→null, Preparing→null |
| `ResendCooldownExpired` dispatch | `ResendModuleTest` (cooldown arm/clear) | ✅ arm on ResendLastAudio/Long, clear on ResendCooldownExpired |
| `RenderPathCutoverGateTest` (5) + `DictateCutoverE2ETest` (10) | RR-4 non-vacuous | ✅ **NOT vacuous** — real production owners through the real bound `DictatePipelineService` binder + real `KeyboardLayoutManager` + real binder-owned `VisibilityWriteAuditLogger` with armed `RenderGate`s; assertions read real Robolectric `View.visibility` + real `ledger.doubleWriteCount`/`soleLiveWriterOf` (lines 264-296, 471-496). No mock/stub of the owner under test |

**LOGIC-audit edge-case coverage:**
- *boot-before-bind / unbound fallback:* `RenderPathCutoverGateTest` +
  `DictateCutoverE2ETest` both exercise the bound/`pipelineBinder`-guard
  path (grep-confirmed binder-harness usage).
- *double-write race:* `VisibilityWriteAuditLoggerTest` "two distinct
  LIVE writers in one generation = double-write" + the gate-test
  Strict-Mode soak across QWERTZ↔EMOJI↔MAIN ×5 + recording + keystone
  F-1/F-2/F-3 + Triangle T1/T3/T5 → `doubleWriteCount==0`.
- *resend cooldown:* `ResendModuleTest` arm/clear + the affordance
  double-click re-guard (CR4-IMPL-2/3).
- *SPACE double-commit:* `SpecialTouchHandlerInstallerTest`
  `space_handler_onTap_commits_a_space_via_inputconnection` +
  `space_handler_clears_drawables_and_short_circuits_when_no_inputconnection`;
  the click→`SpaceKey` + touch→`onTap` dual-commit is the documented
  Spec 2 §6 target (CR4-IMPL-4, Nice-to-have "not a defect", correctly
  flagged for CR-RGATE awareness only — not re-litigated here).

Branch-inspection verdict: **all new owners adequately covered**; no
untested behaviour branch identified.

## Cross-Chunk-Regressions

**none.** Zero failures across `testDebugUnitTest` ×2 + `testReleaseUnitTest`
×2, all uncached, two different fork orders. CR-DEL deleted
`KeyboardUiControllerTest` (−6) + 4 controllers and added +24 new tests
(net 1135→1153 = +18 since CR-RGATE baseline). Verified:

- The 6 deleted `KeyboardUiControllerTest` methods
  (`pipeline_preparing_defers_to_refreshFromState`,
  `recording_idle_when_pipeline_idle_paintsIdleLabel`,
  `recording_preparing_when_pipeline_idle_disables_button`,
  `recording_active_useBluetooth_true/false_when_pipeline_idle`,
  `recording_paused_when_pipeline_idle_keepsActiveButtonState`) are
  **ported verbatim** into `PipelineStepRowRendererTest` (same names,
  same RecordingState `when`-branch coverage) + 8 new — coverage
  net-increased, **no deleted-controller behaviour lost its only test**.
- `FakePipelineUiStateReader.kt` + `MultiCallbackForwardingTest.kt`
  edits are **doc-comment-only** (`KeyboardUiController` →
  `PipelineStepRowRenderer` rename in KDoc) — verified zero code/
  assertion lines changed (`git diff` filtered to non-comment hunks =
  empty). Legitimate compile-context updates, **no hidden weakened
  assertions**.
- `ContentAreaControllerTest`/`EditBarControllerTest`/`EmojiControllerTest`
  edits add the 4th-axis / `applyTheme` tests; the pre-existing 8
  `ContentAreaControllerTest` assertions are unchanged (nullable
  4th-field default verified backward-compatible).
- AC-RR-7 cleanup-grep: the 4 controller `.kt` sources are deleted;
  every residual `grep` hit in `app/src/main/` is **comment / doc-anchor
  only** (`// (legacy MainButtonsController.kt:126-130 parity)`,
  `// CR-DEL — MainButtonsController.Callback removed`, etc.) — zero
  compile-time code refs (proven by GREEN `assembleDebug`). Same
  accepted historical-trail pattern as parent-C15
  `KeyboardLayoutModeController`.

## Helper-Konsolidierung

**none.** The staged `installDormant`/`attachToViews` (CR2 →
CR-EXTRACT) and `RenderGate` dormant/`arm()` (CR3) patterns are
*reused* (the same abstraction applied to a new axis), not
quasi-duplicated. The per-controller `writeVisibility` ~6-line gate
branch (CR3 IMPL-1) is a documented deliberate non-abstraction
(SRP-pure backends; engineering-principles no-premature-abstraction) —
acceptable, not a consolidation debt.

---

## Findings (delegated)

### AUDIT-TEST-B5-1 — R-7 3rd axis: `PipelineRunnerSubsystemAdapterTest` thread-start race (JobExecutor single-thread executor never reset)

| ID | Severity | Description | Status | Reason |
|----|----------|-------------|--------|--------|
| AUDIT-TEST-B5-1 | **Important** | `PipelineRunnerSubsystemAdapterTest` "blocking runner did not start" testRelease-only flake — the 3rd distinct R-7 axis (after B2-VAL-W1's `ActiveJobRegistry` axis + B3-VAL-W1's `DurationHealingScheduler` axis). Root cause + precise fix below. | **delegated-to-orchestrator** | External-agent finding (AUDIT-TEST) — not inline-fixable per D7; routes via repair-sub-phase. Did NOT reproduce in my 4 runs but has fired in CR-EXTRACT/CR-DEL agent runs; structural root-cause + preventive fix specified per the prompt's PRIORITY directive. NOT re-postponed (D3). |

**Did it reproduce?** No. In my 4 uncached runs (debug ×2, release ×2,
two different fork orders) `PipelineRunnerSubsystemAdapterTest` was
**7/7, 0 failures** in both release runs. The flake is genuinely
intermittent (load/scheduling-sensitive) — consistent with the
CR-RGATE/CR-DEL agent observation that it "did NOT fire" in their runs
yet *has* fired in CR-EXTRACT/CR-DEL earlier runs. It is a real latent
defect, not a phantom; the prompt correctly mandates a structural fix.

**Precise root cause (the 3rd axis — distinct from the prior two).**

The failing assertion is `PipelineRunnerSubsystemAdapterTest.kt:233`
(test `isRunning and activeJobCount reflect ActiveJobRegistry`):
`check(started.await(2, TimeUnit.SECONDS)) { "blocking runner did not start" }`.
The `started` latch is counted down on the `JobExecutor` executor
thread inside the blocking runner's `runTranscription`.

`JobExecutor` (`core/JobExecutor.kt:33`) holds a **process-global
single-thread executor created once at class-load**:

```kotlin
private val executor: ExecutorService = Executors.newSingleThreadExecutor()
```

`JobExecutor.resetForTest()` (`:67-72`) clears only
`orchestrator`/`activeToken`/`activeThread` — **it does NOT touch
`executor`**. `ActiveJobRegistry.resetForTest()` clears the
single-job-lock map, and the test's `@After` (`:78-99`) also drains
`DurationHealingScheduler` + DB. None of these reset the executor's
**work queue**.

The race: `JobExecutor.start()` (`:104`) does
`executor.submit { activeThread = …; runTranscription(...) }`. The
submitted `Runnable` only counts down `started` *after* it begins
executing on the single executor thread. If a **prior test in the same
Robolectric release fork** (e.g. a sibling that calls
`JobExecutor.start` — `JobExecutorTest`, or the adapter test's own
earlier `submitReprocess` cases, or the binder-integration test that
boots the full service) submitted a `Runnable` whose body is *slow to
finish or still draining its `finally` block* (`:161-165`:
`activeThread=null; ActiveJobRegistry.unregister(...)`), that prior
`Runnable` is **still occupying the single executor thread**. Because
the executor is `newSingleThreadExecutor()` (a FIFO unbounded queue,
one worker), the new test's submitted `Runnable` is **queued behind the
not-yet-finished prior one** and does not start within the test's 2 s
`started.await(...)` → "blocking runner did not start". `waitForRegistryEmpty()`
(used as the inter-test drain) waits on `ActiveJobRegistry` going
empty, NOT on the executor queue being idle — the registry can be empty
(unregister already ran in the prior job's `finally`) while the
*executor thread itself* is still finishing that `finally`/returning,
so the next `submit` still queues behind it. testRelease-only because
release runs more aggressively co-locate forks / has different timing
than debug, widening the window (same R-7 family timing-amplification as
the prior two axes).

This is structurally the **same class** of defect B2-VAL-W1 fixed for
`ActiveJobRegistry` and B3-VAL-W1 fixed for `DurationHealingScheduler`
— a process-wide singleton whose async state leaks across
same-fork sibling tests because its `resetForTest()` seam doesn't drain
the *async carrier*. Here the un-drained carrier is the
**`JobExecutor.executor` single-thread work queue itself** (the prior
two axes drained the *registry* and the *scheduler*, but never the
executor's own queue).

**Precise fix (a `resetForTest()`-seam drain, mirroring the prior two axes).**

Add an executor-quiescence drain to `JobExecutor.resetForTest()` so a
sibling test cannot inherit a still-busy / backlogged single-thread
executor. Recommended (smallest, safest, no production-behaviour
change — the executor is never shut down in production, dies with the
process):

```kotlin
// core/JobExecutor.kt — resetForTest()
@JvmStatic
internal fun resetForTest() {
    this.orchestrator = null
    this.activeToken = null
    this.activeThread = null
    // Epic R-7 (3rd axis — AUDIT-TEST-B5-1): the process-global
    // single-thread `executor` is NOT recreated here, so a prior
    // same-fork test's still-draining Runnable would queue the next
    // test's submit behind it (FIFO single worker) → "blocking
    // runner did not start". Block until the executor queue is
    // quiescent so each test starts with an idle worker. Mirrors
    // ActiveJobRegistry.resetForTest (B2-VAL-W1) /
    // DurationHealingScheduler.resetForTest (B3-VAL-W1).
    val latch = java.util.concurrent.CountDownLatch(1)
    executor.submit { latch.countDown() }
    check(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
        "JobExecutor executor did not become quiescent within 5s"
    }
}
```

Submitting a no-op sentinel and awaiting it guarantees every previously
submitted `Runnable` (including its `finally`) has fully completed
before the next test runs — because the single worker is FIFO, the
sentinel cannot run until all predecessors have. This is the exact
"drain the async carrier in the production-owned reset seam" mechanic
of the prior two axes, applied to the third (the executor queue).
`@After` ordering stays: `JobExecutor.resetForTest()` first (now also
drains the executor), then `ActiveJobRegistry.resetForTest()`, then
scheduler, then DB — no ordering change needed; the executor drain must
precede the registry clear so a late-finishing job's `finally`
`unregister` does not race the registry reset (the drain ensures the
`finally` is already done).

*Alternative considered + rejected:* recreating the executor
(`executor = Executors.newSingleThreadExecutor()`) — rejected because
`executor` is a `val` and making it reassignable adds a production
mutability footgun for a test-only concern; the sentinel-drain keeps
`executor` immutable and is strictly a quiescence wait. A
deterministic-executor injection seam was also considered but is a
larger refactor than the R-7 family's established
`resetForTest()`-drain convention warrants (D7 — match the existing
proven pattern, three axes now consistent).

**Repair-sub-phase scope:** one production-file edit
(`core/JobExecutor.kt` `resetForTest()`), no test edit required (the
existing `@After` already calls `JobExecutor.resetForTest()` first).
Verify by re-running `testReleaseUnitTest --rerun-tasks` ≥2× — the
adapter test must stay 7/7.

### AUDIT-TEST-B5-2 — KSP incremental-cache race under `./gradlew test --rerun-tasks`

| ID | Severity | Description | Status | Reason |
|----|----------|-------------|--------|--------|
| AUDIT-TEST-B5-2 | Nice-to-have | `./gradlew test --rerun-tasks` (combined debug+release in one invocation) intermittently aborts at `:app:kspDebugKotlin` with `java.io.FileNotFoundException: app/build/kspCaches/debug/symbols (No such file or directory)` — concurrent debug+release KSP work-actions racing on the shared `kspCaches` dir under `--rerun-tasks`. **Environmental/build-infra, not a code defect.** | open (not a defect) | `rm -rf app/build/kspCaches && ./gradlew clean assembleDebug` → BUILD SUCCESSFUL; running the two variants as separate invocations (`testDebugUnitTest` then `testReleaseUnitTest`) is fully green ×2 each. Documented so future AUDIT-TEST runs prefer split invocations + a `kspCaches` purge before `--rerun-tasks`. No production/test change. |

---

## Stdout sign-off

```
Test-audit done. Doc-gaps: 0. Quality findings: 0. Coverage: all new owners adequately covered (no JaCoCo task configured — branch-inspection verdict PASS). Cross-chunk-regressions: 0. Helper-Konsolidierung: 0.
Uncached BOTH variants ×2 different-order: debug 1153/0/0/0 ×2, release 1153/0/0/16-skip ×2 (16 = expected DEBUG-guarded assumeTrue skips). No non-R-7 regression.
R-7 3rd axis: did NOT reproduce in 4 runs; structural root-cause = JobExecutor's process-global single-thread executor queue is never drained by resetForTest() → a prior same-fork test's still-finishing Runnable queues the next submit behind it (FIFO single worker) → "blocking runner did not start". Precise fix: add a sentinel-submit-and-await executor quiescence drain to JobExecutor.resetForTest() (mirrors ActiveJobRegistry/DurationHealingScheduler resetForTest). Raised as Important AUDIT-TEST-B5-1 (delegated-to-orchestrator, repair-sub-phase, NOT re-postponed per D3).
Output: ./reports/audit-test-B5.md
Phase complete.
```
