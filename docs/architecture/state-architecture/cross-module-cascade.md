---
date: 2026-05-14
author: Lukas + Claude Code
type: Architecture
status: Accepted
context: The two allowed cross-module-cascade modes, the forbidden third, self-cascade, frozen-snapshot semantics, depth guard, and coupling-matrix notation.
related-plan: ../../plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md
related-adrs: ADR-0002
---

# Cross-Module Cascade

This page describes the **two allowed modes** of cross-module
effects, the **forbidden third mode**, the **self-cascade rule**, the
**frozen-snapshot semantics**, the **depth guard**, and the
**coupling-matrix notation**.

Owner ADR:
[ADR-0002 — state-cross-module-cascade](../../decisions/0002-state-cross-module-cascade.md).
Prerequisite reading: [`modules.md`](modules.md).

## 1. Vision and Motivation

### 1.1 The three modes

With one-owner-per-axis (ADR-0001), how do modules coordinate?
Three mechanisms came up in design; two are allowed:

| Mode | What | Status |
|---|---|---|
| **1 — Own SideEffect** | Reducer emits a `SideEffect` of the **owning** module's interface. The effect touches hardware in another subsystem. State mutation stays on the module's own axis. | ✅ allowed |
| **2 — Action-Cascade** | Module B observes a `(prev, next)` transition via `onCrossModuleStateChange(prev, next): List<Action>` and returns actions. The orchestrator dispatches them recursively at `depth+1`. | ✅ allowed |
| **3 — Atomic Cross-Axis-Update** | A reducer mutates its own axis **plus** another module's axis in one transition. | ❌ forbidden (Phase 1) |

Mode 1 + Mode 2 cover all Phase-1 identified flows. Mode 3 is a
Phase-2 backlog item (parent plan §7.1) — it stays forbidden until
a use case arrives that's unsolvable by helper consolidation.

### 1.2 What this solves

| Problem | Mechanism |
|---|---|
| Modules talking to each other directly (`recordingModule.overlayModule.foo()`) | Inter-module communication only via global state + Action pipe (forbidden pattern (n)) |
| Race between observer-cascades in the same pass | Frozen snapshot — every observer sees the same `(prev, next)` |
| Runaway cascade loops | `MAX_CASCADE_DEPTH = 8` |
| Self-cascade blocked (HOVER-reopen bug) | Self-filter removed; depth-cap is sole guard (KG-RSB-2 fix) |
| `EffectFailure` routed to the wrong module | Origin-routed via `originModuleId`, not by KClass |

## 2. Properties this Architecture Guarantees

1. **Two modes only.** Mode 1 (own SideEffect) and Mode 2 (Action-Cascade).
   Mode 3 (Atomic Cross-Axis-Update) raises a code-review failure
   per ADR-0002 §"code-review requirement".
2. **Frozen snapshot.** Every observer sees a consistent `(prev, next)`
   tuple in a given dispatch pass.
3. **Self-cascade allowed.** A module observing its own axis in
   `onCrossModuleStateChange` is valid. The pre-2026-05-11 self-filter
   was removed (KG-RSB-2 production bug).
4. **Depth-capped recursion.** `MAX_CASCADE_DEPTH = 8`. DEBUG raises
   `error()`; release logs an error and returns
   `DispatchOutcome.Rejected("cascade-loop")`.
5. **Deterministic order.** Cascade actions follow the order of
   `DictateModuleRegistry.all`. Reordering the registry is a
   plan-relevant refactor.

## 3. Mode 1 — own SideEffect

The owning module's reducer emits an effect that touches hardware
in **some** subsystem. The state mutation stays on the owning
module's axis. Example:

```kotlin
// RecordingModule.reduce — Idle + StartRecording:
TransitionResult(
    nextState = RecordingState.Preparing(useBluetooth = true, audioFile = …),
    sideEffects = listOf(Effect.AllocateMediaRecorder(target, useBluetooth, audioFile)),
)
```

`AllocateMediaRecorder` triggers `services.recordingHardware.allocate(...)`
— a call into the **`RecordingHardware` subsystem**, which is
not a state axis. The state stays clean: only `recording` was mutated.

Mode 1 is appropriate when:

- The effect operates on hardware that's owned by the same module
  (RecordingModule → RecordingHardware).
- The state mutation is on the module's own axis only.
- No other module needs to know about the change synchronously.

## 4. Mode 2 — Action-Cascade

Module B observes a state transition in `onCrossModuleStateChange`
and emits actions that cascade through the orchestrator:

```kotlin
// LayoutModule.onCrossModuleStateChange — observes WIDGET → KEYBOARD:
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.viewMode == ViewMode.WIDGET && next.viewMode == ViewMode.KEYBOARD)
        listOf(Action.LayoutAction.SetSmallMode(true))      // activate SmallMode on close
    else emptyList()
```

The cascade flow:

```
ViewModeModule.reduce sets viewMode = KEYBOARD
  ↓
store.update { …copy(viewMode = KEYBOARD) }
  ↓
prev = (viewMode=WIDGET, …)
next = (viewMode=KEYBOARD, …)
  ↓
for each module in DictateModuleRegistry.all:
    cascadeActions += module.onCrossModuleStateChange(prev, next)
  ↓
LayoutModule → [SetSmallMode(true)]
OverlayModule → [SetUserPrefersWidget(false)]
  ↓
dispatchInternal(SetSmallMode(true), depth = 1)
  → LayoutModule.reduce → state.layout.smallMode = true
dispatchInternal(SetUserPrefersWidget(false), depth = 1)
  → OverlayModule.reduce → state.overlay.userPrefersWidget = false
```

Each cascade-dispatched action goes through the **full** dispatch
pipeline (routing → reducer → state-update → effects → cascade).
Cross-module-cascades nest naturally.

### 4.1 Cascade-order guarantee

Cascade actions are dispatched in the order of
`DictateModuleRegistry.all`. Each recursive `dispatchInternal`
call snapshots `prev`/`next` again, so later cascades see the
state including earlier cascade mutations from the same pass.

> [!IMPORTANT]
> A module **must not** rely on another module's cascade running
> before it. The order is deterministic but documented as a
> convention; if you need real reordering semantics, you have a
> Mode-3 use case (Phase-2 backlog). Reordering
> `DictateModuleRegistry.all` is a plan-relevant refactor verified
> by `DictateOrchestratorCascadeOrderTest.kt`.

## 5. Mode 3 — Atomic Cross-Axis-Update (forbidden)

```kotlin
// ❌ FORBIDDEN — ViewModeModule writes to layout + overlay in one transition:
override fun reduce(state: ViewMode, action: …, ctx: …) =
    when (action) {
        ToggleViewModeWidget -> TransitionResult(
            nextState = state.copy(
                viewMode = ViewMode.KEYBOARD,
                layout = state.layout.copy(smallMode = true),         // ❌
                overlay = state.overlay.copy(userPrefersWidget = false), // ❌
            ),
            …,
        )
    }
```

This is the "atomic cross-axis update" pattern that breaks SRP
(ViewModeModule writing into `layout` + `overlay` axes it doesn't
own). The Phase-B S-9 finding identified this exact pattern in
Spec 3 §7.3 and rewrote it onto Mode-2 form.

### 5.1 The correct Mode-2 form

```kotlin
// ✓ ViewModeModule.reduce mutates ONLY `viewMode`:
TransitionResult(nextState = ViewMode.KEYBOARD, sideEffects = emptyList())

// ✓ LayoutModule.onCrossModuleStateChange handles its own axis:
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.viewMode == ViewMode.WIDGET && next.viewMode == ViewMode.KEYBOARD)
        listOf(Action.LayoutAction.SetSmallMode(true))
    else emptyList()

// ✓ OverlayModule.onCrossModuleStateChange handles its own axis:
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.viewMode == ViewMode.WIDGET && next.viewMode == ViewMode.KEYBOARD)
        listOf(Action.OverlayAction.SetUserPrefersWidget(false))
    else emptyList()
```

Three modules, each owning their axis. No cross-axis writes. The
cascade depth is 2 (the initial `ToggleViewModeWidget` dispatch +
the two cascaded actions).

### 5.2 Anti-pattern table

From Spec 1 §15.5:

| Pattern | Mode | Verdict |
|---|---|---|
| Module mutates **only** its own axis + emits hardware effects | Mode 1 | ✅ SRP-clean |
| Module mutates its own axis; other modules cascade follow-ups | Mode 2 | ✅ SRP-clean |
| Module mutates **its own axis + another axis** in one reducer transition | Mode 3 | ❌ Phase-2 backlog |
| Module reads its own axis (`prev.x` vs `next.x`) as cascade trigger to a foreign axis | Mode 2 (Self-Read) | ✅ Allowed (KG-RSB-3 convention) |

The **Mode-2 Self-Read** pattern is the trickiest — see §7 below.

## 6. Frozen snapshot

Before Step 5 (cross-module observation), the orchestrator
snapshots `prevGlobal` and `nextGlobal` from the store:

```kotlin
// Step 5 in dispatchInternal (Spec 1 §4.3):
val nextGlobal = store.snapshot
val cascadeActions = modules
    .flatMap { it.onCrossModuleStateChange(prevGlobal, nextGlobal) }
```

Every module's observer is called with the **same** `prev`/`next`
tuple. Even if observer A's cascade later mutates state, observer
B (called later in the same loop) still sees the original
`(prev, next)`. The recursive `dispatchInternal(cascadeAction,
depth+1)` calls take a **fresh** snapshot for their own
observation pass.

Why this matters: without the frozen snapshot, observer order
becomes load-bearing — observer A's cascade would mutate state,
observer B would see partial state, observer C would see the
post-A-cascade state. That's the road to non-deterministic
cascades and impossible-to-debug bugs.

## 7. Self-cascade — allowed (KG-RSB-2 fix)

A module is allowed to observe its **own** axis in
`onCrossModuleStateChange` and cascade to other modules:

```kotlin
// RecordingModule.onCrossModuleStateChange — observes own Idle → Preparing:
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.recording is RecordingState.Idle && next.recording is RecordingState.Preparing)
        listOf(Action.OverlayAction.ResetSuppressBit)
    else emptyList()
```

This is essential: without it, HOVER would not reopen after the
first user-close in a session (Spec 1 §15.2 KG-RSB-2 production
bug).

> [!CAUTION]
> The earlier `modules.filter { it.id != module.id }` (self-filter) in
> `dispatchInternal` Step 5 is **deliberately removed**. Re-adding it
> as a "looks like an infinite-loop guard" reactivates the
> KG-RSB-2 bug. The depth-cap (`MAX_CASCADE_DEPTH = 8`) is
> sufficient. See the ⚠-banner in Spec 1 §4.3 and the regression
> test `DictateOrchestratorTest.recordingModule_idleToPreparing_emits…`.

### 7.1 Self-read in the coupling matrix (KG-RSB-3 convention)

The Cross-Module-Coupling-Matrix (Spec 1 §15.1.x) uses notation
`R(state.x.y)` for cross-module reads and `C(Action.Y.Z)` for
cross-module cascades. **Self-reads** — a module reading its own
axis as cascade trigger — are NOT entered as `R(state.x)` on the
matrix diagonal. The diagonal stays `—`; only the
`C(Action.Y.Z)` consequence appears in the cross-module cell.

Example: `Recording × Overlay = C(OverlayAction.ResetSuppressBit)`
— the `prev.recording is Idle && next.recording is Preparing`
predicate inside `RecordingModule.onCrossModuleStateChange` is
considered an implementation detail of the owner module, not a
cross-module coupling. The matrix shows only the consequence.

The alternative (verbose `[self]R(state.x)` markers) was rejected
in KG-RSB-3 resolution — it adds notational noise without
information gain.

## 8. `MAX_CASCADE_DEPTH = 8`

The single loop guard:

```kotlin
private fun dispatchInternal(action: Action, depth: Int): DispatchOutcome {
    if (depth >= MAX_CASCADE_DEPTH) {
        val msg = "Cascade loop detected at depth=$depth, action=$action"
        if (BuildConfig.DEBUG) error(msg)
        Log.e(TAG, msg)
        return DispatchOutcome.Rejected(action, "cascade-loop")
    }
    // …
}
```

DEBUG builds raise `error()` (= `IllegalStateException`) so
developers see runaway cascades immediately. Release builds log
the error and return `Rejected("cascade-loop")` — the IME never
crashes.

Real cascade depths in the designed flows are **1–3**:

- Depth 1 — most user-clicks (action → cascade follow-ups)
- Depth 2 — T1/T2 ViewMode transitions (initial ToggleViewModeWidget
  → ViewModeModule.reduce → cascades → LayoutModule.reduce +
  OverlayModule.reduce)
- Depth 3 — T7 Pipeline-Done cascade
  (PipelineModule.reduce → cascade → ViewModeModule.reduce →
  cascade → LayoutModule.reduce)

Cap = 8 is conservative — anything deeper is a Mode-3 use case
in disguise.

## 9. Coupling matrix — the auditable contract

Spec 1 §15.1.x carries the full **Cross-Module-Coupling Matrix**:
column = observing module, row = owning module. Each cell
documents the read coupling (`R(state.x.y)`) and the cascade
consequence (`C(Action.Y.Z)`).

Example excerpt:

| Owner ↓ / Observer → | Recording | Pipeline | Audio | ViewMode | Overlay |
|---|---|---|---|---|---|
| **Recording** | — | R(state.recording) C(PipelineAction.Submit) | R(state.recording) | R(state.recording) C(ViewModeAction.OnRecordingActive) | C(OverlayAction.ResetSuppressBit) |
| **Pipeline** | R(state.pipeline) C(RecordingAction.StopRecording) | — | | R(state.pipeline) C(ViewModeAction.OnPipelineDone) | R(state.pipeline) C(OverlayAction.OnPipelineDone) |
| **Audio** | R(state.audio.audioFocusGranted) C(RecordingAction.PauseRecording) | | — | | |

Reading the matrix:

- **Recording × Overlay = `C(OverlayAction.ResetSuppressBit)`** —
  the Recording module **observes its own** Idle→Preparing
  transition and cascades a ResetSuppressBit action to Overlay.
  (Self-read, no diagonal entry.)
- **Pipeline × ViewMode = `R(state.pipeline) C(ViewModeAction.OnPipelineDone)`** —
  Pipeline observes its own pipeline state, cascades
  OnPipelineDone to ViewMode (the T7 transition that fixes the
  Geist-Widget bug).
- **Audio × Recording = `R(state.audio.audioFocusGranted) C(RecordingAction.PauseRecording)`** —
  Audio reads its own focus-granted axis, cascades PauseRecording
  to Recording when focus is lost.

Every new cascade requires a matrix update. A
`onCrossModuleStateChange` hook without a matrix-row entry is a
code-review violation.

## 10. Example cascade sequence — ResetSuppressBit (KG-RSB-2 fix)

```
User clicks RECORD button (in KEYBOARD)
  ↓
dispatch(Action.RecordingAction.StartRecording, depth=0)
  ↓
RecordingModule.reduce(Idle, StartRecording, ctx)
  → TransitionResult(Preparing(...), [AllocateMediaRecorder(...)])
  ↓
store.update { recording = Preparing(...) }
  ↓
runEffect(AllocateMediaRecorder) — async, in scope.launch
  ↓
Step 5: cross-module observers see (prev.recording=Idle, next.recording=Preparing)
  ↓
RecordingModule.onCrossModuleStateChange returns [OverlayAction.ResetSuppressBit]
  (self-cascade, KG-RSB-2 fix)
ViewModeModule.onCrossModuleStateChange returns [OnRecordingActive]
  ↓
dispatch(Action.OverlayAction.ResetSuppressBit, depth=1)
  → OverlayModule.reduce → state.overlay.suppressAutoOverlayUntilNextSession = false
dispatch(Action.ViewModeAction.OnRecordingActive, depth=1)
  → ViewModeModule.reduce → re-evaluate computeViewMode
  → (still KEYBOARD because imeViewVisible=true)
  → returns null (no state change)
```

Effects: the suppress-bit is reset, allowing HOVER to auto-open
on the next IME-View-Hidden event. Without the self-cascade,
the suppress-bit stays set across sessions (KG-RSB-2 bug).

## 11. `EffectFailure` is also a cascade

When a `runEffect` throws, the orchestrator re-dispatches as
`Action.EffectFailure(originModuleId, effect, reason)` at
`depth+1`. The routing uses the secondary `moduleById` map
(ADR-0002 §"EffectFailure routing"). See
[`effects-and-failures.md`](effects-and-failures.md) §6 for the
full path.

## 12. Information Gaps

(no gaps known at this time — Spec 1 §4.3 + §15.5 are the canonical sources)

## 13. Change History

### 2026-05-14 — Initial draft

- **Trigger:** Block 0 architecture anchor.
- **Reasoning:** Captures the cascade rules from Spec 1 §15.5 +
  ADR-0002 + KG-RSB-2 + KG-RSB-3 + Phase-B S-9 in tutorial form.

## 14. References

- [ADR-0002 — state-cross-module-cascade](../../decisions/0002-state-cross-module-cascade.md)
- [Spec 1 §4.3 — DictateOrchestrator.dispatchInternal](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [Spec 1 §15.1.x — Coupling-Matrix](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [Spec 1 §15.5 — Cross-Module-Effect-Modi](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [`modules.md`](modules.md)
- [`effects-and-failures.md`](effects-and-failures.md)
- [`forbidden-patterns.md`](forbidden-patterns.md) §(f), §(g), §(h)
