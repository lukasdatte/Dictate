---
date: 2026-05-14
author: Lukas + Claude Code
type: Architecture
status: Accepted
context: The KEYBOARD / WIDGET / HOVER FSM — three modes, seven transitions, the computeViewMode function, transience of userPrefersWidget, and the T7 Geist-Widget structural guard.
related-plan: ../../plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md
related-adrs: ADR-0005
---

# Triangle-FSM (KEYBOARD / WIDGET / HOVER)

This page describes the **ViewMode FSM** — three modes, seven
transitions, all implemented as Mode-2 cascades.

Owner ADR:
[ADR-0005 — ui-triangle-fsm-keyboard-widget-hover](../../decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md).
Prerequisite reading:
[`cross-module-cascade.md`](cross-module-cascade.md).

## 1. Vision and Motivation

### 1.1 Three modes, two product reasons

Two product requirements (plan §1.2) shape the FSM:

- **HOVER** — automatic floating UI when the IME-View is gone but
  the pipeline is still running. The user has switched to Gboard
  for a password field; the dictation must keep going and the user
  needs visible controls (Send / Pause / Trash / Close).
- **WIDGET** — user-toggled floating keyboard. All buttons live,
  Send works (InputConnection is alive), Record can start new
  recordings.

The two modes **share the same 5-button overlay layout**
(`OVERLAY_5BUTTON`, parent plan §7 OPEN-2 resolution); the only
difference is the Send button's `enabled` state.

### 1.2 What this solves

| Problem | Mechanism |
|---|---|
| Pipeline running but no UI when keyboard switches | HOVER auto-trigger on `!imeViewVisible && pipelineActive` |
| User wants floating keyboard during long dictation | WIDGET user-toggle (permission-gated) |
| WIDGET-close should drop into "small keyboard" mode | T2 cascade → `LayoutAction.SetSmallMode(true)` |
| HOVER hanging after pipeline-done ("Geist-Widget") | T7 — `PipelineDone` cascade re-computes ViewMode |
| WIDGET state leaking across sessions | `userPrefersWidget` is transient (in-memory) |

## 2. Properties this Architecture Guarantees

1. **Computed ViewMode.** `computeViewMode(imeViewVisible,
   userPrefersWidget, pipelineActive) → ViewMode` is pure,
   exhaustively tested via truth table.
2. **`userPrefersWidget` is transient.** Lives in
   `state.overlay.userPrefersWidget`; not persisted. A new pipeline
   session starts WIDGET-off.
3. **All transitions are Mode-2 cascades.** ADR-0002's
   cascade machinery (frozen snapshot, depth cap, self-cascade
   allowance) is a hard prerequisite.
4. **T7 is mandatory.** PipelineDone → re-evaluate → HOVER becomes
   KEYBOARD. Structural guard against "Geist-Widget".
5. **Permission-gated WIDGET.** Without
   `state.overlay.hasPermission`, T1 is a silent no-op (the
   onboarding flow surfaces the permission prompt at click time).

## 3. The three modes

```
                   ┌──────────────────────────────┐
                   │        KEYBOARD              │
                   │  (full keyboard, normal)     │
                   │                              │
                   │  - Two-Row / Single-Row      │
                   │  - Send-mode variants        │
                   │  - ReprocessStaging          │
                   │  - InputConnection LIVE      │
                   └──────────────────────────────┘
                       │   ▲                  ▲
                       │   │                  │
              T1: user │   │ T2: user         │  T5: IME view
              clicks   │   │ clicks Close     │      returns (no
              Widget-  │   │ in WIDGET        │      widget-pref)
              Toggle   │   │ (→ SmallMode)    │
                       ▼   │                  │
                   ┌─────────────────────────┐  │
                   │       WIDGET            │  │
                   │  (user choice, float)   │  │
                   │                         │  │
                   │   - 5 buttons           │  │
                   │   - Send works          │  │
                   │   - InputConnection alv │  │
                   └─────────────────────────┘  │
                       │   ▲                    │
                       │   │ T6: IME view       │
              T4: view │   │ returns + widget-  │
              hidden + │   │ pref persists      │
              pipeline │   │                    │
              active   │   │                    │
                       ▼   │                    │
                   ┌─────────────────────────┐  │
                   │      HOVER (auto)       │──┘
                   │                         │  T7 also reaches here
                   │  - 5 buttons (same as   │      via Pipeline-Done
                   │    WIDGET)              │      cascade — the
                   │  - Send DISABLED        │      Geist-Widget
                   │  - Close → dismiss      │      structural guard.
                   │  - InputConnection ∅    │
                   └─────────────────────────┘
                              ▲
                              │
                              │  T3: view hidden + pipeline active
                              │      (from KEYBOARD)
```

## 4. `computeViewMode` truth table

```kotlin
fun computeViewMode(
    imeViewVisible: Boolean,
    userPrefersWidget: Boolean,
    pipelineActive: Boolean,
): ViewMode = when {
    imeViewVisible && userPrefersWidget    -> ViewMode.WIDGET
    imeViewVisible && !userPrefersWidget   -> ViewMode.KEYBOARD
    !imeViewVisible && pipelineActive      -> ViewMode.HOVER
    else                                    -> ViewMode.KEYBOARD
}
```

Truth table:

| imeViewVisible | userPrefersWidget | pipelineActive | ViewMode |
|:---:|:---:|:---:|---|
| true | true | * | WIDGET |
| true | false | * | KEYBOARD |
| false | * | true | HOVER |
| false | * | false | KEYBOARD |

`pipelineActive` is computed at the call site:
`ctx.global.pipeline !is PipelineUiState.Idle || ctx.global.recording.isActiveOrPaused`.

Why "KEYBOARD" when nothing is visible? It's the **default/null
state** — no UI is rendered (the IME-View is gone, the overlay is
not opened), but the FSM stays in a well-defined value rather than
a fourth "NONE" enum. KEYBOARD becomes the resting state across the
two "no-UI" rows of the truth table.

## 5. The seven transitions T1–T7

All seven transitions are Mode-2 cascades. The `ViewModeModule`
mutates only `viewMode`; sibling modules (`LayoutModule`,
`OverlayModule`, `PipelineModule`) handle their own axes via
`onCrossModuleStateChange`.

### T1: KEYBOARD → WIDGET (user clicks Widget-Toggle)

**Trigger:** click on `widget_toggle_btn` → `actionResolver` returns
`Action.ViewModeAction.ToggleViewModeWidget`.

```kotlin
// ViewModeModule.reduce — mutates ONLY viewMode (SRP-clean, Mode 2):
when (action) {
    Action.ViewModeAction.ToggleViewModeWidget -> {
        if (!ctx.global.overlay.hasPermission) {
            null   // permission missing — silent no-op; onboarding handles UI prompt
        } else when (state) {
            ViewMode.KEYBOARD -> TransitionResult(ViewMode.WIDGET, emptyList())
            ViewMode.WIDGET   -> TransitionResult(ViewMode.KEYBOARD, emptyList())
            else              -> null
        }
    }
}

// OverlayModule.onCrossModuleStateChange — KEYBOARD → WIDGET sets userPrefersWidget:
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.viewMode == ViewMode.KEYBOARD && next.viewMode == ViewMode.WIDGET)
        listOf(Action.OverlayAction.SetUserPrefersWidget(true))
    else emptyList()
```

### T2: WIDGET → KEYBOARD (user clicks Close in WIDGET)

**Trigger:** click on `overlay_close_btn` in WIDGET → `actionResolver`
branches on `state.viewMode`:

```kotlin
// OVERLAY_CLOSE slot's actionResolver:
actionResolver = { state, _ -> when (state.viewMode) {
    ViewMode.WIDGET -> Action.ViewModeAction.ToggleViewModeWidget   // → T2
    ViewMode.HOVER  -> Action.ViewModeAction.CloseOverlay            // → dismiss + suppress
    else            -> null
} }
```

Cascade follow-ups:

```kotlin
// LayoutModule.onCrossModuleStateChange — WIDGET → KEYBOARD activates SmallMode:
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.viewMode == ViewMode.WIDGET && next.viewMode == ViewMode.KEYBOARD)
        listOf(Action.LayoutAction.SetSmallMode(true))
    else emptyList()

// OverlayModule.onCrossModuleStateChange — reset userPrefersWidget:
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.viewMode == ViewMode.WIDGET && next.viewMode == ViewMode.KEYBOARD)
        listOf(Action.OverlayAction.SetUserPrefersWidget(false))
    else emptyList()
```

Result: viewMode = KEYBOARD, layout.smallMode = true,
overlay.userPrefersWidget = false. Three modules each owning their
axis; cascade depth = 2.

### T3: KEYBOARD → HOVER (IME view hidden + pipeline active)

**Trigger:** Android calls `onFinishInputView` in the IME-Service.

```kotlin
// In DictateInputMethodService:
override fun onFinishInputView(finishingInput: Boolean) {
    super.onFinishInputView(finishingInput)
    pipeline?.dispatch(Action.ViewModeAction.OnImeViewHidden)
}

// ViewModeModule.reduce:
when (action) {
    Action.ViewModeAction.OnImeViewHidden -> {
        val newViewMode = computeViewMode(
            imeViewVisible = false,
            userToggledWidget = ctx.global.overlay.userPrefersWidget,
            pipelineActive = ctx.global.pipeline !is PipelineUiState.Idle
                              || ctx.global.recording.isActiveOrPaused,
        )
        if (newViewMode != state) TransitionResult(newViewMode, emptyList()) else null
    }
}
```

Concrete: `computeViewMode(false, false, true) = HOVER`. The render
backend switches: `KeyboardLayoutManager.onStateChanged` →
`detachBackend(imeViewBackend)` + `attachBackend(overlayBackend)`,
`OverlayBackend` renders `OVERLAY_5BUTTON` with Send disabled.

### T4: WIDGET → HOVER (IME view hidden, was WIDGET)

Same code path as T3, but `state.overlay.userPrefersWidget == true`.
The `computeViewMode` result is the same — HOVER. The
`userPrefersWidget` bit stays set in `OverlayState`; T6 will
respect it when the view returns.

Why HOVER (not "WIDGET while view is gone")? Because WIDGET
requires both a visible view AND user-preference; without the
visible view, InputConnection is dead, Send doesn't work. HOVER is
the correct UX (Send disabled, dismissable).

### T5: HOVER → KEYBOARD (view returns, no widget-pref)

**Trigger:** Android calls `onStartInputView` in the IME-Service.

```kotlin
// In DictateInputMethodService:
override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
    super.onStartInputView(info, restarting)
    pipeline?.dispatch(Action.ViewModeAction.OnImeViewShown)
}

// ViewModeModule.reduce — same shape as T3 but visible=true:
// computeViewMode(true, false, *) = KEYBOARD
```

### T6: HOVER → WIDGET (view returns + widget-pref persists)

```kotlin
// ViewModeModule.reduce — visible=true, userPrefersWidget=true:
// computeViewMode(true, true, *) = WIDGET
```

The `userPrefersWidget` bit was set in T1 (WIDGET activation) and
remains set across T4 (HOVER auto-trigger) — so when the view
returns, the user lands back in WIDGET.

### T7: HOVER → KEYBOARD via Pipeline-Done cascade (Geist-Widget guard)

**Trigger:** `PipelineModule.onCrossModuleStateChange` observes
`PipelineUiState.Done`:

```kotlin
// PipelineModule.onCrossModuleStateChange:
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.pipeline !is PipelineUiState.Done && next.pipeline is PipelineUiState.Done)
        listOf(Action.ViewModeAction.OnPipelineDone)
    else emptyList()

// ViewModeModule.reduce:
when (action) {
    Action.ViewModeAction.OnPipelineDone -> {
        // Pipeline is done; if HOVER, re-compute with pipelineActive=false.
        // IME visibility derives from current ViewMode: HOVER = IME hidden
        // (per the T3/T4 path that put us here); KEYBOARD/WIDGET = IME visible.
        val newViewMode = computeViewMode(
            imeViewVisible = state != ViewMode.HOVER,
            userToggledWidget = ctx.global.overlay.userPrefersWidget,
            pipelineActive = false,
        )
        if (newViewMode != state) TransitionResult(newViewMode, emptyList()) else null
    }
}
```

In HOVER (state was set via T3 or T4, so `imeViewVisible == false`):
`computeViewMode(false, *, false) = KEYBOARD`. The render backend
switches: `KeyboardLayoutManager` re-attaches `imeViewBackend`,
detaches `overlayBackend`. The IME-View isn't actually visible
(the IME is dismounted), but the FSM stays in KEYBOARD as the
"no-UI resting state" rather than in a HOVER that has no purpose.

**Without T7**, the overlay would remain visible after pipeline-done
— the "Geist-Widget" bug class. The user sees a floating
notification with no pipeline running, no recording, just three
disabled buttons. T7 is the structural guard.

## 6. WIDGET in HOVER mode — variant T7-WIDGET

If `userPrefersWidget == true` (the previous mode was WIDGET → HOVER
via T4), what happens at T7?

```
computeViewMode(visible=false, userPrefersWidget=true, pipelineActive=false)
  → row 4 of truth table: "!visible && !pipelineActive → KEYBOARD"
```

Result: KEYBOARD — **not WIDGET**. This is correct: WIDGET requires
a visible view OR an active pipeline. Without both, KEYBOARD (=
no-UI resting state) is the right value. The `userPrefersWidget`
bit remains set; if the user re-opens the IME (T6), they land in
WIDGET.

## 7. `userPrefersWidget` — why transient

`state.overlay.userPrefersWidget` is **not persisted** (no DB, no
SharedPreferences). The rationale (Spec 3 §11.9):

- WIDGET is opt-in for a specific use case (long dictation while
  doing something else on screen). It's not a "default mode"
  preference.
- Persisting across sessions would lock the user into WIDGET until
  they remember to toggle off — discoverability problem.
- A new pipeline session is a natural cleanup boundary.
- The bit is reset to `false` in T2 (user clicks Close in WIDGET)
  and on `Action.OverlayAction.CloseOverlay` (HOVER-close).

Forbidden pattern: persisting `userPrefersWidget` to DB or
SharedPreferences. If a future feature genuinely needs this, the
right shape is a separate `Pref.PreferWidgetMode` user-setting
(opt-in via Settings, not via per-session toggle).

## 8. Permission gate — silent no-op without permission

T1 (KEYBOARD → WIDGET) requires `state.overlay.hasPermission`. The
gate is in `ViewModeModule.reduce`:

```kotlin
if (!ctx.global.overlay.hasPermission) {
    null   // silent no-op — semantically "Rejected"
}
```

A silent no-op is not user-friendly. The onboarding flow (Spec 3
§5.3) handles the user-facing prompt: the Widget-Toggle button's
`actionResolver` checks `state.overlay.hasPermission` and either
returns `ToggleViewModeWidget` (if permission granted) or
`Action.OverlayAction.RequestOverlayPermission` (if missing).
That action launches the Android Settings intent.

The reducer guard is defence-in-depth: even if a developer changes
the `actionResolver` and breaks the gate at the UI layer, the
reducer still refuses to enter WIDGET without permission.

## 9. Permission loss while in WIDGET

Edge case: the user revokes overlay-permission via Android settings
while WIDGET is active. The `OverlayPermissionObserver` (Spec 3
§5.0) detects the change and dispatches
`Action.OverlayAction.SetPermission(false)`. Cascade:

```kotlin
// OverlayModule.onCrossModuleStateChange — permission loss while WIDGET:
override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
    if (prev.overlay.hasPermission && !next.overlay.hasPermission
        && next.viewMode == ViewMode.WIDGET)
        listOf(Action.ViewModeAction.SetViewMode(ViewMode.KEYBOARD))
    else emptyList()
```

The user sees the WIDGET dismount; the IME-View pops back in
(or stays gone if `imeViewVisible == false`, in which case the
recomputed ViewMode is whatever `computeViewMode(false, *,
pipelineActive)` yields).

## 10. Close-button differential — WIDGET vs HOVER

Same `OVERLAY_CLOSE` slot, two different actions depending on
ViewMode:

```kotlin
val OVERLAY_CLOSE = ButtonSlot(
    logicalId = LogicalButtonId.OVERLAY_CLOSE,
    actionResolver = { state, _ -> when (state.viewMode) {
        ViewMode.WIDGET -> Action.ViewModeAction.ToggleViewModeWidget   // → T2 (close to KEYBOARD with SmallMode)
        ViewMode.HOVER  -> Action.ViewModeAction.CloseOverlay            // → dismiss + suppress for session
        else            -> null
    } },
    // …
)
```

Difference in HOVER:

- `Action.OverlayAction.CloseOverlay` is dispatched
- `OverlayModule.reduce` sets `suppressAutoOverlayUntilNextSession =
  true` — HOVER does NOT auto-reopen for the current pipeline
  session
- `userPrefersWidget` is reset to false (so the next IME-Hidden
  trigger doesn't reopen WIDGET)
- The user dismisses the overlay; it does not return until the next
  recording session starts (which triggers
  `RecordingModule.onCrossModuleStateChange` →
  `Action.OverlayAction.ResetSuppressBit` cascade)

## 11. The Geist-Widget bug class — what T7 prevents

Without T7, the FSM has no auto-transition out of HOVER except T5/T6
(view returns). If the user has switched to another app entirely
(IME is gone, no view-return event), HOVER persists until the user
re-opens an IME-using app. Meanwhile the pipeline finishes (done),
but the overlay keeps showing.

Symptoms:

- Three disabled buttons floating on the screen (Send/Pause/Trash
  all disabled because no pipeline).
- The user has no way to dismiss it without re-opening the IME
  and explicitly closing.

The T7 cascade fixes this structurally: as soon as pipeline-done is
emitted, `OnPipelineDone` cascade recomputes `viewMode → KEYBOARD`
(the no-UI resting state), the overlay window is torn down by
`OverlayBackend.detach()`. The user no longer sees the
purposeless overlay.

## 12. Information Gaps

(no gaps known at this time — Spec 3 §7 + ADR-0005 cover the FSM exhaustively)

## 13. Change History

### 2026-05-14 — Initial draft

- **Trigger:** Block 0 architecture anchor.
- **Reasoning:** Captures the Triangle-FSM from Spec 3 §7 + ADR-0005
  + Phase-B S-8 (T7 addition) + Phase-B S-9 (Mode-2 cascade
  rewriting) in tutorial form.

## 14. References

- [ADR-0005 — ui-triangle-fsm-keyboard-widget-hover](../../decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md)
- [Spec 3 §7.1 — computeViewMode](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md)
- [Spec 3 §7.3 — T1–T7 code snippets](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md)
- [Spec 3 §6 — Close-button differential](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md)
- [Spec 3 §11.9 — userPrefersWidget transience](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md)
- [Spec 1 §15.1 — ViewModeModule + Coupling-Matrix](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [`cross-module-cascade.md`](cross-module-cascade.md)
- [`rendering.md`](rendering.md)
