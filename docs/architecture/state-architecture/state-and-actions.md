---
date: 2026-05-14
author: Lukas + Claude Code
type: Architecture
status: Accepted
context: How DictateUiState is shaped, how Actions flow into it via single-dispatch, and what the reducer contract looks like.
related-plan: ../../plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md
related-adrs: ADR-0001
---

# State and Actions

This page describes the **`DictateUiState` data model** and the
**Action sealed hierarchy** that drives every mutation. The single
ADR governing this material is
[ADR-0001 — state-modular-orchestrator-pattern](../../decisions/0001-state-modular-orchestrator-pattern.md).

Read this page **before** [`modules.md`](modules.md), because the
module interface uses both `DictateUiState` and `Action` as type
parameters.

## 1. Vision and Motivation

### 1.1 Why this state model exists

Pre-refactor, the IME's UI state was scattered across
`RecordingStateController`, `RecordingUiController`,
`KeyboardUiController`, `KeyboardStateManager`, and the IME-Service
itself. Five concrete production bugs (parent plan §1.1) traced to
"multiple writers per logical state axis". The fix had to be
structural — there is no naming convention or code-review process
that consistently catches "five places mutate `resend_btn`".

`DictateUiState` is the **single source of truth** for the UI
state. The 13 sub-state axes have one owner module each.
Mutation happens through one entry: `orchestrator.dispatch(action: Action)`.

### 1.2 What problem this solves

| Pre-refactor pain | Post-refactor mechanism |
|---|---|
| Five mutators on `resend_btn` visibility | One `visibilityPredicate` in the slot, reading `state.resend` |
| `recordButton.text/isEnabled` set by two controllers | One `enabledResolver` + `textResolver` per slot |
| State drift across components | Single `StateFlow<DictateUiState>` emitted from the store |
| Hardware-coupled state reads in mutation paths | Reducers are pure; hardware lives in `runEffect` |
| Race conditions on multi-step layout changes | Single-dispatch + frozen-cascade-snapshot |

### 1.3 Discarded Alternatives

- **Free-form mutators on the LocalBinder.** Was the pre-F-8 design.
  Rejected because each binder method duplicated an Action shape,
  creating two parallel APIs that drifted. ADR-0001 §"Alternatives Considered" (3).
- **Monolithic central reducer.** Was the pre-F-11 design.
  Rejected because every new state axis would touch a 2000-line
  central `reduce()` function.
- **Sub-class-based modules** (no `sealed interface DictateModule`).
  Rejected because exhaustive compile-time module identification is
  more valuable than the small ergonomics gain.

## 2. Properties this Architecture Guarantees

1. **Immutability.** `DictateUiState` is a `data class`; sub-state
   types are sealed classes or data classes. Mutation goes through
   `copy(...)`.
2. **Single mutation entry.** The store's `update(reducer)` is the
   only mutator; only `DictateOrchestrator.dispatchInternal` calls
   it.
3. **Main-thread confinement.** `dispatch()` requires
   `Looper.myLooper() == Looper.getMainLooper()`. Async producers
   use `emitAction()`, which `scope.launch`s into the main
   dispatcher.
4. **Determinism.** `(state, action, ctx)` →
   `TransitionResult<S, E>?` is deterministic for every module.
   `ctx.now` is the only time source; hardware reads are forbidden
   in the reducer.
5. **Exhaustivity.** `Action` is a `sealed class`; every reducer's
   `when` is exhaustive. Adding a new variant produces a compile
   error in every reducer that needs to handle it.

## 3. `DictateUiState` shape

The state is a flat `data class` with 13 sub-state axes + 1
top-level boolean. The sub-state types are immutable `data class`es
(or sealed classes with `data class`/`data object` leaves).

```kotlin
data class DictateUiState(
    // ─── Hot-path FSMs (sealed classes, dedicated reducer modules) ───
    val recording: RecordingState,       // sealed: Idle / Preparing / Active / Paused
    val pipeline: PipelineUiState,       // sealed: Idle / Preparing / Running / ReprocessStaging
    val viewMode: ViewMode,              // enum: KEYBOARD / WIDGET / HOVER (Triangle-FSM, ADR-0005)

    // ─── Layout / UI-mode ───
    val layout: LayoutState,             // contentArea + singleRowMode + smallMode + animationsEnabled
    val overlay: OverlayState,           // 4 floats (positions) + 4 booleans (perm / pref / suppress / onboarding)

    // ─── Subsystems (public state snapshots) ───
    val audio: AudioState,               // AudioFocus + BluetoothSco + vibration
    val resend: ResendState,             // lastAudioExists + enabled + cooldown
    val livePrompt: LivePromptState,
    val language: LanguageState,

    // ─── Pref-mirror (synced by PipelinePrefMirror) ───
    val features: FeatureToggles,
    val theming: ThemingState,

    // ─── DB-subscriber-driven ───
    val pendingSessions: PersistentList<PendingSession>,

    // ─── Top-level Pipeline-Service-Death flag ───
    val lastResultNeedsManualPaste: Boolean = false,

    // ─── Phase 2 stub ───
    val interruption: InterruptionState? = null,
)
```

Source-of-truth: [Spec 1 §3](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md). The table below
maps each axis to its owner module and the canonical Spec-section:

| # | Sub-state | Owner module | Source |
|---|---|---|---|
| 1 | `recording` | `RecordingModule` (§15.1) | sealed `RecordingState`: Idle / Preparing / Active / Paused — carries `audioFile` + `useBluetooth` |
| 2 | `pipeline` | `PipelineModule` | sealed `PipelineUiState`: Idle / Preparing / Running / ReprocessStaging — carries `sessionId` (R.8) |
| 3 | `viewMode` | `ViewModeModule` | enum `ViewMode`: KEYBOARD / WIDGET / HOVER (ADR-0005) |
| 4 | `layout` | `LayoutModule` | `LayoutState` (contentArea + 3 booleans, Pref-mirror) |
| 5 | `overlay` | `OverlayModule` | `OverlayState` (4 floats + 4 booleans incl. suppressAutoOverlay + hasPermission) |
| 6 | `audio` | `AudioModule` | `AudioState` (Pref + system status + BluetoothSco) |
| 7 | `resend` | `ResendModule` | `ResendState` (3 booleans) |
| 8 | `livePrompt` | `LivePromptModule` | `LivePromptState` (2 booleans) |
| 9 | `language` | `LanguageModule` | `LanguageState` (effective + override) |
| 10 | `features` | `FeatureToggleModule` | `FeatureToggles` (5 booleans, Pref-mirror) |
| 11 | `theming` | `ThemingModule` | `ThemingState` (4 Pref-mirror values) |
| 12 | `pendingSessions` | `PendingSessionsModule` | PersistentList, DB-subscriber-driven |
| 13 | `interruption` | `InterruptionModule` (Phase 2) | `InterruptionState` — default null |
| top | `lastResultNeedsManualPaste` | `PipelineModule` | boolean — IME-Service-death recovery hint |

### 3.1 `PersistentList`-mutation idiom

`pendingSessions` uses `kotlinx.collections.immutable.PersistentList`
for structurally-shared immutability:

```kotlin
// ✓ structural-share preserved
pendingSessions = current.pendingSessions.add(newSession)
pendingSessions = current.pendingSessions.removeAll { it.sessionId == id }

// ✗ allocates fresh list — performance regression
pendingSessions = (current.pendingSessions + newSession).toPersistentList()
pendingSessions = current.pendingSessions.toMutableList()
    .apply { add(newSession) }
    .toPersistentList()
```

The round-trip via `toMutableList()` is forbidden pattern (e) — see
[`forbidden-patterns.md`](forbidden-patterns.md).

## 4. Action sealed hierarchy

`Action` is a top-level sealed class with one inner `sealed class`
per module (and `sealed object` leaves where useful). Plus a
top-level `Action.EffectFailure` failure channel.

```kotlin
sealed class Action {
    sealed class RecordingAction : Action() {
        data object StartRecording : RecordingAction()
        data object StopRecording : RecordingAction()
        data object PauseRecording : RecordingAction()
        data object ResumeRecording : RecordingAction()
        data object CancelRecording : RecordingAction()
        // ...
    }
    sealed class PipelineAction : Action() {
        data class Submit(val sessionId: String, val target: InsertionTarget) : PipelineAction()
        data object CancelPipeline : PipelineAction()
        // ...
    }
    sealed class OverlayAction : Action() {
        data class SetUserPrefersWidget(val value: Boolean) : OverlayAction()
        data object ResetSuppressBit : OverlayAction()
        data object CloseOverlay : OverlayAction()
        // ...
    }
    // … 10 more modules …

    data class EffectFailure(
        val originModuleId: ModuleId,
        val effect: String,                 // effect.toString() at emit time
        val reason: String,
    ) : Action()
}
```

**Key properties:**

- One inner sealed class per module → KClass-Lookup at the
  orchestrator can route at O(1) (Spec 1 §4.3).
- All variants are `data class` / `data object` — actions are pure
  data, no methods.
- `Action.EffectFailure` is the only top-level Action variant outside
  the module hierarchies. It carries `originModuleId` so the
  orchestrator can route the failure back to the module that
  produced it (ADR-0002 §"EffectFailure routing").
- Tests use the leaf types directly:
  `assertEquals(Action.RecordingAction.StartRecording, captured.first())`.

### 4.1 Five sources of Actions

The orchestrator receives Actions from five distinct sources
(see plan §4.0.1.2):

1. **UI Click.** `slot.actionResolver(state, services) -> Action?`
   in the click-listener. `null` is silently dropped (R.3 idiom).
   `?.let { onAction(it) }`.
2. **Android lifecycle hook.** E.g. `onFinishInputView` →
   `service.dispatch(Action.ViewModeAction.OnImeViewHidden)`.
3. **Cross-Module Cascade (Mode 2).** A module's
   `onCrossModuleStateChange(prev, next)` returns
   `List<Action>`; the orchestrator dispatches them recursively
   at `depth+1`. See [`cross-module-cascade.md`](cross-module-cascade.md).
4. **Effect Completion.** Inside `runEffect`, a coroutine calls
   `services.emitAction(action)` → main-thread re-post via
   `scope.launch { dispatch(action) }`.
5. **Effect Failure (automatic).** When `runEffect` throws, the
   orchestrator emits
   `Action.EffectFailure(originModuleId, effect.toString(), reason)`.
   Routed back to the origin module's `reduceFailure` hook.

## 5. The `dispatch` loop (5 steps)

```
dispatch(action)
  │
  ├─ require(Looper.myLooper() == Looper.getMainLooper())
  ├─ dispatchInternal(action, depth = 0)
  │
  │  Step 0: depth guard
  │    if (depth >= MAX_CASCADE_DEPTH) return Rejected("cascade-loop")
  │
  │  Step 1: routing
  │    if (action is EffectFailure) → lookup by originModuleId
  │    else                          → lookup by action::class
  │
  │  Step 2: reducer
  │    if (action is EffectFailure) → module.reduceFailure(...)
  │    else                          → module.reduce(...)
  │    null return → Rejected("reducer-null")
  │
  │  Step 3: state-update
  │    store.update { module.write(it, result.nextState) }
  │
  │  Step 4: effects (async)
  │    result.sideEffects.forEach {
  │       try    { module.runEffect(it, services) }
  │       catch  { dispatchInternal(EffectFailure(...), depth+1) }
  │    }
  │
  │  Step 5: cross-module cascade (Mode 2)
  │    val (prev, next) = frozen snapshots
  │    val cascades = modules.flatMap { it.onCrossModuleStateChange(prev, next) }
  │    cascades.forEach { dispatchInternal(it, depth+1) }
  │
  └─ return Applied
```

See Spec 1 §4.3 for the full code.

## 6. `DispatchOutcome` trichotomy

`dispatch()` returns a `DispatchOutcome`:

```kotlin
sealed interface DispatchOutcome {
    object Applied : DispatchOutcome
    data class Rejected(val action: Action, val reason: String) : DispatchOutcome
    data class Unrouted(val action: Action) : DispatchOutcome
}
```

| Outcome | Meaning | Common reasons |
|---|---|---|
| `Applied` | Reducer ran, state updated, effects launched. | normal path |
| `Rejected` | The reducer returned `null` ("action not relevant in this state"). | wrong-state click; reason carries `"reducer-null"` or `"cascade-loop"` |
| `Unrouted` | No module matched the action class. | `EffectFailure` whose `originModuleId` does not exist (very rare); ProGuard accidentally stripped the sealed hierarchy (Spec 1 §4.3 ProGuard block) |

`Rejected` is **not** a bug — it's a normal outcome for actions
that the current state doesn't accept (e.g. clicking Send while
recording is active).

`Unrouted` is **always** a bug — either the registry is missing the
module or ProGuard ate the action hierarchy. The
`OrchestratorReleaseSmokeTest.kt` (Block 1b) verifies
`Applied != Unrouted` for a release build.

## 7. `ReducerContext`

```kotlin
data class ReducerContext(
    val global: DictateUiState,            // Read-only view of the full state
    val now: Long = System.currentTimeMillis(),
)
```

Two fields, both `val`:

- **`global`** — the complete `DictateUiState` snapshot at
  reduce-time. Reducer reads `ctx.global.audio.useBluetoothMic` /
  `ctx.global.overlay.hasPermission` etc. Cross-axis reads are
  always read-only.
- **`now`** — monotonic timestamp, the **only** legal time source
  in a reducer. Direct `System.currentTimeMillis()` calls inside
  the reducer are forbidden — they break test determinism.

Hardware reads are forbidden in the reducer context (forbidden
pattern (b)). If a reducer needs a hardware fact, it lives in the
state (e.g. `state.audio.audioFocusGranted` is updated via an
`AudioModule.runEffect`-emitted action).

## 8. Information Gaps

(no gaps known at this time — the model is fully specified in Spec 1 §3)

## 9. Change History

### 2026-05-14 — Initial draft

- **Trigger:** Block 0 of the keyboard-layout-refactor plan creates
  this directory as a pre-code architecture anchor.
- **Reasoning:** ADR-0001 carries the binding decision; this page
  unpacks the data model and dispatch loop in tutorial form. The
  content is sourced from Spec 1 §3 + §4.3 + §15 and is intended
  to stay in sync with both.

## 10. References

- [ADR-0001 — state-modular-orchestrator-pattern](../../decisions/0001-state-modular-orchestrator-pattern.md)
- [Spec 1 §3 — `DictateUiState`](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [Spec 1 §4.3 — DictateOrchestrator.dispatchInternal](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [Spec 1 §15 — Modul-Inventar](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [`modules.md`](modules.md)
- [`forbidden-patterns.md`](forbidden-patterns.md)
- [`cross-module-cascade.md`](cross-module-cascade.md)
