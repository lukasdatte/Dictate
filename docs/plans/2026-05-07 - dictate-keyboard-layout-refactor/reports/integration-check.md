# Phase 4 Integration Check — dictate-keyboard-layout-refactor

**Agent-ID:** INTEGRATION
**Date:** 2026-05-15
**Scope:** Cross-block integration audit of all 6 blocks (B0–B5, 19 chunks, 52 commits since base `9e56318`).
**Diff base:** `git diff 9e56318..HEAD` (254 files, +53891 / −635; production `app/src/main/`: 99 files, +15650 / −625).

---

## Summary

**Blocks integrated cleanly? — YES, with caveats.**

Headline: The architectural seams hold. The keystone IME-activation chain
(F-1/F-2/F-3) is wired correctly end-to-end across the Java IME → Service →
orchestrator → ViewModeModule → KeyboardLayoutManager → OverlayBackend
boundary. The two-orchestrator coexistence (legacy `PipelineOrchestrator`
audio-runner vs. new `DictateOrchestrator` state-router) is coherent — no
double-dispatch, no accidental cross-wiring. The DI container is fully
registered (14 modules, `assertCompleteCoverage` wired at service-bind).
Build + full test suite green (946 tests/variant, 0 failures, reproducible
across a clean `--rerun-tasks`).

**The caveat is a postponed-issue aggregate that crosses the D15 escalation
threshold** (≥5 Important). Four B4-carry-over Important findings
(F-10/F-12/F-13/F-15) describe `DictateUiState` state-shape extensions that
were explicitly scheduled to a "B5-pre" mini-block that **was never
executed** — B5 was the floating-overlay block and did not absorb them.
Together with D-13/D-14 (LanguageController + `audioFile`-field removal,
deferred to a non-existent "B7") and the B3-forwarded
`PipelineNotificationCoordinator`/`PipelineActionRouter`/orchestrator-side
recording wiring (forwarded to "B5/B6" which did not implement it), this is
a **systemic plan-vs-implementation drift**: the plan's 6 blocks do not
contain a home for the deferred-forward work, and the forward-targets
(B5-pre, B6-as-recording-wiring, B7) do not exist in the executed block
plan. None of this is a *correctness* regression in the shipped diff (the
legacy paths remain authoritative and functional), but it is a real
unfinished-scope aggregate that must be surfaced to the user before Phase 5.

---

## Findings

| ID | Severity | File / Location | Description | Status | Suggested routing |
|----|----------|-----------------|-------------|--------|-------------------|
| INT-1 | **Critical, escalate-to-user** | state-file Postponed Issues + B4/B5 reports | **Postponed-aggregate crosses D15 threshold (≥5 Important) AND the forward-targets do not exist in the executed block plan.** B4-carry F-10/F-12/F-13/F-15 → "B5-pre" (never executed); D-13/D-14 → "B7" (no such block); B3-forwarded PipelineNotificationCoordinator/PipelineActionRouter/orchestrator-recording-wiring → "B5/B6" (B5/B6 were overlay/layout, did not implement). The shipped diff is correct because legacy paths stay authoritative, but the orchestrator-side recording pipeline is a permanent no-op stub and the plan has no remaining block to close these. | delegated-to-orchestrator | Escalate to user: decide (a) open a follow-up plan/block covering B5-pre + B7 + orchestrator-recording-wiring, or (b) accept the new state-architecture as parallel/dormant and document it as such in Phase 5 closure + ADR. Do NOT silently archive as "complete". |
| INT-2 | Important | `app/src/main/java/.../state/PipelineServiceStubSubsystems.kt` (pipelineRunner, notificationCoordinator) wired in `DictatePipelineService.onCreate` lines 419/421 | `ModuleServices.pipelineRunner` and `.notificationCoordinator` are production-route **no-op log stubs**. Any `Action` that would route to an orchestrator-side `Effect.SubmitPipelineJob` or a status-notification is silently dropped (logged at `Log.w`). This is by-design per B3 deviation summary + the stub KDoc, but it means the new orchestrator cannot drive a real recording or show a real pipeline notification — the architecture is dormant, not live. Spec 1 §10 Block-2 acceptance "Beim Recording: persistente Notification sichtbar" is **not met by the new path** (legacy notification path still works). | delegated-to-orchestrator | Part of INT-1 escalation. If accepting dormant-architecture: document the stub boundary in Phase 5 ADR/closure as a known limitation, not a bug. |
| INT-3 | Important | `state/DictateUiState.kt` (no `ReprocessStaging.isStarting`, no `Running.completedSteps/totalSteps/elapsedMs`); `state/Action.kt:137` `StopRecordingAndSend(val sessionId: String)` empty-string sentinel | B4-carry F-10/F-12/F-13 state-shape extensions confirmed **absent** in the shipped code. `StopRecordingAndSend` still uses the documented empty-string sentinel; the double-click race guard (`!s.isStarting`) and live pipeline counters have no backing fields. The B4 resolvers pass placeholders. Latent — only bites once legacy path retires (which it has not). | delegated-to-orchestrator | Same follow-up as INT-1. The B4 consolidator's "B5-pre" research+impl recommendation is still the right scope; it just needs a real block. |
| INT-4 | Nice-to-have | B5 report Issue Index lines 36/38/39 vs. lines 31/34 | **Issue-ID namespace collision across block reports.** B5's own `F-10/F-12/F-13` (OverlayDragController KDoc/comment fixes, all `fixed`) share IDs with B4's carried-over `F-10/F-12/F-13` (state-shape, `open`). The B5 report disambiguates with "(B4 carry-over)" suffix, so it is traceable, but a future reader grepping `F-10` across `reports/` hits two unrelated issues. Documentation-hygiene only — no code impact. | delegated-to-orchestrator | Phase 5 closure: note the namespace convention (per-block F-N is block-local) in the plan README, or prefix carry-overs (e.g. `B4-F-10`). |
| INT-5 | Nice-to-have | `app/src/androidTest/.../ui/KeyboardLayoutUiTest.kt` (+ others; 13 `@Ignore` total) | Espresso UI-Tests 1–10 are `@Ignore` skeletons (bodies pending). Compiles cleanly (`compileDebugAndroidTestKotlin` green) so no integration breakage, but the Spec 2 §14.2 UI-coverage for the resend-visibility bug-class is not executable. Tracked as "post-B5" in B5 issue index. | delegated-to-orchestrator | Phase 4.5 E2E covers the same behaviour manually; un-ignoring is a post-merge follow-up. Confirm acceptable in Phase 5. |

No Critical *code* integration defects found. INT-1 is Critical-by-aggregate
(D15 threshold + missing forward-targets), not a code regression.

---

## Audit-axis results

**1. Imports + types across block boundaries — PASS.**
IME (Java) ↔ Service ↔ orchestrator ↔ modules ↔ render-backends ↔ overlay
all type-consistent. `compileDebugKotlin`, `compileDebugJavaWithJavac`,
`compileDebugAndroidTestKotlin/Java` all green. The Java↔Kotlin boundary
(IME consuming `LocalBinder`, `Action.*.INSTANCE` data-objects, fully-
qualified `net.devemperor.dictate.state.*` references) is correct.

**2. DI container fully registered — PASS.**
`DictateModuleRegistry.Default.all` = **14 modules** (5 core: Recording,
Pipeline, Audio, ViewMode, Overlay; 8 aux: Resend, LivePrompt, Language,
Layout, FeatureToggle, Theming, PendingSessions, KeyboardInput; +1
Phase-2 stub: Interruption). `assertCompleteCoverage()` is invoked in
`DictatePipelineService.onCreate` (line 459) and re-throws on miss. Every
`ModuleServices` interface has a concrete binding at the composition root
(`onCreate` Step 4, lines 412–429). **Legitimately-remaining stubs:**
`pipelineRunner` + `notificationCoordinator` (INT-2 — by-design dormant,
documented). `bluetoothSco` has a defensive fallback (Robolectric/null-
AudioManager). `sessionRepo`/`audioFileFactory` stub fields are
`@Deprecated` test-only; production wires `PipelineSessionRepoAdapter` +
`CacheDirAudioFileFactory`. No accidental stubs.

**3. API contracts match — PASS.**
`LocalBinder` producer surface = consumer expectation. `state:
StateFlow<DictateUiState>`, `dispatch(Action): DispatchOutcome`, typed
getters `layoutCatalog`/`keyboardLayoutManager`/`moduleServices`/
`overlayBackend`(`?`)/`overlayPermissionObserver`/`overlayPermissionGate`
+ AI-infra getters + callback registration. IME consumes via same-process
cast in `onServiceConnected` (`DictateInputMethodService.java:351`),
guards every dispatch on `pipelineBinder != null`. `overlayBackend` is
correctly nullable on both sides.

**4. Convention drift across blocks — PASS (no systemic drift).**
Error-handling: best-effort boot paths consistently use
`try/catch(Throwable) + Log.w`; recoverable IO uses `runCatching`.
Logging: per-class `TAG` const, `Log.w/e` with throwable. KDoc: module-
header + `@see` ADR/spec anchors + gotcha-comments applied consistently
across all new `state/` files. Pref-access goes through `DictatePrefs`
sealed `Pref` (Kotlin `sp.get(Pref.X)`, Java `DictatePrefsKt.get`). No
flagged systemic drift; per-block AUDIT-CONVENTION already handled local
style.

**5. Plan-vs-implementation drift aggregate — DRIFT (see INT-1/INT-3).**
B3 deviation summary forwards 3 "real subsystem" implementations
(PipelineRunner, PipelineNotificationCoordinator, PipelineActionRouter)
+ orchestrator-side recording wiring to "B5/B6". B4 forwards 4 state-
shape findings to "B5-pre". B3 forwards D-13/D-14 to "B7". **None of
B5-pre / B6-as-recording-wiring / B7 exist in the executed 6-block
plan.** The pattern is consistent: the new state-architecture was built
to spec as a *parallel dormant* layer; the work to make it *live* (and
to retire the legacy paths) was systematically deferred forward into
blocks that the plan never contained. This is the single biggest
integration finding and is the substance of INT-1.

**6. Capability mismatch — PASS (per-pair).**
B2 orchestrator API ↔ B3 subsystem-adapters: adapters implement the B2
subsystem interfaces 1:1 (`RecordingHardwareSubsystem`, etc.), wired in
`onCreate` Step 3/4. B3 ↔ B4 render-backends: B4 `KeyboardLayoutManager`
constructed with the B2 catalog + an `onAction → orchestrator.dispatch`
sink; `RenderBackend` interface consumed unchanged. B4 ↔ B5 overlay:
`OverlayBackend` consumes `LayoutCatalog.OVERLAY_5BUTTON` +
`ModuleServices` + `RenderBackend.backendType` matching; attach/detach
via `KeyboardLayoutManager`. No signature N produces / N+M consumes
mismatch found. The only "mismatch" is the deliberate no-op
`pipelineRunner` (INT-2), which is a *binding* gap, not a *signature*
gap.

**7. Postponed-issue aggregate (D15) — ESCALATE.**
State-file Postponed Issues table + per-block reports:

| Severity | Count | Items |
|----------|-------|-------|
| Critical | 0 | — |
| Important | **8** | B2 IMPL-1 (CLOSED in d5b9f0f — excluded), D-13, D-14, F-10, F-12, F-13, F-15, B3 F-6 (PipelineRunner/NotifCoord stub forward) |
| Nice-to-have | 4 | B2 F-11/F-14 (spec-matrix doc), B3 F-29 (package move), Espresso 1-10, INT-4 |

Counting still-open Important postponed issues: **D-13, D-14, F-10,
F-12, F-13, F-15** = 6 Important, plus B3's PipelineRunner/
NotificationCoordinator forward (deviation-summary-flagged, effectively
Important) = **7 Important open**. **Threshold ≥5 Important → ESCALATE.**
Verdict: `Critical, escalate-to-user` (INT-1). Known-postponed checks
from the prompt: B2 F-11/F-14 → still NTH-postponed (Phase 4.6 doc).
B3 IMPL-1 → CLOSED (d5b9f0f, verified in code: AI-infra in
`Service.onCreate`). B3 SF-4 → CLOSED (NotifyManualPasteNeeded dispatch
present). **B4 F-10/F-12/F-13 → NOT addressed by B5; still open** (state-
shape fields confirmed absent in `DictateUiState.kt`/`Action.kt`).
D-13/D-14 → still open, "B7" target nonexistent. Espresso 1-10 → still
`@Ignore` skeletons.

**8. The keystone integration — PASS (traced end-to-end).**
Full chain verified independently in code:

1. **IME shown:** `DictateInputMethodService.onStartInputView`
   (`:1760-1775`) — guard `pipelineBinder != null` → `getOverlayPermission
   Observer().refresh()` (F-3, BEFORE dispatch) → `pipelineBinder.dispatch(
   ViewModeAction.OnImeViewShown.INSTANCE)` (F-1).
2. **IME hidden:** `onFinishInputView` (`:1128-1176`) — the load-bearing
   F-1 refactor: the 3-state early-`return` block was restructured into
   `if/else-if/else` with a **single tail dispatch** of
   `OnImeViewHidden.INSTANCE` that fires on ALL paths incl. the
   recording-active / pipeline-running branches (the primary HOVER
   trigger). Verified the dispatch is positioned after the if/else-if/else
   (line 1173–1176).
3. **Action → module:** `ViewModeModule.reduce` (`:90-107`) —
   `OnImeViewShown/Hidden` → `computeViewMode(...)` truth-table → `ViewMode`
   transition (idempotent no-op when unchanged).
4. **State → render:** `DictatePipelineService.onCreate` state-collect
   (`:517-543`) — `orchestrator.state.collect { syncOverlayBackend
   Attachment(state.viewMode); manager.onStateChanged(state) }`, wrapped
   in per-emit `try/catch` so a render exception cannot kill the pipeline.
5. **ViewMode → overlay:** `syncOverlayBackendAttachment` (`:651-683`) —
   collapses to "attach iff `viewMode != KEYBOARD`"; flag-first ordering
   on both attach and detach to survive throwing first-render/detach
   (window-leak protection, cross-class ordering guarantee documented).

B5-VAL-W1's F-1 T1–T7 trace (B5 report lines 501–528) independently
confirms each transition; my code re-read agrees. The keystone closes
the loop correctly.

**9. Two-orchestrator coherence — PASS (no cross-wiring / double-dispatch).**
Legacy `PipelineOrchestrator` (audio-pipeline runner, IME/Service-owned,
+17-line diff for KG-AFF-1 cache-delete only) drives the *actual*
recording→transcription→prompt pipeline, invoked exclusively via
`JobExecutor.INSTANCE.start(...)` from the IME (lines 2236/2897/3053).
New `DictateOrchestrator` (state-router, Service-owned) routes `Action`s
to modules. The only bridge is `ModuleServices.pipelineRunner` — a no-op
stub (INT-2) — so the new orchestrator **cannot** trigger a real
pipeline. Every `pipelineBinder.dispatch(...)` call-site in the IME was
inspected: all are non-pipeline-triggering (OverlayAction permission,
LanguageAction.RefreshFromPref, ViewModeAction shown/hidden). **No
action both starts a legacy pipeline and routes to the new orchestrator's
runner.** No double-dispatch. The coexistence matches ADR-0001 +
B2-VAL-W1 KDoc.

**10. Test-suite integrity — PASS.**
- `./gradlew test` (clean `--rerun-tasks`): **BUILD SUCCESSFUL**, exit 0,
  reproducible across two consecutive clean runs.
- JUnit aggregate (per variant): **946 tests, 0 failures, 0 errors,
  0 skipped, 93 suites** (debug == release). Slightly above the
  ~933 expected (extra B5 suites). No flakiness observed; the historical
  F-9 `LegacyAudioFileMigrationTest` flake did not reproduce (B5 F-9 fix
  added `deleteDatabase` to `resetForTest`).
- `./gradlew assembleDebug`: **BUILD SUCCESSFUL**, exit 0. APK present
  (`app/build/outputs/apk/debug/app-debug.apk`, 26 MB).
- `compileDebugAndroidTestKotlin` + `...JavaWithJavac`: **BUILD
  SUCCESSFUL** — instrumented + Espresso skeletons compile (13 `@Ignore`,
  bodies pending, INT-5).

---

## Postponed-aggregate count + threshold-escalation verdict

**Open Important postponed: 7** (D-13, D-14, F-10, F-12, F-13, F-15,
B3-PipelineRunner/NotifCoord-forward). **Open Critical: 0.** Total
open postponed (all severities): ~11.

**Threshold check (D15):** ≥1 Critical → no. **≥5 Important → YES (7).**
≥10 total → borderline (≈11). **VERDICT: ESCALATE.** Severity stamped
`Critical, escalate-to-user` on INT-1. The escalation is not about code
quality of the shipped diff (which is sound) — it is that the plan
deferred a coherent body of "make the new architecture live + retire
legacy" work into blocks (B5-pre / B6-recording / B7) that were never
part of the executed plan, so Phase 5 must not archive this as
"complete" without a user decision on the follow-up.

---

## Keystone-integration trace result

**The F-1/F-2/F-3 chain works end-to-end across all block boundaries.**
IME (B1/B3 Java) → `dispatch(OnImeViewShown/Hidden)` → `ViewModeModule`
(B2) → `ViewMode` transition → service state-collect → `syncOverlay
BackendAttachment` → `KeyboardLayoutManager` (B4) → `OverlayBackend`
(B5) attach/detach. F-3 ordering (`refresh()` before `OnImeViewShown`)
verified. The load-bearing `onFinishInputView` single-tail refactor
(F-1) verified to fire `OnImeViewHidden` on the recording-active /
pipeline-running paths (the primary HOVER trigger). No broken link in
the chain. This is the one cross-cutting integration that had to work,
and it does.

---

## Build / test commands

| Command | Result |
|---------|--------|
| `./gradlew test --rerun-tasks` | BUILD SUCCESSFUL (exit 0), reproducible ×2 |
| JUnit aggregate (per variant) | 946 tests, 0 fail, 0 error, 0 skip, 93 suites |
| `./gradlew assembleDebug` | BUILD SUCCESSFUL (exit 0), app-debug.apk 26 MB |
| `./gradlew compileDebugAndroidTestKotlin compileDebugAndroidTestJavaWithJavac` | BUILD SUCCESSFUL (exit 0) |

---

## Disposition

5 findings documented (1 Critical-by-aggregate/escalate-to-user, 2
Important, 2 Nice-to-have). **No fixes applied** (audit-class agent per
D7). INT-1 requires a user decision before Phase 5 archival — the
recommended routing is to surface the deferred-forward scope aggregate
and let the user choose follow-up-plan vs. accept-dormant-architecture.
All other findings (INT-2..INT-5) ride along with that decision or are
Phase-5 documentation-hygiene items. No code-correctness regression in
the shipped 52-commit diff.
