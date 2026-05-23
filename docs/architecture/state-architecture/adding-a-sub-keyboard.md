---
date: 2026-05-14
author: Lukas + Claude Code
type: Architecture
status: Accepted
context: Walkthrough — two variants for adding a sub-keyboard (A: new ContentArea inside the IME; B: new RenderBackend on a new window).
related-plan: ../../plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md
related-adrs: ADR-0004
---

# Walkthrough — Adding a sub-keyboard

Two variants depending on whether the sub-keyboard lives **inside
the existing IME-View** (Variant A) or on a **separate window**
(Variant B).

Owner ADR: ADR-0004 §"Required mechanics". Plan source-of-truth:
§4.0.6.2.

## 1. Vision and Motivation

### 1.1 When this walkthrough applies

Use Variant A when:

- The sub-keyboard is a different view inside the same IME render
  surface (e.g. a numeric pad alongside QWERTY and emoji-picker).
- The keyboard hierarchy already has a content-area concept.

Use Variant B when:

- The sub-keyboard is a separate window (e.g. a notification-panel
  keyboard that lives outside the IME).
- The render surface needs its own WindowManager attachment,
  touch-routing, and permission model.

For a **new button** in an existing mode, use
[`adding-a-button.md`](adding-a-button.md). For a **new behavior**
that needs a state axis, use
[`adding-a-module.md`](adding-a-module.md).

## 2. Properties this Walkthrough Guarantees

1. **Both variants reuse the rendering primitives.** `ButtonSlot`,
   `LayoutMode`, `RenderBackend`, `applySlotToView` are the same.
2. **Variant A is content-area-driven.** A new
   `ContentArea` enum value + a new container view + a new
   `ContentAreaController` branch. No new RenderBackend.
3. **Variant B is RenderBackend-driven.** A new `RenderBackend`
   implementation + a new `BackendType` enum value + a new
   `LayoutMode` for the new surface. No content-area change.
4. **Both variants stay declarative.** No imperative ConstraintSet
   rewriting, no per-render-tick listener wiring.

## 3. Variant A — new ContentArea

Example: a numeric pad as an alternative to QWERTY and the
emoji-picker.

### Step 1 — Extend the `ContentArea` enum

```kotlin
// app/src/main/java/net/devemperor/dictate/state/LayoutState.kt
enum class ContentArea {
    MAIN_BUTTONS,
    QWERTZ,
    EMOJI_PICKER,
    NUMERIC_PAD,   // ← NEW
}
```

### Step 2 — Add the XML container

```xml
<!-- app/src/main/res/layout/activity_dictate_keyboard_view.xml -->
<FrameLayout
    android:id="@+id/numeric_pad_container"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:visibility="gone">

    <!-- Numeric buttons (0–9 + dot + backspace), or include from a
         dedicated res/layout/numeric_pad.xml -->
    <include layout="@layout/numeric_pad" />
</FrameLayout>
```

### Step 3 — Extend `ContentAreaController`

```kotlin
// app/src/main/java/net/devemperor/dictate/keyboard/render/ContentAreaController.kt
class ContentAreaController(private val views: KeyboardViews) : RenderBackend {
    override fun attach(onAction: (Action) -> Unit) {}
    override fun detach() {}
    override fun render(state: DictateUiState, mode: LayoutMode) {
        views.mainButtonsCl.visibility =
            if (state.layout.contentArea == ContentArea.MAIN_BUTTONS) View.VISIBLE else View.GONE
        views.qwertzContainer.visibility =
            if (state.layout.contentArea == ContentArea.QWERTZ) View.VISIBLE else View.GONE
        views.emojiPickerContainer.visibility =
            if (state.layout.contentArea == ContentArea.EMOJI_PICKER) View.VISIBLE else View.GONE
        views.numericPadContainer.visibility =                                      // ← NEW
            if (state.layout.contentArea == ContentArea.NUMERIC_PAD) View.VISIBLE else View.GONE
    }
}
```

### Step 4 — Add a trigger button (or button slot)

```kotlin
// In LayoutCatalog.KEYBOARD_TWO_ROW, add a new ButtonSlot:
ButtonSlot(
    logicalId = LogicalButtonId.NUMERIC_TOGGLE,
    actionResolver = { _, _ -> Action.LayoutAction.SetContentArea(ContentArea.NUMERIC_PAD) },
    // …
)
```

(Following the `adding-a-button.md` steps for the new
`LogicalButtonId.NUMERIC_TOGGLE`.)

### Step 5 — `LayoutModule.reduce` handles the action

`LayoutModule` already owns the `layout` axis, including
`contentArea`. The reducer arm for `SetContentArea` is:

```kotlin
// In LayoutModule.reduce:
is Action.LayoutAction.SetContentArea ->
    TransitionResult(
        nextState = state.copy(contentArea = action.contentArea),
        sideEffects = emptyList(),
    )
```

If `SetContentArea` doesn't exist yet, add the action variant
following the [`adding-a-button.md`](adding-a-button.md) step 4
pattern (sealed class addition in `Action.LayoutAction`).

### Step 6 — (Optional) Restore main-buttons on numeric-toggle long-press

If the numeric pad should be exit-via-long-press-or-back, add the
appropriate slot resolver or wire a special touch handler.

### Step 7 — Tests

Reducer test:

```kotlin
@Test
fun layoutModule_setContentAreaNumeric_emitsStateChange() {
    val result = LayoutModule.reduce(
        state = LayoutState(contentArea = ContentArea.MAIN_BUTTONS),
        action = Action.LayoutAction.SetContentArea(ContentArea.NUMERIC_PAD),
        ctx = ReducerContext(DictateUiState.initial()),
    )
    assertEquals(LayoutState(contentArea = ContentArea.NUMERIC_PAD), result?.nextState)
}
```

**Variant A complexity:** medium. ~50 LoC + one XML container +
one ButtonSlot.

## 4. Variant B — new RenderBackend on a new window

Example: a notification-panel keyboard that shows on a separate
`TYPE_APPLICATION_OVERLAY` window when the user expands the
notification shade.

### Step 1 — Define the new `RenderBackend`

```kotlin
// app/src/main/java/net/devemperor/dictate/keyboard/render/NotificationPanelBackend.kt
class NotificationPanelBackend(
    private val ctx: Context,
    private val services: ModuleServices,
    private val window: OverlayWindow,
) : RenderBackend {
    private var stateRef: DictateUiState? = null
    private var modeRef: LayoutMode? = null
    private var onAction: ((Action) -> Unit)? = null

    private lateinit var rootView: View
    private val buttonViews: Map<LogicalButtonId, View> = …

    override fun attach(onAction: (Action) -> Unit) {
        this.onAction = onAction
        rootView = LayoutInflater.from(ctx).inflate(R.layout.notification_panel_keyboard, null)
        window.add(rootView)
        wireStaticHandlers()
    }

    override fun detach() {
        window.remove(rootView)
        onAction = null
    }

    override fun render(state: DictateUiState, mode: LayoutMode) {
        require(mode.backend == BackendType.NOTIFICATION_PANEL)
        stateRef = state
        modeRef = mode

        mode.rows.flatMap { it.slots }.forEach { slot ->
            val view = buttonViews[slot.logicalId]
                ?: error("No view for ${slot.logicalId}")
            applySlotToView(slot, view, state, ctx)
        }
    }

    private fun wireStaticHandlers() {
        // Same once-wiring pattern as ImeViewBackend (see wiring-ui.md §3)
    }
}
```

### Step 2 — Extend `BackendType` enum

```kotlin
enum class BackendType {
    IME_VIEW,
    OVERLAY_WINDOW,         // WIDGET + HOVER
    NOTIFICATION_PANEL,     // ← NEW
}
```

### Step 3 — Add a new `LayoutMode` to `LayoutCatalog`

```kotlin
// app/src/main/java/net/devemperor/dictate/keyboard/LayoutCatalog.kt
val NOTIFICATION_PANEL_LAYOUT: LayoutMode = LayoutMode(
    id = LayoutModeId.NOTIFICATION_PANEL,
    backend = BackendType.NOTIFICATION_PANEL,
    sceneStateId = null,                // no MotionScene for the panel
    rows = listOf(
        RowDescriptor(slots = listOf(
            // … the slots that the panel needs
        )),
    ),
)
```

And add to `LayoutModeId`:

```kotlin
enum class LayoutModeId {
    // existing
    NOTIFICATION_PANEL,
}
```

### Step 4 — Backend switching in `KeyboardLayoutManager`

If the new mode is a new top-level ViewMode (e.g. you added
`ViewMode.NOTIFICATION_PANEL`), update `computeLayoutMode`:

```kotlin
private fun computeLayoutMode(state: DictateUiState): LayoutMode = when (state.viewMode) {
    ViewMode.KEYBOARD -> LayoutCatalog.forKeyboard(state)
    ViewMode.WIDGET, ViewMode.HOVER -> LayoutCatalog.OVERLAY_5BUTTON
    ViewMode.NOTIFICATION_PANEL -> LayoutCatalog.NOTIFICATION_PANEL_LAYOUT
}
```

And the backend attach/detach decision in the keyboard manager:

```kotlin
// In KeyboardLayoutManager.onStateChanged or a sibling method:
if (state.viewMode == ViewMode.NOTIFICATION_PANEL && notificationPanelBackend !in activeBackends) {
    attachBackend(notificationPanelBackend)
}
if (state.viewMode != ViewMode.NOTIFICATION_PANEL && notificationPanelBackend in activeBackends) {
    detachBackend(notificationPanelBackend)
}
```

(For the existing modes, the same multi-backend list mechanism
applies — see [`rendering.md`](rendering.md) §6.)

### Step 5 — Permission, WindowManager wiring, touch routing

A new window typically needs:

- A WindowManager attachment (`OverlayWindow` wrapper from Spec 3 §4)
- Permission check (e.g. `SYSTEM_ALERT_WINDOW` if the window is
  outside the app process; usually not needed for in-process
  windows but check)
- Touch routing decision: does the window intercept touches, or
  does it pass through?

These are window-implementation concerns — see Spec 3 §4 (overlay)
and Spec 3 §8 (touch-routing) for the WIDGET/HOVER reference
implementation.

### Step 6 — Triangle-FSM extension (if adding a new ViewMode)

If you introduced `ViewMode.NOTIFICATION_PANEL`:

- Update `computeViewMode` to return the new mode under the right
  conditions.
- Add new transitions T8+ for entering/leaving the new mode.
- Update [`triangle-fsm.md`](triangle-fsm.md).
- This is a Phase-2-scale change — see ADR-0005 §"Phase-2
  Superseding Expectations" for the supersede process.

### Step 7 — Tests

A new backend deserves both a unit test (resolver-driven slot
output) and an integration test (the manager switches backends
correctly on ViewMode change).

**Variant B complexity:** large. New window type, possibly new
permission, possibly new ViewMode + new T-transitions.

## 5. What you DON'T have to do (either variant)

- ❌ No edits to `DictateOrchestrator`, the dispatch loop, or any
  existing module's reducer.
- ❌ No DB migration.
- ❌ No edits to `SlotRenderer.applySlotToView` (unless you genuinely
  need a new slot property like `tint`, which benefits all
  backends).

## 6. Common mistakes

| Mistake | Why it breaks | Correct shape |
|---|---|---|
| Variant A: per-button `visibilityPredicate` includes `state.layout.contentArea == …` | Conceptual mismatch — every slot would duplicate the gate; SRP violation | Use `ContentAreaController` as a parallel RenderBackend (R.10 / Issue 2.1.15 Option B) |
| Variant B: new RenderBackend rewires listeners on every render | Lambda leak (forbidden pattern (l)) | `wireStaticHandlers` once in `attach()` |
| Variant B: backend mutates state directly (`store.update { … }`) | Single-dispatch violation (forbidden pattern (a)) | All mutation goes through `dispatch(action)` |
| Variant B: backend calls another backend's methods | Backend-to-backend coupling | All cross-backend coordination via state observation |
| New mode without `motion:visibilityMode="ignore"` on its state-driven buttons | MotionScene fights per-slot visibility | Add the attribute (forbidden pattern (k)) — IME-View backend only |

## 7. Information Gaps

(no gaps known at this time — Plan §4.0.6.2 covers both variants)

## 8. Change History

### 2026-05-14 — Initial draft

- **Trigger:** Block 0 architecture anchor.
- **Reasoning:** Captures plan §4.0.6.2 in didactic form. Variant A
  is the lightweight path (Container visibility, used by
  emoji-picker today); Variant B is the heavyweight path (new
  Window, used by overlay-window backend in WIDGET/HOVER).

## 9. References

- [Parent plan §4.0.6.2](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md)
- [ADR-0004 — ui-layout-catalog-motionlayout](../../decisions/0004-ui-layout-catalog-motionlayout.md)
- [Spec 2 §4 — KeyboardLayoutManager (multi-backend)](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md)
- [Spec 2 §5 — RenderBackend interface](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md)
- [Spec 3 §4 — OverlayBackend (reference Variant-B implementation)](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md)
- [`rendering.md`](rendering.md)
- [`wiring-ui.md`](wiring-ui.md)
- [`triangle-fsm.md`](triangle-fsm.md)
