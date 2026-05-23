---
date: 2026-05-15
author: Lukas + Claude Code (planning session)
type: Plan
status: Implementer-ready
context: Make the new DictateOrchestrator drive production recording + notification, retire the legacy controllers/fields it renders dormant, and close the Espresso UI-test gap — the cutover the parent plan deferred into blocks that never existed.
related-plan: n/a (top-level plan)
related-adrs: ADR-0001, ADR-0002, ADR-0003, ADR-0004, ADR-0005
archive_target: 2026-05-15 - dictate-cutover-completion
---

This Epic completes the cutover the `dictate-keyboard-layout-refactor` plan
left unfinished. That plan built a complete new state-architecture
(DictateOrchestrator + 14 modules + RenderBackends + Overlay + Triangle-FSM)
**as a parallel dormant layer** and shipped it unit-green (946 tests), but the
Phase-4 Integration Check (INT-1, Critical, escalated to user) found the new
orchestrator never drives a real recording or the foreground notification —
the legacy `PipelineOrchestrator` + `LanguageController` + `audioFile`-field +
legacy controllers still own production. The cutover-completion work was
repeatedly forwarded to blocks ("B5-pre", "B6", "B7") that the executed plan
never contained. This Epic is the home for that work: it makes the new layer
*live*, retires the legacy paths it renders dead, and closes the test gap — so
a single coherent architecture remains, not a half-migration.

This is a **destructive cutover on a working app**. Recording is the product's
core feature. §6 (Risiken + Rollback) is load-bearing — read it before
implementing any Theme-B/C chunk.

## Table of Contents

- [§1 Kontext & Auslöser](#1-kontext--auslöser)
- [§2 Ziele / Acceptance-Kriterien](#2-ziele--acceptance-kriterien)
- [§3 Architektur-Übergang (legacy → new cutover seam map)](#3-architektur-übergang-legacy--new-cutover-seam-map)
- [§4 Building Blocks (Implementierungs-Reihenfolge)](#4-building-blocks-implementierungs-reihenfolge)
- [§5 Spec-References](#5-spec-references)
- [§6 Risiken & Rollback](#6-risiken--rollback)
- [§7 Verbleibende offene Fragen](#7-verbleibende-offene-fragen)
- [§8 Referenzen](#8-referenzen)
- [§9 Iteration-Log](#9-iteration-log)

## Glossary

### Orchestrators (the two-orchestrator coexistence)

- **Legacy `PipelineOrchestrator`** — the audio-pipeline *runner*
  (record → transcribe → reword → insert). IME/Service-owned, invoked via
  `JobExecutor.INSTANCE.start(...)`. Drives **production today**. Defined in
  `core/PipelineOrchestrator.kt` (1386 LOC).
- **New `DictateOrchestrator`** — the *state-router*. Service-owned, routes
  `Action`s to 14 modules, pure reducers + cascades. Currently **dormant** for
  recording/notification because two subsystem bindings are no-op stubs.
- **`JobExecutor`** — process-global single-job lock + cooperative-cancel
  token + `ActiveJobRegistry`. Wraps `PipelineOrchestrator`. **Never deleted**
  per Spec 1 §9.6 — it will *implement* `PipelineRunnerSubsystem`.

### The dormant seam

- **`pipelineRunner` stub** — `PipelineServiceStubSubsystems.pipelineRunner`,
  a `Log.w`-and-discard `PipelineRunnerSubsystem`. The reason the new
  orchestrator cannot start a real pipeline (INT-2).
- **`notificationCoordinator` stub** — same file, a `Log.w` no-op
  `PipelineNotificationCoordinatorSubsystem`. The reason the new path shows no
  foreground notification (INT-2; Spec 1 §10 Block-2 acceptance unmet by the
  new path).
- **Parallel-dormant layer** — the new architecture, fully built + unit-green
  but inert in production. D7's failure mode: shipping this as the permanent
  state would leave a half-migration forever.

### State-shape gaps (Theme A)

- **F-10** — `Action.RecordingAction.StopRecordingAndSend(sessionId = "")`
  documented empty-string sentinel. Needs a real sessionId source.
- **F-12** — SendStaging single-submit guard. *(B1-VAL-W1 option b,
  2026-05-15: resolved **without** an `isStarting` field — the canonical
  Spec 1 §3 defines `ReprocessStaging(sessionId, transcript)` only, and
  the legacy `isStarting` was a dead field. The FSM `ReprocessStaging →
  Preparing` edge on main-thread-confined dispatch is the canonical
  guard. See research/sendstaging-isstarting-guard-semantics.md.)*
- **F-13** — missing `PipelineUiState.Running.completedSteps/totalSteps/elapsedMs`
  fields (live progress for record-button text + notification).
- **F-15** — `LayoutStrings.dictateButtonText` is not language-aware
  (tied to D-13 LanguageController removal).

### Legacy-retire targets (Theme C)

- **D-13** — full `LanguageController` removal (~18 caller-graph files incl.
  the `DictateApplication` Application-singleton + `PreferencesFragment`).
- **D-14** — `DictateInputMethodService.audioFile` field removal
  (declaration `:222`, ~9 IME-side reads/writes).
- **Dead controllers** — `KeyboardUiController` / `RecordingUiController` /
  `MainButtonsController` / `KeyboardStateManager`: removable once Theme B
  makes the RenderBackend path primary and the legacy render path dead.

> **`PipelineRunner` ≠ `PipelineOrchestrator` ≠ `JobExecutor`.**
> `PipelineRunnerSubsystem` is the *interface* the new orchestrator dispatches
> through (`submit`/`cancel`/`isRunning`). `JobExecutor` is the production
> *implementer* of that interface (single-job lock). `PipelineOrchestrator`
> is the *runner body* `JobExecutor` invokes. Theme B wires interface →
> implementer; it does **not** rewrite the runner body.

## 1. Kontext & Auslöser

### 1.1 Why this Epic exists

The parent plan `dictate-keyboard-layout-refactor` (6 blocks, 19 chunks,
52 commits, 946 tests) is architecturally sound in its shipped diff — the
keystone IME-activation chain (F-1/F-2/F-3) is wired end-to-end, the
two-orchestrator coexistence is coherent (no double-dispatch), the DI
container is fully registered, build + tests green. **But** the Phase-4
Integration Check (`reports/integration-check.md`, INT-1, Critical,
escalate-to-user) found a systemic plan-vs-implementation drift:

- The new `DictateOrchestrator` is a **parallel dormant layer**. It routes
  `Action`s correctly but cannot drive a real recording or the foreground
  notification because `ModuleServices.pipelineRunner` and
  `.notificationCoordinator` are no-op `Log.w` stubs
  (`PipelineServiceStubSubsystems.kt`, wired at
  `DictatePipelineService.onCreate:419/421`).
- The work to make it *live* and to retire the legacy paths was forwarded
  across blocks to "B5-pre" (state-shape F-10/F-12/F-13/F-15), "B6"
  (orchestrator-recording-wiring + the Spec 1 §7.4 NotificationCoordinator),
  and "B7" (D-13 LanguageController + D-14 audioFile-field removal). **None
  of B5-pre / B6 / B7 existed in the executed 6-block plan.**
- The shipped diff is *correct* only because the legacy `PipelineOrchestrator`
  + `LanguageController` + `audioFile`-field remain authoritative and
  functional. INT-1's count: **7 open Important postponed issues**, crossing
  the D15 escalation threshold (≥5).

The user decision (2026-05-15): **implement the cutover now, written as a new
Epic.** This is that Epic. It chooses INT-1 routing option (a) —
follow-up-plan that makes the new architecture live + retires the legacy —
over (b) accept-dormant. Rationale: D7 (long-term-highest-quality). A
permanent parallel-dormant layer is two implementations of the same feature,
forever — the exact anti-pattern (distributed state mutation) the parent
refactor existed to kill.

### 1.2 What problem this solves

1. **Dead architecture.** ~16k LOC of new state-architecture that does nothing
   in production. Every future feature would have to be written twice (legacy
   path + dormant path) or the dormant layer rots.
2. **The Spec 1 §10 Block-2 acceptance is unmet by the new path.** "Beim
   Recording: persistente Notification sichtbar, zeigt korrekte Action-Buttons"
   — only the legacy notification path delivers this; the new path's
   coordinator is a `Log.w` stub.
3. **Latent state-shape bugs.** F-10's empty-string sentinel, F-12's missing
   double-click guard, F-13's placeholder progress counters — all latent
   *until* legacy retires, at which point they bite for real.
4. **Two language sources of truth.** `LanguageController` (legacy) and
   `LanguageModule` (new) both exist; a bridge keeps them in sync. D-13 wants
   one.

### 1.3 Discarded alternatives

- **Accept the dormant layer, document as known-limitation (INT-1 option b).**
  Rejected: violates D7. Two implementations of recording forever; the next
  developer cannot tell which path is authoritative without archaeology.
- **Delete the new architecture, keep legacy.** Rejected: throws away
  946 tests + 5 ADRs of accepted design + the keystone Triangle-FSM that fixes
  the Geist-Widget bug. The new architecture is the *correct* one; legacy is
  the thing being retired.
- **Big-bang cutover (flip everything in one commit).** Rejected: recording is
  the core feature; an un-staged flip with no per-step safety net bricks the
  product on the first regression. The Epic stages the cutover behind the
  legacy safety-net (see §6).

## 2. Ziele / Acceptance-Kriterien

**Headline:** the new `DictateOrchestrator` drives production recording +
notification; legacy `LanguageController` + `audioFile`-field + dead
controllers retired; Espresso UI-Tests 1–10 green; no parallel-dormant layer
remains.

Concrete, testable:

1. **AC-1 (compile invariant).** `PipelineServiceStubSubsystems.pipelineRunner`
   and `.notificationCoordinator` are no longer referenced from
   `DictatePipelineService.onCreate` — `grep -n "StubSubsystems.pipelineRunner\|StubSubsystems.notificationCoordinator" app/src/main/` returns zero hits in `core/`. The two stub `val`s either deleted or `@Deprecated` test-only (mirroring `sessionRepo`/`audioFileFactory`).
2. **AC-2 (behaviour, E2E).** Recording started from the IME drives the **new**
   orchestrator path: `state.recording` transitions
   `Idle → Preparing → Active`, the FGS notification shows
   `NotificationStatus.Recording` with the `[Pause][Stopp][Senden]` action
   buttons (Spec 1 §10 Block-2 acceptance, §7.4/§7.6). Verified by
   `DictatePipelineServiceRecordingDriveTest.kt` + the manual two-keyboard E2E
   runbook.
3. **AC-3 (behaviour, E2E).** Stop-and-send drives the new orchestrator:
   `StopRecordingAndSend` carries a real sessionId (F-10 closed), the pipeline
   runs via `PipelineRunnerSubsystem` (JobExecutor-backed), the notification
   transitions `Recording → Pipeline → Idle`, `stopSelf()` dismisses it after
   insertion.
4. **AC-4 (state-shape, compile + unit).** The SendStaging single-submit
   guard is the FSM `ReprocessStaging → Preparing` edge — a second tap
   arrives in `Preparing` and reduces to `null` (F-12; *B1-VAL-W1 option
   b, 2026-05-15: no `isStarting` field — Spec 1 §3 defines
   `ReprocessStaging(sessionId, transcript)` only; the legacy field was
   dead. See research/sendstaging-isstarting-guard-semantics.md*);
   `PipelineUiState.Running` has `completedSteps: Int`, `totalSteps: Int`,
   `elapsedMs: Long` (F-13); `StopRecordingAndSend` has no empty-string
   sentinel call-site (F-10); `LayoutStrings.dictateButtonText` resolves
   language-aware via `LanguageModule` state (F-15). Reducer unit-tests
   cover each new field's transition + the SendStaging single-submit
   guard (the FSM `→Preparing` edge: a second tap is a no-op).
5. **AC-5 (legacy-retire, compile invariant).** `LanguageController.kt` is
   deleted; `grep -rl "LanguageController" app/src/main/` returns zero hits
   (D-13). The `DictateApplication` singleton + `PreferencesFragment` +
   `KeyboardUiController` consumers migrated to `LanguageModule` + dispatch.
6. **AC-6 (legacy-retire, compile invariant).**
   `DictateInputMethodService.audioFile` field is deleted; all ~9 IME-side
   reads sourced from orchestrator state (D-14). `grep -n "private File audioFile" app/src/main/java/.../DictateInputMethodService.java` returns zero.
7. **AC-7 (dead-controller retire, compile invariant).** The legacy render
   controllers made dead by Theme B are deleted per Spec 1 §9.6 + Spec 2 §9.1
   retire-tables; the End-of-Block-Cleanup-Check `grep` (Spec 1 §9.6) passes
   for every class marked "Final gelöscht". `PipelineOrchestrator` either
   deleted or reduced to a thin `PipelineRunnerSubsystem`-adapter (decision in
   §4 Block-3 / §7 OQ-1).
8. **AC-8 (test-completion).** Espresso UI-Tests 1–10
   (`androidTest/.../ui/KeyboardLayoutUiTest.kt`) have implemented bodies (no
   `@Ignore`, no `fail("pending:")`); `connectedDebugAndroidTest` for those 10
   passes (or, where device-infra unavailable, the equivalent Robolectric
   render-assertion passes — see §4 Block-9).
9. **AC-9 (regression invariant).** Full `./gradlew test` green (≥ 946 tests,
   no net deletions of behaviour coverage); `./gradlew assembleDebug` green;
   the parent plan's keystone F-1/F-2/F-3 trace still passes.
10. **AC-10 (single-architecture invariant).** No production code path both
    starts a legacy `JobExecutor` pipeline *and* a new-orchestrator pipeline
    for the same user action (no double-dispatch). The two-orchestrator
    coexistence is collapsed: `DictateOrchestrator` is the sole state-router,
    `JobExecutor`/`PipelineOrchestrator` survive only behind the
    `PipelineRunnerSubsystem` interface.

## 3. Architektur-Übergang (legacy → new cutover seam map)

### 3.1 BEFORE — parallel dormant layer (shipped state, post parent-plan)

```
┌─────────────────────────────────────────────────────────────────────┐
│  DictateInputMethodService.java  (IME — Java, ~3252 LOC)            │
│  • owns `audioFile` field (D-14)                                    │
│  • owns LanguageController calls (D-13)                             │
│  • startRecording → JobExecutor.INSTANCE.start(request) ───────┐    │
│  • dispatch(ViewModeAction / LanguageAction.RefreshFromPref)   │    │
│    → new orchestrator (state only, NON-pipeline)               │    │
└────────────────────────────────────────────────────────────────┼────┘
        │ renders via                                             │
        ↓ (legacy render path — PRIMARY today)                    │ drives (PRIMARY today)
┌──────────────────────────────────┐         ┌────────────────────▼──────────────┐
│ KeyboardUiController             │         │ JobExecutor → PipelineOrchestrator │
│ RecordingUiController            │         │ (record→transcribe→reword→insert)  │
│ MainButtonsController            │         │ + legacy notification path         │
│ KeyboardStateManager            │         └────────────────────────────────────┘
└──────────────────────────────────┘
        ╎ (new render path — built, unit-green, but only HOVER/overlay live)
        ╎
┌───────────────────────────────────────────────────────────────────────────────┐
│  DictatePipelineService.kt  → DictateOrchestrator (14 modules, state-router)     │
│  • drives ViewMode/Overlay/Triangle-FSM (LIVE — keystone wired)                  │
│  • ModuleServices.pipelineRunner        = STUB  (Log.w no-op)  ◄── INT-2 DORMANT │
│  • ModuleServices.notificationCoordinator = STUB  (Log.w no-op) ◄── INT-2 DORMANT│
└───────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 AFTER — single coherent architecture (this Epic's target)

```
┌─────────────────────────────────────────────────────────────────────┐
│  DictateInputMethodService.java  (IME — Java, slimmed)              │
│  • NO audioFile field  (D-14 done — sourced from state.recording)  │
│  • NO LanguageController  (D-13 done — dispatch LanguageAction)    │
│  • startRecording → pipelineBinder.dispatch(                       │
│        RecordingAction.StartRecording(target, audioFile))  ───────┐ │
│  • stopAndSend → dispatch(StopRecordingAndSend(realSessionId)) ───┤ │
└────────────────────────────────────────────────────────────────────┼┘
        │ renders via                                                 │
        ↓ (RenderBackend path — sole render path)                     │ dispatches (sole driver)
┌──────────────────────────────────┐         ┌───────────────────────▼───────────────────────┐
│ KeyboardLayoutManager            │         │ DictateOrchestrator (14 modules, state-router)  │
│  → ImeViewBackend / OverlayBackend│        │ • RecordingModule.runEffect →                   │
│ (legacy controllers DELETED)     │         │     ModuleServices.recordingHardware (real)     │
└──────────────────────────────────┘         │ • PipelineModule.runEffect →                    │
                                              │     ModuleServices.pipelineRunner ──────────┐   │
                                              │ • notificationCoordinator (REAL) shows FGS  │   │
                                              └─────────────────────────────────────────────┼───┘
                                                                                            │ implements PipelineRunnerSubsystem
                                                                          ┌─────────────────▼──────────────────┐
                                                                          │ JobExecutor → PipelineOrchestrator  │
                                                                          │ (runner body — survives behind the │
                                                                          │  PipelineRunnerSubsystem interface)│
                                                                          └────────────────────────────────────┘
```

The single structural change: the two `STUB` boxes become real adapters
(`pipelineRunner` → a `JobExecutor`-backed `PipelineRunnerSubsystem`;
`notificationCoordinator` → the Spec 1 §7.4 `PipelineNotificationCoordinator`),
the IME's recording-trigger flips from `JobExecutor.INSTANCE.start` to
`pipelineBinder.dispatch(RecordingAction.*)`, and everything the legacy render
path + legacy language path + `audioFile` field touched is deleted once dead.
`JobExecutor`/`PipelineOrchestrator` are **not rewritten** — they survive as
the `PipelineRunnerSubsystem` implementation (Spec 1 §9.6: "`JobExecutor` nie
gelöscht — implementiert das `PipelineRunner`-Interface").

Authoritative retire-tables (do not duplicate — Spec is SoT):
- **Spec 1 §9.6** "Lösch-/Adapter-/Erhalt-Tabelle" — heutige Klassen → künftiger Status, per block.
- **Spec 2 §9.1–§9.6** — `KeyboardLayoutModeController` (entfällt vollständig),
  `MainButtonsController → ImeViewBackend`, `KeyboardStateManager → 3 Owner`,
  `RecordingUiController → KeyboardUiController-Anteile + LayoutCatalog`,
  the 4 problematic resend-mutations in the IME.
- **Spec 1 §7.4 / §7.5 / §7.6** — `PipelineNotificationCoordinator`,
  `PipelineActionRouter`, Notification-Inhalt (the real impls Theme B writes).

## 4. Building Blocks (Implementierungs-Reihenfolge)

Dependency order is load-bearing: **Theme A first** (state-shape — unblocks B
+ C), **then Theme B** (recording-drive — the live cutover), **then Theme C**
(legacy-retire — only safe once B makes legacy dead), **then Theme D**
(test-completion — locks the result). Each block is sized M/L for
implement-long-plan-v2 (5-step chunk workflow compatible: Impl →
Plan-Correctness-Fix → Self-Code-Fix → Tests → Test-Self-Review).

### Block A1 — State-shape extensions: F-12 / F-13 (size M)

- **Scope.** F-12: the SendStaging single-submit guard in
  `PipelineModule.reduce` is the FSM `ReprocessStaging → Preparing` edge —
  the first `SendStaging` transitions to `Preparing`; a second tap arrives
  with `pipeline is Preparing` and falls to `else -> null`. Dispatch is
  main-thread-confined (ADR-0001) so the two taps are serialized; the FSM
  edge is the guard. *(Plan-deviation B1-VAL-W1, option b, 2026-05-15:
  the literal pseudo-code `if (state.isStarting) null else
  copy(isStarting=true)` was **not** implemented — it strands the
  reprocess job because `StartPipeline` only fires from `Preparing`, and
  the legacy `isStarting` field is a dead carry-over the canonical Spec 1
  §3 `ReprocessStaging(sessionId, transcript)` does not define. No
  `isStarting` field is added to the new `state/` module. See
  research/sendstaging-isstarting-guard-semantics.md.)* F-13: add
  `PipelineUiState.Running.completedSteps/totalSteps/elapsedMs`. Wire the
  `Running` counters: `StepCompleted` increments `completedSteps`;
  `StartPipeline` sets `totalSteps`; `elapsedMs` derived from
  `ReducerContext.now`. Replace the B4-resolver placeholders that read these.
- **Files.** `state/DictateUiState.kt` (the two `data class` variants),
  `state/modules/PipelineModule.kt` (reducer arms), the B4 record-button-text
  resolver + any `LayoutStrings` counter consumer reading placeholders.
- **Dependencies.** None — pure state-shape, isolated. First block.
- **Acceptance.** AC-4 (F-12/F-13 part). New reducer unit-tests:
  SendStaging-while-starting → no-op; StepCompleted increments
  `completedSteps`; `Running` counter labels render real values not
  placeholders.
- **Risk.** Low. Additive fields with defaults; no call-site flips. Sealed
  `data class` field additions are source-compatible (defaulted).

### Block A2 — State-shape: F-10 sessionId source + F-15 language-aware strings (size M)

- **Scope.** F-10: give `StopRecordingAndSend` a real sessionId. The IME
  pre-allocates a UUID for `JobExecutor.register()` already
  (`DictateInputMethodService.java:2213` `preAllocatedId`). Source the
  sessionId from `state.recording`'s session (or thread the pre-allocated id
  into `RecordingAction.StartRecording` → carried in `RecordingState` → read
  on `StopRecordingAndSend`). Remove the documented empty-string sentinel
  (`Action.kt:137` KDoc + every call-site). F-15: make
  `LayoutStrings.dictateButtonText` read `LanguageModule`'s effective-language
  state instead of a static string (depends on `LanguageState.effective`,
  already in `DictateUiState` — no D-13 dependency for the *read*; D-13 only
  removes the legacy *writer*).
- **Files.** `state/Action.kt` (StopRecordingAndSend KDoc/shape),
  `state/RecordingState` payload (if sessionId threaded through Preparing/Active
  — note: `RecordingState.Active` currently carries `audioFile`+`useBluetooth`;
  adding `sessionId` here is the clean source), `state/modules/RecordingModule.kt`
  reducer, `LayoutStrings`/record-button resolver for F-15.
- **Dependencies.** A1 (same `PipelineUiState`/state-shape surface; sequential
  to avoid two parallel diffs on `DictateUiState.kt`/`Action.kt`).
- **Acceptance.** AC-4 (F-10/F-15 part). Unit-test: `StopRecordingAndSend`
  carries the same sessionId minted at `StartRecording`; no `sessionId = ""`
  literal anywhere (`grep`); `dictateButtonText` differs across two
  `LanguageState.effective` values.
- **Risk.** Medium. Threading sessionId through `RecordingState` touches the
  RecordingModule FSM that the parent plan's §15.2 spec fully specifies — must
  stay spec-faithful (Spec 1 §15.2). F-15 must not pre-empt D-13 (read-only
  from state is fine; do not add a new legacy writer).

### Block B1 — Real `PipelineRunnerSubsystem` adapter (size L)

- **Scope.** Replace `PipelineServiceStubSubsystems.pipelineRunner` with a
  production `PipelineRunnerSubsystem` adapter backed by `JobExecutor`
  (Spec 1 §9.6: `JobExecutor` implements `PipelineRunner`; §13.3.11). The
  adapter's `submit(sessionId, audioFile)` builds a
  `JobRequest.TranscriptionPipeline` (mirroring the IME's current
  `DictateInputMethodService.java:2214-2236` construction) and calls
  `JobExecutor.INSTANCE.start(...)`; `submitReprocess` → the reprocess
  JobRequest; `cancel(sessionId)` → `JobExecutor.cancel`; `isRunning` /
  `activeJobCount` → `ActiveJobRegistry`. Wire it into
  `DictatePipelineService.onCreate` Step 4 (`ModuleServices(... pipelineRunner = PipelineRunnerSubsystemAdapter(...) ...)`) replacing line 419.
  `PipelineModule.runEffect`'s pipeline-submit Effect now reaches a real
  runner.
- **Files.** New `core/PipelineRunnerSubsystemAdapter.kt` (mirrors the existing
  `core/*Adapter.kt` pattern, e.g. `BluetoothScoSubsystemAdapter`),
  `core/DictatePipelineService.kt` onCreate Step 3/4 (construct + wire),
  `state/PipelineServiceStubSubsystems.kt` (demote `pipelineRunner` to
  `@Deprecated` test-only or delete).
- **Dependencies.** A2 (needs the real sessionId in `RecordingState` to bridge
  IME-trigger → runner). Spec 1 §7.5 `PipelineActionRouter` is needed only if
  the runner emits actions back — see B2 (notification) for the back-channel;
  this block is submit-direction only.
- **Acceptance.** AC-1 (pipelineRunner part). Robolectric test: dispatching a
  pipeline-submit Action through the real binder calls `JobExecutor.start`
  (spy/mock) with a correct `JobRequest`; `isRunning` reflects the registry.
  No `Log.w("pipelineRunner...")` reachable in production wiring.
- **Risk.** **High.** This is the load-bearing recording-drive cutover. The
  JobRequest construction in the IME (language resolution, prompt queue,
  style-prompt, livePrompt, autoSwitch, recordingsDir) is intricate
  (`DictateInputMethodService.java:2196-2241`) — the adapter must reproduce it
  faithfully or recordings silently lose config. Mitigation: the IME path
  stays the dispatch *source*; the adapter is a thin translation; keep the
  legacy `JobExecutor.start` call-site reachable behind a guarded fallback
  until B3 proves the new path (see §6 rollback).

### Block B2 — Real `PipelineNotificationCoordinator` (size L)

- **Scope.** Write the Spec 1 §7.4 `PipelineNotificationCoordinator` class
  (the spec specifies it fully — §7.4/§7.6 Notification-Inhalt, §11.1.2
  `Notification.Builder` concrete impl, §7.5 `PipelineActionRouter` for the
  action-button → Action back-channel). Replace
  `PipelineServiceStubSubsystems.notificationCoordinator` with it; wire into
  `onCreate` Step 4 (replacing line 421). `show(NotificationStatus.Recording)`
  → persistent FGS notification with `[Pause][Stopp][Senden]`;
  `NotificationStatus.Pipeline` → progress; `dismiss()` on Idle. The
  action-buttons dispatch back through `PipelineActionRouter` →
  `orchestrator.dispatch(RecordingAction.* / PipelineAction.*)`. NOTIF_ID
  single-source-of-truth (Spec 1 §10 Phase-B-S-5 NOTIF_ID-Konsistenz: only
  `PipelineNotificationCoordinator.NOTIF_ID`).
- **Files.** New `core/PipelineNotificationCoordinator.kt` +
  `core/PipelineActionRouter.kt` (Spec 1 §7.4/§7.5),
  `core/DictatePipelineService.kt` onCreate Step 4 + the BroadcastReceiver /
  PendingIntent wiring for action-buttons, `state/PipelineServiceStubSubsystems.kt`
  (demote/delete `notificationCoordinator`), `res/` notification strings (verify
  `[Pause][Stopp][Senden]` strings exist; F-5 added overlay strings —
  pipeline-notification strings may need adding).
- **Dependencies.** B1 (the runner must be live so a `Recording`/`Pipeline`
  status is reachable end-to-end to verify the notification reflects real
  state). F-13 (A1) — the progress counters the notification displays.
- **Acceptance.** AC-2 + AC-3. The Spec 1 §10 Block-2 acceptance
  ("persistente Notification sichtbar, zeigt korrekte Action-Buttons") is met
  by the **new** path. Robolectric/instrumented:
  `DictatePipelineServiceRecordingDriveTest.kt` asserts the notification is
  posted with the 3 action-buttons on `Recording`, transitions on `Pipeline`,
  dismissed on `Idle`; action-button PendingIntent dispatches the right Action.
- **Risk.** **High.** FGS notification correctness is OS-version-sensitive
  (Android 13+ POST_NOTIFICATIONS, FGS-5s-Frist, channel-before-startForeground
  ordering — Spec 1 §10 has explicit acceptance tests for each). A botched
  coordinator can crash the FGS (`startForeground` IllegalArgumentException) →
  recording dies. Mitigation: Spec 1 §11.1.2 gives the exact `Notification.Builder`;
  reuse the existing channel-creation in `onCreate` Step 1; the legacy
  notification path stays until AC-2 verified (§6).

### Block B3 — IME recording-trigger cutover + double-dispatch elimination (size L)

- **Scope.** Flip `DictateInputMethodService.java`'s recording trigger from
  `JobExecutor.INSTANCE.start(request)` (`:2236`) to
  `pipelineBinder.dispatch(Action.RecordingAction.StartRecording(target, audioFile))`
  and the stop-and-send path to
  `dispatch(Action.RecordingAction.StopRecordingAndSend(realSessionId))`
  (F-10, sessionId from A2). The new orchestrator's RecordingModule.runEffect
  drives `ModuleServices.recordingHardware` (real adapter, already wired);
  PipelineModule.runEffect drives the B1 runner; B2 coordinator shows the
  notification. Remove the now-dead direct `JobExecutor.start` call-site
  **only after** the guarded-fallback window (§6) — i.e. this block lands the
  dispatch path + a feature-guarded fallback, the *deletion* of the legacy
  call-site is the block's final chunk gated on the E2E proving green.
  Eliminate any double-dispatch (AC-10): audit every IME
  `pipelineBinder.dispatch` + every `JobExecutor.start` so no user action
  triggers both.
- **Files.** `DictateInputMethodService.java` (the `startRecording` /
  stop-and-send / cancel methods ~`:2165-2293`, `onFinishInputView`
  recording-active branch, the standalone-prompt path `:2251-2290`),
  possibly `core/PipelineOrchestrator.kt` (only if the runner-adapter needs a
  hook — prefer none).
- **Dependencies.** B1 + B2 (the runner + notification must be real before the
  IME stops calling the legacy path) + A2 (real sessionId).
- **Acceptance.** AC-2 + AC-3 + AC-10 end-to-end. The two-keyboard manual E2E
  (Spec 1 §10 Tastatur-Wechsel-Survival) passes via the **new** path:
  record → switch to Gboard → 30s → back → recording still alive, notification
  correct, stop-and-send completes. `grep` proves no user action
  double-dispatches.
- **Risk.** **Highest.** This is the irreversible flip of the core feature.
  Recording-pipeline-survival across keyboard-switch (the parent plan's
  raison d'être, ADR-0003) must keep working. Mitigation: guarded fallback
  (the legacy `JobExecutor.start` stays one boolean away until the E2E runbook
  signs off — see §6); deletion is the last chunk, separately committed,
  reversible by reverting that one commit.

### Block C1 — `LanguageController` full removal (D-13) (size L)

- **Scope.** Delete `core/LanguageController.kt`. Migrate every consumer to
  `LanguageModule` + dispatch. Caller graph (verified):
  `DictateApplication.java` (Application-singleton — the hard one: it holds a
  process-global `LanguageController`; migrate to reading
  `LanguageModule`/`DictatePrefs` or the bound service state),
  `DictateInputMethodService.java`, `settings/PreferencesFragment.java`,
  `core/KeyboardUiController.kt`, `core/PipelineUiStateReader.kt`,
  `core/DictatePipelineService.kt`, plus the bridge dispatch from B3-era. The
  IME already dispatches `LanguageAction.RefreshFromPref` (`:874`) — extend
  that pattern. F-15 (A2) consumers now read `LanguageState.effective` with no
  legacy writer behind it.
- **Files.** Delete `core/LanguageController.kt` +
  `app/src/test/.../core/LanguageControllerTest.kt`; edit
  `DictateApplication.java`, `DictateInputMethodService.java`,
  `settings/PreferencesFragment.java`, `core/KeyboardUiController.kt` (if not
  yet deleted by C3), `core/PipelineUiStateReader.kt`,
  `core/DictatePipelineService.kt`. Adjust
  `testutil/FakePipelineUiStateReader.kt`, `MultiCallbackForwardingTest.kt`.
- **Dependencies.** B3 (the language-during-recording path must be on the new
  orchestrator before the legacy language controller is removed; the bridge
  that kept both in sync is removed here).
- **Acceptance.** AC-5. `grep -rl "LanguageController" app/src/main/` → zero.
  Settings-activity language change still propagates to the next transcription
  (manual + `LanguageModuleTest` extension). The `DictateApplication`
  singleton no longer holds a `LanguageController`.
- **Risk.** Medium-High. The `DictateApplication` Application-singleton is a
  process-global with non-service-bound lifetime — its consumers may run
  before the service binds. Mitigation: route through `DictatePrefs` (the
  source of truth `LanguageController` mirrored) + the LanguageModule for the
  bound path; document the boot-before-bind ordering (mirrors the parent
  plan's `pipelineBinder != null` guard pattern).

### Block C2 — `audioFile` field removal (D-14) (size M)

- **Scope.** Delete `DictateInputMethodService.audioFile` (`:222`). The ~9
  reads/writes (`:1374, :1880, :2104, :2116, :2122, :2218, :2379, :3042`)
  sourced from orchestrator state (`state.recording` is
  `Active(useBluetooth, audioFile)` / `Preparing(...)` — the audioFile lives
  there post-B3) or from the `CacheDirAudioFileFactory` directly where the
  field was just a scratch handle. `onAudioPersisted` (`:2379`) takes the file
  as a parameter already — no field needed.
- **Files.** `DictateInputMethodService.java` only (field decl + ~9 sites).
- **Dependencies.** B3 (the audioFile must be authoritative in
  `RecordingState` before the IME field is removed) + A2 (sessionId threading
  established the RecordingState payload pattern).
- **Acceptance.** AC-6. `grep -n "private File audioFile" …` → zero; recording
  still produces a valid audio file end-to-end (E2E).
- **Risk.** Medium. Several reads are in non-recording paths (legacy-migration
  `:1880`, resend `:2104`). Each must be traced to its real source (state vs
  factory vs Pref.LastFileName) — a wrong source silently breaks resend or
  migration. Mitigation: per-site analysis table in the chunk; the parent
  plan's `b3-cleanup` research already established the `Pref.LastFileName`
  semantics.

### Block C3 — Dead-controller retire + PipelineOrchestrator disposition (size L)

- **Scope.** Delete the legacy render/state controllers that Theme B + the
  parent plan's RenderBackend path made dead, per the **Spec 1 §9.6** +
  **Spec 2 §9.1–§9.6** retire-tables: `KeyboardLayoutModeController` (already
  deleted in parent C15 — verify), `MainButtonsController`,
  `RecordingUiController`, `KeyboardUiController` (state + view parts),
  `KeyboardStateManager`. Run the Spec 1 §9.6 End-of-Block-Cleanup-Check
  `grep` for each. **Decide `PipelineOrchestrator` disposition** (OQ-1):
  Spec 1 §9.6 says `PipelineOrchestrator` is the runner body
  `JobExecutor`/the B1 adapter invokes — it is **not deleted**, it survives as
  the `PipelineRunnerSubsystem` implementation detail. Confirm B1's adapter
  delegates to it (not a reimplementation) and document the boundary.
- **Files.** Delete `core/MainButtonsController.kt`,
  `core/RecordingUiController.kt`, `core/KeyboardUiController.kt`,
  `core/KeyboardStateManager.kt` (+ their tests); edit any residual IME
  references; keep `core/PipelineOrchestrator.kt` (annotate it as the
  `PipelineRunnerSubsystem` adaptee per Spec 1 §9.6).
- **Dependencies.** B3 (render path must be solely RenderBackend before
  controllers deleted), C1 (KeyboardUiController held a LanguageController
  ref), C2.
- **Acceptance.** AC-7 + AC-10. Spec 1 §9.6 cleanup-grep passes for every
  "Final gelöscht" class. `PipelineOrchestrator` reachable only via the B1
  adapter; no other caller (`grep`).
- **Risk.** Medium. A residual IME reference to a deleted controller is a
  compile error (caught fast). The subtle risk: a controller method with a
  side-effect not yet ported to a module — mitigation: per-class
  responsibility-trace against Spec 2 §9.x before deletion (the spec maps each
  method to its new owner).

### Block D1 — Espresso UI-Tests 1–10 (size L)

- **Scope.** Implement the 10 `@Ignore`/`fail("pending:")` skeletons in
  `androidTest/.../ui/KeyboardLayoutUiTest.kt` per **Spec 2 §14.2** test-body
  table (UI-1..UI-10, each mapped to a §1.1 bug-symptom). Un-`@Ignore`,
  implement the Espresso assertions. UI-3/UI-8/UI-10 depend on the F-13
  `Running` counters (A1) + the live recording-drive (B3) being real. Where
  device instrumentation infra is unavailable in CI, provide the equivalent
  Robolectric render-assertion (the parent plan's
  `DictatePipelineServiceOverlayTransitionTest` shows the binder-harness
  pattern) and keep the Espresso body for `connectedAndroidTest`.
- **Files.** `androidTest/.../ui/KeyboardLayoutUiTest.kt` (10 test bodies),
  possibly new Robolectric mirror tests under `test/`.
- **Dependencies.** A1 (F-13 counters), B3 (live drive — UI-2/UI-3 record),
  C3 (render path is solely RenderBackend, so the assertions test the real
  path not the dead one).
- **Acceptance.** AC-8. The 10 tests pass (no `@Ignore`, no `fail("pending:")`).
- **Risk.** Low-Medium. Espresso device-infra flakiness; mitigated by the
  Robolectric mirror fallback. Pure test code — no production risk.

### Block D2 — Integration E2E + Triangle-FSM re-trace (size M)

- **Scope.** Re-run the parent plan's keystone F-1/F-2/F-3 trace + the
  Triangle-FSM T1–T7 E2E **at integration level on the new live path** (not
  the dormant one). Runbook-driven: the two-keyboard manual E2E (Spec 1 §10),
  the FGS-survival + OOM-kill-recovery acceptance (Spec 1 §10 Phase-B-S-5),
  the notification action-button round-trip (B2). Confirm AC-9 + AC-10
  holistically — full `./gradlew test` + `assembleDebug` + the cleanup-greps.
- **Files.** Test/runbook only; possibly a new
  `DictateCutoverE2ETest.kt` aggregating the cross-block trace.
- **Dependencies.** All prior blocks.
- **Acceptance.** AC-9 + AC-10 + the parent plan's keystone trace green on the
  new path. No regression vs the 946-test baseline.
- **Risk.** Low (verification block) — but it is the gate that authorises
  removing the §6 guarded-fallback permanently.

### 4.1 Block dependency graph

```
A1 ──► A2 ──► B1 ──► B2 ──► B3 ──► C1 ──► C3 ──► D1 ──► D2
              │             │      └► C2 ─┘
              └─────────────┘  (B2 also needs A1/F-13)
```

8 blocks across 4 themes (A: A1,A2 · B: B1,B2,B3 · C: C1,C2,C3 · D: D1,D2 — 9
blocks total; C2 is parallelizable with C1 after B3 but sequenced for
git-index safety). Strictly sequential per implement-long-plan-v2 block-mode.

## 5. Spec-References

The specs remain the **single source of truth** for the detailed contracts.
This Epic points; it does not duplicate.

| Topic | Authoritative spec section | Used by Block |
|---|---|---|
| `PipelineRunner` interface + JobExecutor-as-implementer | Spec 1 §9.6, §13.3.11 | B1 |
| `PipelineNotificationCoordinator` class | Spec 1 §7.4, §11.1.2 | B2 |
| `PipelineActionRouter` (action-button back-channel) | Spec 1 §7.5 | B2 |
| Notification-Inhalt (`[Pause][Stopp][Senden]`, status mapping) | Spec 1 §7.6, §11.5 | B2 |
| Block-2 acceptance (persistent notification, FGS survival, OOM) | Spec 1 §10 | B2, B3, D2 |
| RecordingModule reducer (sessionId/audioFile payload) | Spec 1 §15.2 | A2, B3 |
| Cross-module coupling matrix (Pipeline×Resend etc.) | Spec 1 §15.1.x | A1, A2 |
| Legacy-retire table (per-class, per-block) | Spec 1 §9.6 | C1, C3 |
| Controller→Backend retire map | Spec 2 §9.1–§9.6 | C3 |
| Espresso UI-Tests 1–10 bodies + bug-symptom map | Spec 2 §14.2 | D1 |
| Triangle-FSM T1–T7 + IME-activation wiring | ADR-0005, `research/b5-ime-activation-wiring.md` §3 | D2 |
| Manual-paste field architecture (already landed; consumer wiring) | `research/manual-paste-field-architecture.md` §6 | A1 (context) |
| Cleanup-cascade / backfill (already landed; orthogonal) | `research/b3-cleanup-cascade-and-backfill-policy.md` §6 | (context only) |

## 6. Risiken & Rollback

> [!CAUTION]
> This is a **destructive cutover on a working app**. Recording is the
> product's core feature. A botched Theme-B/C step ships an app that cannot
> dictate. The legacy path is the safety net — it stays reachable until each
> cutover step is **proven** green by the E2E runbook, not assumed.

### 6.1 The big risks (top 3)

1. **R-1 — Recording-drive flip silently loses pipeline config (B1+B3).**
   The IME's `JobRequest.TranscriptionPipeline` construction
   (`DictateInputMethodService.java:2214-2236`) threads language, prompt-queue,
   style-prompt, livePrompt, autoSwitch, recordingsDir, origin. The B1 adapter
   must reproduce **all** of it. A dropped field → recordings transcribe with
   wrong language / no prompts, silently. *Mitigation:* B1 acceptance asserts
   the full `JobRequest` field-by-field against a spy; B3 keeps the legacy
   `JobExecutor.start` call-site behind a guarded fallback (one boolean) until
   D2 signs off; the deletion of the legacy call-site is B3's final, separately
   committed, revertible chunk.
2. **R-2 — FGS notification coordinator crashes the foreground service (B2).**
   `startForeground` is OS-version-sensitive (channel-before-start ordering,
   FGS-5s-Frist, Android 13+ POST_NOTIFICATIONS). A coordinator bug throws
   `IllegalArgumentException` in `startForeground` → the FGS dies →
   recording-during-keyboard-switch (ADR-0003's whole point) breaks.
   *Mitigation:* Spec 1 §11.1.2 gives the exact `Notification.Builder`; reuse
   the existing channel creation (`onCreate` Step 1); Spec 1 §10 has explicit
   acceptance tests for channel-order + FGS-latency + NOTIF_ID — all must pass
   before B3 flips the IME trigger.
3. **R-3 — `LanguageController` removal breaks the Application-singleton path
   (C1).** `DictateApplication.java` holds a process-global
   `LanguageController` with non-service-bound lifetime; some consumers run
   before the service binds. A naive removal NPEs or silently uses
   stale/`"system"` language. *Mitigation:* route the unbound path through
   `DictatePrefs` (the SoT `LanguageController` mirrored anyway) + the bound
   path through `LanguageModule`; replicate the parent plan's
   `pipelineBinder != null` guard discipline; C1 acceptance includes the
   settings-change-propagation manual check.

### 6.2 Rollback strategy — the legacy path is the safety net

The cutover is **staged**, not big-bang. Invariants:

- **Theme A is non-destructive** (additive defaulted fields) — independently
  revertible by reverting the block's commits; nothing downstream breaks
  because the new fields have defaults.
- **Themes B1/B2 add the real adapters without deleting the legacy path.**
  After B1/B2 the legacy `JobExecutor.start` + legacy notification are still
  reachable. Reverting B1/B2 commits restores the dormant-but-working state.
- **B3 lands the IME-trigger flip behind a guarded fallback.** The legacy
  `JobExecutor.start(request)` call-site is kept one boolean
  (`USE_LEGACY_RECORDING_DRIVE`, default off after D2) away. If the new path
  regresses in dogfood, flip the boolean → instant rollback to legacy with no
  revert. The boolean + legacy call-site are removed only in B3's final chunk,
  gated on D2 green, as a separate revertible commit.
- **Themes C1/C2/C3 are the point of no return** — they delete the legacy
  paths. They run **after** D2 proves the new path. Each C-block is a separate
  commit; a regression found post-C is fixed forward (the new path is proven
  by D2 at that point), not rolled back to legacy (which no longer exists).
- **Per-block git commits** (5-step workflow, 2 commits/chunk) keep the
  history bisectable; any block is `git revert`-able in isolation up to B3's
  final chunk.

### 6.3 Other risks

- **R-4 — double-dispatch during the B3 transition window.** While the guarded
  fallback exists, a bug could route a user action to *both* the legacy
  `JobExecutor.start` and the new `dispatch`. *Mitigation:* AC-10 grep-audit
  is a B3 acceptance gate; the fallback is mutually-exclusive (boolean
  switch, not parallel).
- **R-5 — `audioFile` field removal (C2) breaks resend/migration.** Several
  reads are non-recording (legacy-migration, resend). *Mitigation:* per-site
  source-analysis table in the C2 chunk; `Pref.LastFileName` semantics already
  researched (`b3-cleanup` research).
- **R-6 — Espresso device-infra unavailable in CI (D1).** *Mitigation:*
  Robolectric render-assertion mirror; Espresso body retained for
  `connectedAndroidTest`.
- **R-7 — test-pollution amplification.** The new IME-boot Robolectric tests
  share the `DictateDatabase` singleton + default prefs (parent plan's F-9
  flake). *Mitigation:* every new boot-test copies the
  `DictatePipelineServiceOverlayTransitionTest` `tearDown` (DB/pref/JobExecutor
  reset) — mandated by `b5-ime-activation-wiring.md` §8.

## 7. Verbleibende offene Fragen

1. **OQ-1 — `PipelineOrchestrator` final disposition.** Spec 1 §9.6 says
   `PipelineOrchestrator` is *never deleted* — it is the runner body the
   `PipelineRunnerSubsystem` adapter invokes. This Epic adopts that (B1's
   adapter delegates to it; C3 keeps it). **Owner:** implementer confirms in
   B1 that a thin delegation (not a reimplementation of the 1386-LOC runner)
   is feasible; if `JobExecutor`'s API does not expose a clean
   submit/cancel/isRunning surface the adapter needs, B1 escalates. *Fallback:*
   the adapter wraps `JobExecutor.INSTANCE` exactly as the IME does today
   (proven code path).
2. **OQ-2 — guarded-fallback boolean lifetime.** The Epic keeps
   `USE_LEGACY_RECORDING_DRIVE` until D2. **Owner:** user — confirm whether
   the boolean should ship in one dogfood release (default-on-legacy) before
   B3's final deletion chunk, or be removed immediately after D2 in the same
   Epic run. *Default if no input:* remove immediately after D2 green (D7 —
   no lingering dead switch), per AC-10.
3. **OQ-3 — Notification strings.** B2 needs `[Pause][Stopp][Senden]`
   notification-action strings + de/en locales. F-5 added overlay strings;
   pipeline-notification strings may or may not exist. **Owner:** B2
   implementer audits `values/strings.xml`; if missing, adds them (mirrors
   F-5's locale-file discipline). Not a blocker — additive.
4. **OQ-4 — Espresso vs Robolectric for AC-8.** Whether CI runs
   `connectedAndroidTest`. **Owner:** user/infra. *Default:* D1 ships both the
   Espresso body (for device runs) and a Robolectric mirror (for CI green) —
   AC-8 is satisfied by either being green.

No open question blocks starting Theme A. OQ-1/OQ-2 surface at B1/B3 (mid-Epic)
with documented fallbacks; OQ-3/OQ-4 are additive/test-only.

## 8. Referenzen

- **Parent plan:** `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md`
- **Integration check (INT-1 escalation source):** `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/reports/integration-check.md`
- **State-file Postponed Issues:** `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.state.md` §"Postponed Issues"
- **Research files (deferred-work source):**
  - `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/b5-ime-activation-wiring.md` (Triangle-FSM IME-wiring, §3/§8)
  - `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/manual-paste-field-architecture.md` (ResendState relocation — landed; B3-recovery forward-compat §6)
  - `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/b3-cleanup-cascade-and-backfill-policy.md` (FK/backfill — landed; orthogonal, §6 forward-compat)
- **ADRs:**
  - `docs/decisions/0001-state-modular-orchestrator-pattern.md` (single-dispatch, pure reducers, lens, registry)
  - `docs/decisions/0002-state-cross-module-cascade.md` (Mode-1/2/3 cascade rules)
  - `docs/decisions/0003-service-foreground-pipeline-architecture.md` (FGS container — the cutover's structural enabler; cleanup-policy Decision-History)
  - `docs/decisions/0004-ui-layout-catalog-motionlayout.md` (RenderBackend rendering side)
  - `docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md` (ViewMode FSM; IME-activation contract Decision-History)
- **Specs (SoT — not duplicated here):**
  - Spec 1 `…/research/1-pipeline-service/1-pipeline-service.reviewed.md` (§7.4/§7.5/§7.6 NotificationCoordinator, §9.6 retire-table, §10 acceptance, §15.x module contracts)
  - Spec 2 `…/research/2-keyboard-layout/2-keyboard-layout.reviewed.md` (§9.x controller-retire, §14.2 Espresso bodies)
  - Spec 3 `…/research/3-floating-overlay/3-floating-overlay.reviewed.md` (§4.8 OverlayModule, §5 permission-onboarding)
- **Key code seams:**
  - `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt` (the two no-op stubs — INT-2)
  - `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` (composition root, onCreate Step 3/4 lines 302/362/419/421)
  - `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (IME — recording trigger `:2236`, `audioFile` field `:222`, LanguageController sites)
  - `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt` (legacy runner — survives as PipelineRunnerSubsystem adaptee)
  - `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt` + `Action.kt` (F-10/F-12/F-13 state-shape surface)
  - `app/src/main/java/net/devemperor/dictate/state/ModuleServices.kt` (`PipelineRunnerSubsystem`/`PipelineNotificationCoordinatorSubsystem` interfaces, `NotificationStatus`)

## 9. Iteration-Log

### 2026-05-15 — Epic authored from INT-1 escalation + user-decision

- **Trigger:** Phase-4 Integration Check INT-1 (`Critical, escalate-to-user`)
  on the `dictate-keyboard-layout-refactor` plan: the new
  `DictateOrchestrator` is a parallel-dormant layer; the cutover-completion
  work (state-shape F-10/F-12/F-13/F-15, recording-drive INT-2, legacy-retire
  D-13/D-14, Espresso 1–10) was forwarded to blocks B5-pre/B6/B7 that the
  executed plan never contained. User decision (2026-05-15): implement the
  cutover now, as a new Epic.
- **Reasoning:** INT-1 routing option (a) chosen over (b) accept-dormant, per
  D7 — a permanent parallel-dormant layer is two implementations of recording
  forever, the exact anti-pattern the parent refactor existed to kill. The
  Epic stages the cutover behind the legacy safety-net (guarded fallback until
  E2E sign-off) because recording is the product's core feature and a
  big-bang flip bricks it.
- **What changed:** Epic created at
  `docs/plans/2026-05-15 - dictate-cutover-completion/dictate-cutover-completion.md`.
  9 blocks across 4 themes (A state-shape → B recording-drive → C
  legacy-retire → D test-completion), dependency-ordered, M/L-sized for
  implement-long-plan-v2. Specs remain SoT (referenced, not duplicated).
  `archive_target: 2026-05-15 - dictate-cutover-completion` set.

---

<!-- EXECUTION-PLAN -->
<!--
implement-long-plan-v2 block/chunk breakdown. Block-mode always active.
Strictly sequential (dependency-ordered). Each block = 1+ M/L chunks,
5-step chunk workflow (Impl → Plan-Correctness-Fix → Self-Code-Fix →
[Commit 1] → Tests → Test-Self-Review → [Commit 2]). Specs are SoT —
chunks reference §-sections, do not re-derive contracts.

THEME A — state-shape (non-destructive, unblocks B+C):
  BLOCK A1  [size M, 1 chunk]   F-12 isStarting + F-13 Running counters
                                 → state/DictateUiState.kt, PipelineModule.kt
                                 → reducer unit-tests (SendStaging guard, counter increment)
  BLOCK A2  [size M, 1-2 chunks] F-10 sessionId source + F-15 language-aware strings
                                 → Action.kt, RecordingState payload, RecordingModule.kt
                                 → unit-tests (sessionId continuity, no "" sentinel grep)
                                 dep: A1

THEME B — recording-drive cutover (the live flip; guarded-fallback):
  BLOCK B1  [size L, 2 chunks]   PipelineRunnerSubsystemAdapter (JobExecutor-backed)
                                 → new core/PipelineRunnerSubsystemAdapter.kt,
                                   DictatePipelineService.onCreate Step 3/4,
                                   demote StubSubsystems.pipelineRunner
                                 → Robolectric: dispatch→JobExecutor.start spy, full JobRequest assert
                                 dep: A2   RISK: HIGH (R-1)
  BLOCK B2  [size L, 2 chunks]   PipelineNotificationCoordinator + PipelineActionRouter
                                 → new core/PipelineNotificationCoordinator.kt,
                                   core/PipelineActionRouter.kt, onCreate Step 4,
                                   demote StubSubsystems.notificationCoordinator,
                                   notification strings (OQ-3)
                                 → DictatePipelineServiceRecordingDriveTest.kt
                                 dep: B1 + A1(F-13)   RISK: HIGH (R-2)
  BLOCK B3  [size L, 2-3 chunks] IME recording-trigger flip + double-dispatch elim
                                 → DictateInputMethodService.java (startRecording,
                                   stop-and-send, cancel, standalone, onFinishInputView)
                                   behind USE_LEGACY_RECORDING_DRIVE guard;
                                   final chunk = delete legacy call-site (gated on D2)
                                 → two-keyboard E2E, AC-10 grep-audit
                                 dep: B1+B2+A2   RISK: HIGHEST (R-1/R-4) — final chunk gated on D2

THEME C — legacy-retire (point of no return; after D2 proves new path):
  BLOCK C1  [size L, 2 chunks]   LanguageController full removal (D-13)
                                 → delete core/LanguageController.kt(+test),
                                   migrate DictateApplication.java, IME,
                                   PreferencesFragment.java, KeyboardUiController,
                                   PipelineUiStateReader, DictatePipelineService
                                 → grep zero LanguageController; settings-propagation test
                                 dep: B3   RISK: MED-HIGH (R-3)
  BLOCK C2  [size M, 1 chunk]    audioFile field removal (D-14)
                                 → DictateInputMethodService.java only (field + ~9 sites,
                                   per-site source-analysis table)
                                 → grep zero "private File audioFile"; E2E audio still produced
                                 dep: B3   RISK: MED (R-5)
  BLOCK C3  [size L, 2 chunks]   dead-controller retire + PipelineOrchestrator disposition
                                 → delete MainButtonsController, RecordingUiController,
                                   KeyboardUiController, KeyboardStateManager (+tests);
                                   keep PipelineOrchestrator as PipelineRunnerSubsystem adaptee (OQ-1)
                                 → Spec1 §9.6 cleanup-grep passes per class
                                 dep: B3+C1+C2   RISK: MED

THEME D — test-completion (locks the result; D2 gates B3-final + C-blocks):
  BLOCK D1  [size L, 2 chunks]   Espresso UI-Tests 1–10 bodies (Spec 2 §14.2)
                                 → androidTest/.../ui/KeyboardLayoutUiTest.kt,
                                   Robolectric mirror fallback (OQ-4)
                                 dep: A1(F-13)+B3+C3   RISK: LOW-MED (R-6)
  BLOCK D2  [size M, 1 chunk]    Integration E2E + Triangle-FSM re-trace
                                 → DictateCutoverE2ETest.kt aggregating keystone+T1-T7,
                                   full ./gradlew test + assembleDebug + cleanup-greps
                                 → GATE: signs off B3-final-chunk deletion + authorises C-blocks
                                 dep: all   RISK: LOW (verification gate)

ORDERING NOTE for the orchestrator: D2 is a verification GATE, not a tail.
The dependency reality is A1→A2→B1→B2→B3(guarded)→D2(sign-off)→
B3-final-deletion + C1→C2→C3→D1(needs C3)→final D2 re-run. Practically:
run A1,A2,B1,B2,B3-guarded, then D2-pre (verify new path green) to authorise
B3-final + Themes C, then D1, then D2-final (full regression + cleanup-grep).
The skill should treat B3's final deletion chunk and the C-blocks as gated on
a green D2-pre run.

ESTIMATE: 9 blocks, ~15-17 chunks total. Implementation-score (parent-plan
calibration: 19 chunks ≈ score 6 "large"): this Epic ≈ 15-17 chunks, 3
HIGH-risk blocks (B1/B2/B3) — score ≈ 5 "large", recording-drive is the
load-bearing risk concentration. Recommend implement-long-plan-v2 with
block-mode, mid-chunk-triage armed for B1/B2/B3 (architecture-conflict /
blocks-following-chunks markers likely on the recording-drive flip).
-->
