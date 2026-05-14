---
date: 2026-05-14
author: Lukas + Claude Code
type: Architecture
status: Skeleton
context: The 14 hard-forbidden patterns (a–n) with example, rationale, and correct alternative — derived from plan §4.0.1.5 and the failure-mode sections of ADRs 0001/0002/0004/0005.
related-plan: ../../plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md
related-adrs: ADR-0001, ADR-0002, ADR-0004, ADR-0005
---

# Forbidden Patterns

This page catalogues the **14 hard-forbidden patterns** from
[parent plan §4.0.1.5](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md).
Each entry shows the forbidden code shape, the bug class it
reactivates, and the correct alternative.

The 14 patterns are routed into the relevant ADRs per plan §4.0.1.5:

- **ADR-0001 §"Failure Modes"** owns (a, b, c, e, h, i, m, n).
- **ADR-0002 §"Failure Modes"** owns (f, g).
- **ADR-0004 §"Failure Modes"** owns (d, j, k, l).

This page is the consolidated catalogue — the ADRs each carry their
own subset.

## 1. Vision and Motivation

### 1.1 Why a forbidden-patterns catalogue

The 14 patterns are responsible for **all of plan §1.1's bugs**.
Each one was either:

- An anti-pattern in the pre-refactor codebase that produced a
  named production bug, or
- A subtle developer-tempting shape that would reintroduce a
  named bug if added.

The catalogue exists because the compiler **does not** catch most
of them — they pass type-checking, they don't fail lint, but they
silently re-establish the multi-writer / non-deterministic /
memory-leaking shapes the refactor exists to eliminate.

### 1.2 How to use this page

| Reader role | What to do |
|---|---|
| Implementing a new feature | Read this page before opening the editor. The patterns are easy to slip into. |
| Reviewing a PR | Cross-check the diff against this catalogue. Any match = code-review block. |
| Adding a new module/button | Skim the relevant section ((a)–(n)) before touching the catalogue / module. |

## 2. Properties this Catalogue Guarantees

1. **Closed list.** Plan §4.0.1.5 defines exactly 14 patterns
   ((a)–(n)). New patterns require an ADR amendment.
2. **Every entry names a bug.** Either a real production bug from
   plan §1.1 or a structurally inevitable one.
3. **Every entry has a correct alternative.** No "don't do X"
   without "do Y instead".

## 3. The 14 forbidden patterns

### (a) Direct `_state.value = …` outside the store

**Forbidden:**

```kotlin
// In some controller or service:
_state.value = state.copy(recording = newRecording)
```

**Why it breaks:** breaks single-dispatch ownership (F-8). The
store's `_state` is the only mutator; the `update(reducer)`
function is the only public entry. Bypassing it means mutations
happen outside the dispatch loop — no reducer runs, no cascade
fires, no observers are notified consistently.

**Correct alternative:**

```kotlin
orchestrator.dispatch(Action.RecordingAction.StartRecording(...))
```

The reducer + dispatch loop handle the mutation atomically with
the cascade.

Owner ADR: ADR-0001 §"Failure Modes" (entry 1).

### (b) Hardware / IO read in the reducer

**Forbidden:**

```kotlin
// In RecordingModule.reduce:
override fun reduce(state: RecordingState, action: Action.RecordingAction, ctx: ReducerContext) =
    when (state) {
        is RecordingState.Preparing -> {
            if (audioFile.exists()) {              // ❌ FS read
                MediaRecorder.prepare()            // ❌ hardware
                TransitionResult(RecordingState.Active(...), emptyList())
            } else null
        }
    }
```

**Why it breaks:** breaks the Pure-Reducer Invariant (F1+F2). Tests
become non-deterministic (file presence depends on the test
environment). Race conditions reappear (the same action emitted
twice produces different results).

**Correct alternative:** put the hardware in `runEffect`:

```kotlin
override fun reduce(...) = …
    TransitionResult(
        nextState = RecordingState.Preparing(useBluetooth, audioFile),
        sideEffects = listOf(Effect.AllocateMediaRecorder(target, useBluetooth, audioFile)),
    )

override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
    is Effect.AllocateMediaRecorder ->
        services.recordingHardware.allocate(effect.target, effect.useBluetooth, effect.audioFile)
}
```

State holds the data the reducer needs (`audioFile: File`); hardware
operations are deferred to `runEffect`.

Owner ADR: ADR-0001 §"Failure Modes" (entry 2).

### (c) `else`-branch in `reduce`-`when` over sealed Actions

**Forbidden:**

```kotlin
override fun reduce(state: RecordingState, action: Action.RecordingAction, ctx: ReducerContext) =
    when (action) {
        is Action.RecordingAction.StartRecording -> …
        is Action.RecordingAction.StopRecording -> …
        else -> null   // ❌ swallows new Action variants silently
    }
```

**Why it breaks:** the compiler stops warning when a new
`RecordingAction` subtype is added. The new action will silently
hit `else` → `null` → `DispatchOutcome.Rejected("reducer-null")`.
The feature appears broken at runtime with no compile-time signal.

**Correct alternative:** expression-form `when` without `else`:

```kotlin
override fun reduce(state: RecordingState, action: Action.RecordingAction, ctx: ReducerContext) =
    when (action) {
        is Action.RecordingAction.StartRecording -> …
        is Action.RecordingAction.StopRecording -> …
        is Action.RecordingAction.PauseRecording -> …
        is Action.RecordingAction.ResumeRecording -> …
        is Action.RecordingAction.CancelRecording -> …
        // … exhaustive — compile error on new variant
    }
```

Owner ADR: ADR-0001 §"Failure Modes" (entry 3).

### (d) Re-parenting on layout switch (ConstraintSet rewriting)

**Forbidden:**

```kotlin
// In KeyboardLayoutModeController.applySingleRow:
trash_btn.parent.removeView(trash_btn)
input_row.addView(trash_btn)         // ❌ re-parent
pause_btn.parent.removeView(pause_btn)
input_row.addView(pause_btn)
// … 5+ more re-parents …
// then on revert, the originalParents-Map tries to undo them.
```

**Why it breaks:** reactivates plan §1.1 bugs #1 + #2 (asymmetric
re-parenting). The `originalParents`-Map work-around was the
band-aid; it broke when buttons were added/removed across modes.
Plan §1.1 bug #2 (Revert) was the symptom.

**Correct alternative:** declarative `MotionScene` transition:

```kotlin
// LayoutCatalog.KEYBOARD_SINGLE_ROW uses sceneStateId = R.id.single_row_state.
// Backend calls motionLayout.transitionToState(sceneId).
// Buttons stay in the same parent; their constraints change.
```

MotionLayout is the binding choice — see ADR-0004 §"Required
mechanics" item 6.

Owner ADR: ADR-0004 §"Failure Modes" (entry 1).

### (e) `toMutableList()`-round-trip on `PersistentList`

**Forbidden:**

```kotlin
pendingSessions = current.pendingSessions
    .toMutableList()
    .apply { add(newSession) }
    .toPersistentList()                  // ❌ destroys structural sharing
```

**Why it breaks:** `PersistentList` is structurally shared. The
round-trip allocates a fresh `ArrayList`, fills it, then allocates
a fresh `PersistentList` — losing all the prior structural sharing.
At a session-count of 100+, the cost becomes visible (the entire
list is copied per mutation instead of one path).

**Correct alternative:** use the native PersistentList API:

```kotlin
pendingSessions = current.pendingSessions.add(newSession)
pendingSessions = current.pendingSessions.removeAll { it.sessionId == id }
pendingSessions = current.pendingSessions.set(index, updatedSession)
```

Owner ADR: ADR-0001 §"Failure Modes" (entry 4).

### (f) Self-filter (`it.id != module.id`) in `dispatchInternal` Step 5

**Forbidden:**

```kotlin
// In DictateOrchestrator.dispatchInternal Step 5:
val cascadeActions = modules
    .filter { it.id != module.id }                      // ❌ KG-RSB-2 reactivation
    .flatMap { it.onCrossModuleStateChange(prevGlobal, nextGlobal) }
```

**Why it breaks:** reactivates KG-RSB-2 production bug. The
`RecordingModule.Idle → Preparing → OverlayAction.ResetSuppressBit`
self-cascade is essential for HOVER auto-reopen. Without it, the
suppress-bit stays set across sessions; HOVER does not reopen
after the first user-close.

**Correct alternative:** no filter. `MAX_CASCADE_DEPTH = 8` is the
sole loop guard:

```kotlin
val cascadeActions = modules
    .flatMap { it.onCrossModuleStateChange(prevGlobal, nextGlobal) }
```

The ⚠-banner comment in Spec 1 §4.3 (`dispatchInternal` Step 5)
guards against re-introduction. The regression test
`DictateOrchestratorTest.recordingModule_idleToPreparing_emits…`
asserts the cascade fires.

Owner ADR: ADR-0002 §"Failure Modes" (entry 1).

### (g) Cross-axis mutation in the reducer (Mode 3)

**Forbidden:**

```kotlin
// In ViewModeModule.reduce:
override fun reduce(state: ViewMode, action: …, ctx: …) =
    when (action) {
        ToggleViewModeWidget -> TransitionResult(
            nextState = state.copy(
                viewMode = ViewMode.KEYBOARD,
                layout = state.layout.copy(smallMode = true),         // ❌ cross-axis
                overlay = state.overlay.copy(userPrefersWidget = false), // ❌ cross-axis
            ),
            sideEffects = emptyList(),
        )
    }
```

Wait — `state` in `ViewModeModule.reduce` is `ViewMode` (the enum
sub-state), not `DictateUiState`. The code above is a compile error
in this exact form. The forbidden form is the conceptual one:

```kotlin
// Conceptual form — same bug class via store.update bypass:
store.update { it.copy(
    viewMode = ViewMode.KEYBOARD,
    layout = it.layout.copy(smallMode = true),
    overlay = it.overlay.copy(userPrefersWidget = false),
) }                                                                  // ❌
```

**Why it breaks:** atomic cross-axis mutation. The `viewMode`
owner (ViewModeModule) writes into `layout` and `overlay` axes
that other modules own. SRP violation; Mode 3 is Phase-2 backlog
(parent plan §7.1).

**Correct alternative:** Mode 2 cascade — each module owns its
axis:

```kotlin
// ViewModeModule.reduce mutates ONLY viewMode:
TransitionResult(nextState = ViewMode.KEYBOARD, sideEffects = emptyList())

// LayoutModule.onCrossModuleStateChange handles its own axis:
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.viewMode == ViewMode.WIDGET && next.viewMode == ViewMode.KEYBOARD)
        listOf(Action.LayoutAction.SetSmallMode(true))
    else emptyList()

// OverlayModule.onCrossModuleStateChange handles its own axis:
override fun onCrossModuleStateChange(...) = …
    listOf(Action.OverlayAction.SetUserPrefersWidget(false))
```

Owner ADR: ADR-0002 §"Failure Modes" (entry 2).

### (h) Synchronous re-dispatch from an EffectHandler

**Forbidden:**

```kotlin
override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
    Effect.StartTimer -> {
        // … work …
        orchestrator.dispatch(Action.RecordingAction.TimerTick(elapsedMs))   // ❌
    }
}
```

**Why it breaks:** breaks the frozen-cascade-snapshot. The
synchronous re-dispatch happens during the current dispatch pass;
the cascade observers iterating over the post-state see a new
mutation that didn't exist when they were called.

**Correct alternative:** async re-entry via `services.emitAction`:

```kotlin
override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
    Effect.StartTimer -> services.scope.launch {
        // … work …
        services.emitAction(Action.RecordingAction.TimerTick(elapsedMs))     // ✓
    }
}
```

`services.emitAction` is a `scope.launch { dispatch(action) }` —
async, main-thread re-post.

Owner ADR: ADR-0001 §"Failure Modes" (entry 5).

### (i) LocalBinder forwarder methods parallel to the Action hierarchy

**Forbidden:**

```kotlin
class LocalBinder(private val orchestrator: DictateOrchestrator) : Binder() {
    val state: StateFlow<DictateUiState> = orchestrator.state
    fun dispatch(action: Action) = orchestrator.dispatch(action)

    // ❌ parallel to Action.RecordingAction:
    fun startRecording() = orchestrator.dispatch(Action.RecordingAction.StartRecording(...))
    fun stopRecording() = orchestrator.dispatch(Action.RecordingAction.StopRecording)
    fun pauseRecording() = orchestrator.dispatch(Action.RecordingAction.PauseRecording)
    // … 22 more forwarder methods …
}
```

**Why it breaks:** F-8 violation. Two parallel APIs (Action types
and Binder methods) drift over time. Every new Action variant has
to be added to both. Eventually one falls behind.

**Correct alternative:** single-dispatch only:

```kotlin
class LocalBinder(private val orchestrator: DictateOrchestrator) : Binder() {
    val state: StateFlow<DictateUiState> = orchestrator.state
    fun dispatch(action: Action): DispatchOutcome = orchestrator.dispatch(action)
    // … and lifecycle hooks (bind, unbind) only.
}
```

Owner ADR: ADR-0001 §"Failure Modes" (entry 6).

### (j) `pred*Visible` predicate contains cooldown logic

**Forbidden:**

```kotlin
ButtonSlot(
    logicalId = LogicalButtonId.RESEND,
    visibilityPredicate = { state ->
        state.resend.lastAudioExists && !state.resend.resendCooldown   // ❌ cooldown in visibility
    },
    // …
)
```

**Why it breaks:** reactivates plan §1.1 bug #3b. Visibility
flickers as the cooldown timer runs (visible → gone → visible),
which produces a visual jump even though the button is still
present.

**Correct alternative:** visibility for **existence**, enabled for
**timing**:

```kotlin
ButtonSlot(
    logicalId = LogicalButtonId.RESEND,
    visibilityPredicate = { state -> state.resend.lastAudioExists },   // ← stable
    enabledResolver = { state ->                                       // ← timing-aware
        state.resend.lastAudioExists && state.resend.resendEnabled && !state.resend.resendCooldown
    },
    // …
)
```

Cooldown disables the button (greys out, no click). Visibility stays
constant. The user sees the button consistently — only its
interactability changes during the cooldown.

Owner ADR: ADR-0004 §"Failure Modes" (entry 3).

### (k) State-driven button without `motion:visibilityMode="ignore"` in MotionScene XML

**Forbidden:**

```xml
<!-- res/xml/motion_scene_keyboard.xml -->
<Constraint android:id="@+id/resend_btn"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    motion:layout_constraintTop_toTopOf="@+id/record_pulse_layout"
    motion:layout_constraintEnd_toStartOf="@+id/backspace_btn" />
    <!-- ❌ no PropertySet motion:visibilityMode="ignore" -->
```

**Why it breaks:** MotionScene's default behavior is to animate
visibility across transitions. When `transitionToState(...)`
runs, MotionLayout interpolates `visibility` from the source
ConstraintSet's value to the target — overlaying whatever the
per-slot `visibilityPredicate` has set.

Visible symptom: a button appears or disappears with a flicker
during transitions, regardless of what the predicate says.

**Correct alternative:**

```xml
<Constraint android:id="@+id/resend_btn"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    motion:layout_constraintTop_toTopOf="@+id/record_pulse_layout"
    motion:layout_constraintEnd_toStartOf="@+id/backspace_btn">
    <PropertySet motion:visibilityMode="ignore" />                  <!-- ✓ -->
</Constraint>
```

`visibilityMode="ignore"` tells MotionLayout to not touch
visibility during transitions. The render code owns it.

Owner ADR: ADR-0004 §"Failure Modes" (entry 4).

### (l) Click-Listener wired per render-tick

**Forbidden:**

```kotlin
override fun render(state: DictateUiState, mode: LayoutMode) {
    mode.rows.flatMap { it.slots }.forEach { slot ->
        val view = buttonViews[slot.logicalId]!!
        view.setOnClickListener {                                       // ❌ per render-tick
            slot.actionResolver(state, services)?.let { onAction(it) }
        }
    }
}
```

**Why it breaks:** lambda leak (Spec 2 §11.6 measures the cost).
Each `setOnClickListener` allocates a fresh lambda that captures
the `state` arg and the slot. At 60 Hz during recording, that's
60 × 9 buttons = 540 lambda allocations per second.

**Correct alternative:** `wireStaticHandlers()` once in `attach()`,
lambdas read `stateRef`/`modeRef` backend fields:

```kotlin
override fun attach(onAction: (Action) -> Unit) {
    this.onAction = onAction
    wireStaticHandlers()                                                // ✓ once
}

private fun wireStaticHandlers() {
    buttonViews.forEach { (id, view) ->
        view.setOnClickListener {
            val s = stateRef ?: return@setOnClickListener
            val slot = currentSlot(id) ?: return@setOnClickListener
            slot.actionResolver(s, services)?.let { onAction?.invoke(it) }
        }
    }
}

override fun render(state: DictateUiState, mode: LayoutMode) {
    stateRef = state                       // single field update
    modeRef = mode
    // … apply slots …
}
```

Owner ADR: ADR-0004 §"Failure Modes" (entry 5).

### (m) `actionResolver` returns `Action.NoOp`

**Forbidden:**

```kotlin
ButtonSlot(
    logicalId = LogicalButtonId.RECORD,
    actionResolver = { state, _ -> when (state.recording) {
        is RecordingState.Idle -> Action.RecordingAction.StartRecording
        is RecordingState.Active -> Action.RecordingAction.StopRecording
        is RecordingState.Preparing -> Action.NoOp                       // ❌
    } },
)
```

**Why it breaks:** `Action.NoOp` doesn't exist (R.3 — Spec 1
removed it). Even if it did, every NoOp would reach the
orchestrator and log `DispatchOutcome.Unrouted` or `Rejected` —
log-spam for normal user behavior (clicking while in a transient
state).

**Correct alternative:** return `null`:

```kotlin
ButtonSlot(
    logicalId = LogicalButtonId.RECORD,
    actionResolver = { state, _ -> when (state.recording) {
        is RecordingState.Idle -> Action.RecordingAction.StartRecording
        is RecordingState.Active -> Action.RecordingAction.StopRecording
        is RecordingState.Preparing -> null                               // ✓
    } },
)
```

The click-listener filters with `?.let { onAction(it) }` — no
action reaches the orchestrator, no log entry, clean no-op.

Owner ADR: ADR-0001 §"Failure Modes" (entry 7).

### (n) Direct module-to-module call

**Forbidden:**

```kotlin
// In OverlayModule.runEffect:
override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
    Effect.NotifyRecordingPause -> RecordingModule.pause()                // ❌ direct ref
}
```

**Why it breaks:** breaks module encapsulation. The compiler does
not stop this — the `RecordingModule` reference is reachable since
both modules live in the same package. But the call bypasses
single-dispatch and the cascade machinery; `RecordingModule.pause`
would have to be a side-door API that doesn't exist (and shouldn't).

**Correct alternative:** all inter-module communication goes
through the global state + Action pipe:

```kotlin
// In OverlayModule.runEffect:
Effect.NotifyRecordingPause -> services.emitAction(Action.RecordingAction.PauseRecording)
```

Or via `onCrossModuleStateChange` cascade if the trigger is a
state transition rather than an effect.

Owner ADR: ADR-0001 §"Failure Modes" (entry 8).

## 4. Mapping to ADR Failure-Modes

| Pattern | Bug class (plan §1.1) | Owner ADR |
|---|---|---|
| (a) Direct `_state.value =` | F-8 violation | ADR-0001 |
| (b) Hardware/IO in reducer | F1+F2 violation, test non-determinism | ADR-0001 |
| (c) `else`-branch in reduce-when | new variants silently swallowed | ADR-0001 |
| (d) Re-parenting on layout switch | §1.1 #1 + #2 (asymmetric re-parenting) | ADR-0004 |
| (e) `toMutableList()`-round-trip | structural-sharing destroyed | ADR-0001 |
| (f) Self-filter in cascade Step 5 | KG-RSB-2 HOVER-reopen bug | ADR-0002 |
| (g) Cross-axis mutation in reducer | Mode 3 (SRP violation) | ADR-0002 |
| (h) Synchronous re-dispatch from EffectHandler | frozen-snapshot violation | ADR-0001 |
| (i) LocalBinder forwarder methods | F-8 doppel-API drift | ADR-0001 |
| (j) `predResendVisible` with cooldown | §1.1 #3b (Resend-Toggle-Verschwinden) | ADR-0004 |
| (k) Missing `motion:visibilityMode="ignore"` | MotionScene fights predicate | ADR-0004 |
| (l) Per-tick click-listener rewiring | lambda leak | ADR-0004 |
| (m) `actionResolver` returns NoOp | R.3 / log-spam | ADR-0001 |
| (n) Direct module-to-module call | encapsulation breach | ADR-0001 |

## N. Information Gaps

(no gaps known at this time — the 14 patterns are the closed list per plan §4.0.1.5)

## N+1. Change History

### 2026-05-14 — Initial draft

- **Trigger:** Block 0 architecture anchor.
- **Reasoning:** Consolidates the 14 patterns from plan §4.0.1.5 +
  the Failure-Modes sections of ADRs 0001/0002/0004/0005 into one
  scannable catalogue. Each entry names the bug it would
  reactivate.

## N+2. References

- [Parent plan §4.0.1.5](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md)
- [ADR-0001 — state-modular-orchestrator-pattern](../../decisions/0001-state-modular-orchestrator-pattern.md)
- [ADR-0002 — state-cross-module-cascade](../../decisions/0002-state-cross-module-cascade.md)
- [ADR-0004 — ui-layout-catalog-motionlayout](../../decisions/0004-ui-layout-catalog-motionlayout.md)
- [Spec 1 §15.5 — Cross-Module-Effect-Modi (Mode 3 anti-pattern)](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [Spec 2 §11.6 — Click-Listener memory-leak analysis](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md)
- [`cross-module-cascade.md`](cross-module-cascade.md)
- [`rendering.md`](rendering.md)
- [`wiring-ui.md`](wiring-ui.md)
