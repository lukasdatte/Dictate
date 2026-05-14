---
date: 2026-05-14
author: Lukas + Claude Code
type: Architecture
status: Skeleton
context: Click-listener wiring, stateRef/modeRef discipline, nullable resolver idiom, special touch handlers, and the memory-leak structural protection.
related-plan: ../../plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md
related-adrs: ADR-0001, ADR-0004
---

# Wiring — UI handlers and click flow

This page describes how UI-elements wire to actions. Renders are
covered in [`rendering.md`](rendering.md); this page covers the
**listener** side.

Owner ADRs:
[ADR-0001 §"UI-Wiring boundary"](../../decisions/0001-state-modular-orchestrator-pattern.md)
+ [ADR-0004 §"Required mechanics"](../../decisions/0004-ui-layout-catalog-motionlayout.md)
items 7, 9.

## 1. Vision and Motivation

### 1.1 Why once-wiring (L8)

The naive pattern attaches click-listeners per render-tick:

```kotlin
override fun render(state: DictateUiState, mode: LayoutMode) {
    mode.rows.flatMap { it.slots }.forEach { slot ->
        val view = buttonViews[slot.logicalId]!!
        view.setOnClickListener {
            slot.actionResolver(state, services)?.let { onAction(it) }
        }
    }
}
```

Every `setOnClickListener` allocates a fresh lambda. At 60 Hz during
recording, that's 60 allocations × N buttons per second. Plus the
implicit `removeOnClickListener` work. Spec 2 §11.6 measures the
memory + CPU cost.

The **once-wiring** pattern hoists the lambda to `attach()` and lets
it read `stateRef` / `modeRef` backend fields:

```kotlin
private var stateRef: DictateUiState? = null
private var modeRef: LayoutMode? = null

override fun attach(onAction: (Action) -> Unit) {
    this.onAction = onAction
    wireStaticHandlers()
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
    stateRef = state
    modeRef = mode
    // … apply slots …
}
```

One lambda per button for the backend's lifetime. Memory cost:
constant.

### 1.2 What this solves

| Problem | Mechanism |
|---|---|
| Lambda allocation per render-tick | `wireStaticHandlers()` once in `attach()` |
| Listener reading stale state | `stateRef` field updated at the top of `render()` |
| `Action.NoOp` log-spam from inert clicks | `slot.actionResolver(...)?.let { … }` — null is silent |
| State-side visibility-check inside the listener | Visibility lives in `visibilityPredicate`; listener never checks |
| State-side cooldown-check inside the listener | Cooldown lives in `enabledResolver`; the Android system disables the view |
| Special-touch logic (CursorSwipe, etc.) tied to per-render rewiring | Special touch handlers are state-free OnTouchListeners, wired once |

## 2. Properties this Architecture Guarantees

1. **One listener per button per backend lifetime.** `wireStaticHandlers`
   runs once in `attach()`. `detach()` does **not** remove
   listeners (it nulls `stateRef`/`onAction` so they no-op).
2. **State read via backend field.** Listener lambdas reference
   `stateRef`, not the `render()` arguments.
3. **Nullable Resolver idiom.** `null` returns from `actionResolver`
   are silently dropped at the click site.
4. **No visibility/enabled check in the listener.** Visibility and
   enabled are managed by the render loop via predicates +
   resolvers.
5. **Special touch handlers are state-free.** CursorSwipe,
   BackspaceSwipe, EnterOverlay handlers don't read `stateRef`; they
   emit actions directly. Wired once in `attach()`.

## 3. The full `wireStaticHandlers` shape

```kotlin
private fun wireStaticHandlers() {
    buttonViews.forEach { (id, view) ->
        view.setOnClickListener {
            onVibrate()
            val s = stateRef ?: return@setOnClickListener
            val slot = currentSlot(id) ?: return@setOnClickListener
            slot.actionResolver(s, services)?.let { onAction?.invoke(it) }
        }
        keyPressAnimator.applyPressAnimation(view)
    }

    // Special long-click handlers (state-free or fixed-action)
    buttonViews[LogicalButtonId.RECORD]?.setOnLongClickListener {
        onVibrate(); true
    }
    buttonViews[LogicalButtonId.RESEND]?.setOnLongClickListener {
        onVibrate(); onAction?.invoke(Action.ResendAction.ResendLastAudioLong); true
    }
    buttonViews[LogicalButtonId.BACKSPACE]?.setOnLongClickListener { true }

    // Special touch handlers (state-free, wired once — L9, Spec 2 §11.7)
    buttonViews[LogicalButtonId.SPACE]?.setOnTouchListener(buildSpaceTouchHandler())
    buttonViews[LogicalButtonId.BACKSPACE]?.setOnTouchListener(buildBackspaceSwipeHandler())
    buttonViews[LogicalButtonId.ENTER]?.setOnTouchListener(buildEnterOverlayHandler())
}

private fun currentSlot(id: LogicalButtonId): ButtonSlot? =
    modeRef?.rows?.flatMap { it.slots }?.firstOrNull { it.logicalId == id }
```

Key properties:

- `currentSlot(id)` reads `modeRef` to find the active slot for
  the button — slots change across modes, but the view is the
  same physical button.
- The lambda body uses early-returns on null state/slot/action —
  no log-spam, no unrouted-dispatch.

## 4. The nullable resolver idiom (R.3)

`actionResolver` returns `Action?`. `null` means "no action right
now":

```kotlin
ButtonSlot(
    logicalId = LogicalButtonId.RECORD,
    actionResolver = { state, services -> when (state.recording) {
        is RecordingState.Idle -> Action.RecordingAction.StartRecording(
            audioFile = services.audioFileFactory.allocate(),
        )
        is RecordingState.Active -> Action.RecordingAction.StopRecording
        is RecordingState.Paused -> Action.RecordingAction.ResumeRecording
        is RecordingState.Preparing -> null    // wait — preparing is transient
    } },
    // …
)
```

The click-listener filters with `?.let`:

```kotlin
slot.actionResolver(s, services)?.let { onAction?.invoke(it) }
```

This structurally prevents `DispatchOutcome.Unrouted` log-spam for
"wrong-state" clicks (clicking RECORD during a transient state).
No action reaches the orchestrator → no log entry.

Forbidden pattern (m) in [`forbidden-patterns.md`](forbidden-patterns.md):
`actionResolver` returning `Action.NoOp`. The `NoOp` symbol does
not exist; `null` is the canonical no-op.

## 5. Click-flow diagram

```
USER CLICKS RECORD BUTTON
  ↓
view.setOnClickListener { … }   ← wired once in attach()
  ├── onVibrate()
  ├── s = stateRef               ← backend field, current state
  ├── slot = currentSlot(RECORD) ← lookup current slot by ID
  ├── action = slot.actionResolver(s, services)
  │     → Action.RecordingAction.StartRecording(audioFile=/cache/x.m4a)
  │       (Pre-Dispatch allocation via services.audioFileFactory — R.2)
  ├── action?.let { onAction(it) }
  │     ↓
  └── onAction = LocalBinder.dispatch (from attach())
        ↓
        DictateOrchestrator.dispatch(action)
        ↓
        RecordingModule.reduce → state.copy(recording = Preparing(...))
        ↓
        store.update → StateFlow emits new state
        ↓
        KeyboardLayoutManager.onStateChanged
        ↓
        backends.forEach { it.render(newState, computeLayoutMode(newState)) }
        ↓
        ImeViewBackend.render
        ├── stateRef = newState              ← updated for next click
        ├── modeRef = newMode
        ├── motionLayout.transitionToState(...)
        └── for each slot:
              applySlotToView(slot, view, state, ctx)
                ├── view.visibility = visibilityPredicate(state)
                ├── view.isEnabled = enabledResolver(state)
                ├── view.icon = iconResolver(state)
                └── view.text = textResolver(state)
```

The flow is **unidirectional**: click → action → reducer → state →
render. No back-channels, no per-render rewiring.

## 6. Special touch handlers (Spec 2 §11.7)

Three buttons have state-free touch handlers in addition to their
standard click-listener:

| Button | Handler | Purpose |
|---|---|---|
| `SPACE` | `CursorSwipeTouchHandler` | Swipe-to-move-cursor |
| `BACKSPACE` | `BackspaceSwipeHandler` | Swipe-to-delete-by-word |
| `ENTER` | `EnterOverlayHandler` | Long-press → overlay-character-picker |

These handlers are **state-free** — they don't read `stateRef`.
They emit actions directly:

```kotlin
private fun buildSpaceTouchHandler(): View.OnTouchListener =
    CursorSwipeTouchHandler(
        onCursorMove = { delta -> onAction?.invoke(Action.KeyboardInputAction.MoveCursor(delta)) },
        onClick = {
            val s = stateRef ?: return@CursorSwipeTouchHandler
            val slot = currentSlot(LogicalButtonId.SPACE) ?: return@CursorSwipeTouchHandler
            slot.actionResolver(s, services)?.let { onAction?.invoke(it) }
        },
    )
```

Wired once in `wireStaticHandlers()`. The handlers are listed in
Spec 2 §11.7 with full implementations.

## 7. Long-click handlers

Mixed approach:

- **State-free (fixed action):** RESEND long-press →
  `Action.ResendAction.ResendLastAudioLong`. Wired once.
- **State-aware:** if you need state-based long-click (rare), use
  the same `stateRef` discipline as the standard click listener.

Default: avoid long-click unless there's a clear UX need. Each
adds a touch-delay before regular clicks register.

## 8. View-map (`buttonViews: Map<LogicalButtonId, View>`)

The backend maintains a map from `LogicalButtonId` to `View`:

```kotlin
private val buttonViews: Map<LogicalButtonId, View> = mapOf(
    LogicalButtonId.RECORD        to rootView.findViewById(R.id.record_btn),
    LogicalButtonId.RESEND        to rootView.findViewById(R.id.resend_btn),
    LogicalButtonId.BACKSPACE     to rootView.findViewById(R.id.backspace_btn),
    LogicalButtonId.AUDIO_FOCUS   to rootView.findViewById(R.id.audio_focus_btn),
    LogicalButtonId.WIDGET_TOGGLE to rootView.findViewById(R.id.widget_toggle_btn),
    LogicalButtonId.TRASH         to rootView.findViewById(R.id.trash_btn),
    LogicalButtonId.SPACE         to rootView.findViewById(R.id.space_btn),
    LogicalButtonId.PAUSE         to rootView.findViewById(R.id.pause_btn),
    LogicalButtonId.ENTER         to rootView.findViewById(R.id.enter_btn),
)
```

`wireStaticHandlers` iterates the map; `render` does the same for
applying slot properties. A missing entry is a hard `error(...)`
at render time (Spec 2 §6, Issue 3.0.12 "Silent-Skip-Schutz").

## 9. `detach()` semantics

```kotlin
override fun detach() {
    this.onAction = null
    firstRender = true
    // Click-listeners are NOT removed — they reference stateRef which
    // is now null + onAction which is now null; the lambda becomes a
    // safe no-op until next attach.
}
```

We do **not** call `view.setOnClickListener(null)` because:

- The listeners are held by the views, which are released when the
  Activity/Service destroys them (no leak).
- Re-attach (`attach()` called again on the same backend) would
  have to re-wire all listeners — extra work for no benefit.
- A click during the detach-attach window is a safe no-op via the
  null-checks in the lambda body.

`firstRender = true` resets so the next attach's first render
uses `jumpToState` (no animation from initial state).

## 10. Memory-leak structural protection — per-tick rewiring vs once-wiring

The forbidden pattern (l) is per-render-tick listener rewiring:

```kotlin
// ❌ FORBIDDEN — Lambda leak per render
override fun render(state: DictateUiState, mode: LayoutMode) {
    mode.rows.flatMap { it.slots }.forEach { slot ->
        val view = buttonViews[slot.logicalId]!!
        view.setOnClickListener {
            slot.actionResolver(state, services)?.let { onAction(it) }
        }
    }
}
```

Spec 2 §11.6 measures the cost:

- N buttons × 60 Hz × 8 hours of recording session = 1.7M lambda
  allocations + 1.7M view-event-listener resets.
- Each lambda captures `state` (the entire `DictateUiState`) — heap
  pressure proportional to state-size.
- Garbage collection pauses become visible at the 60 Hz tick.

The once-wiring with `stateRef` is the structural fix:

```kotlin
// ✓ ALLOWED — one lambda per button per backend lifetime
private fun wireStaticHandlers() {
    buttonViews.forEach { (id, view) ->
        view.setOnClickListener {
            val s = stateRef ?: return@setOnClickListener
            val slot = currentSlot(id) ?: return@setOnClickListener
            slot.actionResolver(s, services)?.let { onAction?.invoke(it) }
        }
    }
}
```

- N lambda allocations total. Constant.
- `stateRef` is one field reference — the lambda doesn't capture
  the full state.
- The lambda survives across renders; no listener-set/unset work.

The Block-5 acceptance test (Spec 2 §10) includes an Espresso
assertion that `setOnClickListener` is called exactly once per
attach.

## N. Information Gaps

(no gaps known at this time — Spec 2 §6 + §11.6 + §11.7 cover the wiring layer)

## N+1. Change History

### 2026-05-14 — Initial draft

- **Trigger:** Block 0 architecture anchor.
- **Reasoning:** Captures the click-listener + special-touch wiring
  discipline from Spec 2 §6 + §11.6 + §11.7 + ADR-0001 §"UI-Wiring
  boundary" + ADR-0004 in tutorial form.

## N+2. References

- [ADR-0001 — state-modular-orchestrator-pattern](../../decisions/0001-state-modular-orchestrator-pattern.md) §"UI-Wiring boundary"
- [ADR-0004 — ui-layout-catalog-motionlayout](../../decisions/0004-ui-layout-catalog-motionlayout.md) §"Required mechanics" items 7, 9
- [Spec 2 §6 — ImeViewBackend (wireStaticHandlers)](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md)
- [Spec 2 §11.6 — Click-Listener-Lifecycle / memory-leak analysis](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md)
- [Spec 2 §11.7 — Special-Touch handlers](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md)
- [`rendering.md`](rendering.md)
- [`forbidden-patterns.md`](forbidden-patterns.md) §(l), §(m)
