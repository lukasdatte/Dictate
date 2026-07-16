---
date: 2026-05-14
author: Lukas + Claude Code
type: Architecture
status: Accepted
context: The render-backend pattern, LayoutCatalog, MotionScene, and how predicates/resolvers turn state into views.
related-plan: ../../plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md
related-adrs: ADR-0004
---

# Rendering

This page describes the **`RenderBackend` interface**, the
**`LayoutCatalog`** with its predicate/resolver model, the
**MotionScene XML** as positional source-of-truth, and the
**`computeLayoutMode`** function.

Owner ADR:
[ADR-0004 — ui-layout-catalog-motionlayout](../../decisions/0004-ui-layout-catalog-motionlayout.md).
Companion: [`wiring-ui.md`](wiring-ui.md) covers the click-listener
side of the same backend.

## 1. Vision and Motivation

### 1.1 Why this rendering model

Pre-refactor, the IME's button area used **imperative ConstraintSet
rewriting + view re-parenting** per mode (KeyboardLayoutModeController.kt).
Plan §1.1 bugs #1, #2, #3a, #3b cluster in that mechanism. Visibility
was set across 5+ controllers per button (Spec 1 §13.1 audit).

The fix is **declarative**: every per-button decision lives in a
`ButtonSlot` (visibility, icon, text, enabled, action); every render
backend iterates the slots and applies the result. Position lives
in MotionScene XML — no re-parenting, just transitions between
ConstraintSets.

### 1.2 What this solves

| Pre-refactor pain | Post-refactor mechanism |
|---|---|
| Re-parenting → asymmetric-revert bugs | MotionScene `transitionToState` (no re-parent) |
| 5 visibility mutators per button | One `visibilityPredicate` per slot |
| `recordButton.text/isEnabled` race | One `enabledResolver` + `textResolver` per slot |
| Content-area visibility forced into per-slot predicates | `ContentAreaController` as parallel `RenderBackend` (R.10) |
| Click-listener leak from per-render rewiring | `wireStaticHandlers` once in `attach()` (L8) |
| MotionScene visibility fighting per-slot visibility | `motion:visibilityMode="ignore"` mandatory |
| Initial-render animating from initial state | `firstRender` flag → `jumpToState` |

## 2. Properties this Architecture Guarantees

1. **Pure data classes for layout.** `LayoutMode`, `RowDescriptor`,
   `ButtonSlot` are immutable data classes. No setter, no controller.
2. **One Slot→View mapper.** `SlotRenderer.applySlotToView` is the
   single function that translates a slot's resolvers into Android
   view properties.
3. **Multi-backend parallel.** `KeyboardLayoutManager` holds a list
   of active backends, not one. KEYBOARD mode attaches
   `ImeViewBackend` + `ContentAreaController` simultaneously.
4. **Declarative position.** `MotionScene` XML is the source-of-truth
   for constraints. ConstraintSets are not built programmatically.
5. **VISIBILITY_MODE_IGNORE on every state-driven button.** MotionScene
   transitions animate position; per-slot `visibilityPredicate`
   controls visibility. The two layers don't fight.

## 3. The stack

```
┌─────────────────────────────────────────────────────────────────────┐
│  LAYER 1 — KeyboardLayoutManager                          (top)     │
│  Type:   class                                                      │
│  File:   app/src/main/java/net/devemperor/dictate/                  │
│            keyboard/KeyboardLayoutManager.kt                        │
│  Form:   onStateChanged(state) → activeBackends.forEach { render }  │
└─────────────────────────────────────────────────────────────────────┘
                                ↓ collects state, dispatches to backends
┌─────────────────────────────────────────────────────────────────────┐
│  LAYER 2 — RenderBackend (interface)                                │
│  Type:   interface RenderBackend                                    │
│  File:   app/src/main/java/net/devemperor/dictate/                  │
│            keyboard/render/RenderBackend.kt                         │
│  Form:   attach(onAction) / detach() / render(state, mode)          │
└─────────────────────────────────────────────────────────────────────┘
              ↓                ↓                       ↓
┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────────┐
│ ImeViewBackend      │  │ ContentAreaController│ │ OverlayBackend          │
│ (KEYBOARD)          │  │ (Container vis.)     │ │ (WIDGET + HOVER)        │
│                     │  │                      │ │                         │
│ Iterates LayoutMode │  │ Iterates contentArea │ │ Iterates OVERLAY_5BTN   │
│ slots; uses         │  │ enum, sets visibility│ │ slots via shared        │
│ MotionLayout.       │  │ on three containers. │ │ applySlotToView.        │
│ jumpToState /       │  │                      │ │                         │
│ transitionToState.  │  │                      │ │                         │
└─────────────────────┘  └─────────────────────┘  └─────────────────────────┘
                                ↓
                       ┌─────────────────────────────────────┐
                       │  Shared helper                       │
                       │  SlotRenderer.applySlotToView        │
                       │  (top-level function, F-7 / DRY)     │
                       │  visibility + enabled + icon + text  │
                       │  + alpha — single Slot→View mapper.  │
                       └─────────────────────────────────────┘
```

## 4. The data model

### 4.1 `LogicalButtonId`

```kotlin
enum class LogicalButtonId {
    // KEYBOARD render surface
    RECORD, RESEND, BACKSPACE, AUDIO_FOCUS, WIDGET_TOGGLE,
    TRASH, SPACE, PAUSE, ENTER,
    // OVERLAY render surface
    OVERLAY_RECORD, OVERLAY_SEND, OVERLAY_PAUSE, OVERLAY_TRASH, OVERLAY_CLOSE,
}
```

Adding a button is `+1 enum entry + 1 view-map entry + 1 slot in
the catalog + an optional action + an optional reducer arm` —
walked through in [`adding-a-button.md`](adding-a-button.md).

### 4.2 `ButtonSlot`

```kotlin
data class ButtonSlot(
    val logicalId: LogicalButtonId,
    val widthPolicy: WidthPolicy,                                  // WrapContent / FillRow / …
    val visibilityPredicate: (DictateUiState) -> Boolean,
    val iconResolver: (DictateUiState) -> Int? = { null },          // drawable resource ID
    val textResolver: (DictateUiState) -> CharSequence? = { null },
    val enabledResolver: (DictateUiState) -> Boolean = { true },
    val alphaResolver: (DictateUiState) -> Float = { 1.0f },
    val actionResolver: (DictateUiState, ModuleServices) -> Action?, // ← Action? (nullable, R.3)
)
```

Resolvers are pure functions on `(DictateUiState)` (and
`ModuleServices` for action-resolvers that need pre-dispatch
allocation, e.g. AudioFileFactory). Each resolver runs every
render-tick — keep them O(1).

### 4.3 `RowDescriptor` + `LayoutMode`

```kotlin
data class RowDescriptor(
    val slots: List<ButtonSlot>,
)

data class LayoutMode(
    val id: LayoutModeId,
    val backend: BackendType,
    val sceneStateId: Int? = null,         // R.id.two_row_state etc. for KEYBOARD
    val rows: List<RowDescriptor>,
)

enum class LayoutModeId {
    KEYBOARD_TWO_ROW, KEYBOARD_SINGLE_ROW,
    KEYBOARD_TWO_ROW_SEND_MODE, KEYBOARD_SINGLE_ROW_SEND_MODE,
    KEYBOARD_REPROCESS_STAGING,
    OVERLAY_5BUTTON,
}

enum class BackendType { IME_VIEW, OVERLAY_WINDOW }
```

### 4.4 `LayoutCatalog`

```kotlin
object LayoutCatalog {
    val KEYBOARD_TWO_ROW: LayoutMode = LayoutMode(
        id = LayoutModeId.KEYBOARD_TWO_ROW,
        backend = BackendType.IME_VIEW,
        sceneStateId = R.id.two_row_state,
        rows = listOf(
            RowDescriptor(slots = listOf(
                ButtonSlot(
                    logicalId = LogicalButtonId.RECORD,
                    widthPolicy = WidthPolicy.WrapContent,
                    visibilityPredicate = { it.viewMode == ViewMode.KEYBOARD },
                    iconResolver = { state -> when (state.recording) {
                        is RecordingState.Active -> R.drawable.ic_stop
                        is RecordingState.Paused -> R.drawable.ic_play
                        else -> R.drawable.ic_mic
                    } },
                    enabledResolver = { it.audio.audioFocusGranted },
                    actionResolver = { state, services -> when (state.recording) {
                        is RecordingState.Idle -> Action.RecordingAction.StartRecording(
                            audioFile = services.audioFileFactory.allocate(),
                        )
                        is RecordingState.Active -> Action.RecordingAction.StopRecording
                        is RecordingState.Paused -> Action.RecordingAction.ResumeRecording
                        else -> null
                    } },
                ),
                // … RESEND, BACKSPACE slots
            )),
            // … input_row
        ),
    )

    val KEYBOARD_SINGLE_ROW: LayoutMode = …
    val KEYBOARD_TWO_ROW_SEND_MODE: LayoutMode = …
    val KEYBOARD_SINGLE_ROW_SEND_MODE: LayoutMode = …
    val KEYBOARD_REPROCESS_STAGING: LayoutMode = …
    val OVERLAY_5BUTTON: LayoutMode = …     // shared between WIDGET + HOVER

    fun forKeyboard(state: DictateUiState): LayoutMode =
        when {
            state.pipeline is PipelineUiState.ReprocessStaging -> KEYBOARD_REPROCESS_STAGING
            state.pipeline is PipelineUiState.Running && state.pipeline.target == InsertionTarget.SEND
                && state.layout.singleRowMode -> KEYBOARD_SINGLE_ROW_SEND_MODE
            state.pipeline is PipelineUiState.Running && state.pipeline.target == InsertionTarget.SEND
                -> KEYBOARD_TWO_ROW_SEND_MODE
            state.layout.singleRowMode -> KEYBOARD_SINGLE_ROW
            else -> KEYBOARD_TWO_ROW
        }
}
```

Spec 2 §8 is the canonical SoT for the predicates / resolvers
across all six modes.

## 5. The `RenderBackend` interface

```kotlin
interface RenderBackend {
    fun attach(onAction: (Action) -> Unit)
    fun detach()
    fun render(state: DictateUiState, mode: LayoutMode)
}
```

Three methods, minimal:

- **`attach(onAction)`** — wire static handlers (click, long-click,
  touch) once. Store the `onAction` for re-invocation.
- **`detach()`** — release references (`onAction = null`,
  `firstRender = true` for re-attach).
- **`render(state, mode)`** — called per state-change with the
  current state and the matching `LayoutMode`.

## 6. Multi-backend pattern (R.10)

`KeyboardLayoutManager` keeps a **list** of active backends:

```kotlin
class KeyboardLayoutManager(
    private val scope: CoroutineScope,
    private val onAction: (Action) -> Unit,
) {
    private val activeBackends = mutableListOf<RenderBackend>()
    private var currentState: DictateUiState? = null

    fun attachBackend(backend: RenderBackend) {
        backend.attach(onAction)
        activeBackends += backend
        currentState?.let { backend.render(it, computeLayoutMode(it)) }
    }

    fun detachBackend(backend: RenderBackend) {
        backend.detach()
        activeBackends -= backend
    }

    fun onStateChanged(state: DictateUiState) {
        currentState = state
        val mode = computeLayoutMode(state)
        activeBackends.forEach { it.render(state, mode) }
    }

    private fun computeLayoutMode(state: DictateUiState): LayoutMode = when (state.viewMode) {
        ViewMode.KEYBOARD -> LayoutCatalog.forKeyboard(state)
        ViewMode.WIDGET, ViewMode.HOVER -> LayoutCatalog.OVERLAY_5BUTTON
    }
}
```

In KEYBOARD mode, both `ImeViewBackend` (the main keyboard) **and**
`ContentAreaController` (the container visibility) are attached
simultaneously. Each render-tick fans out to all active backends.

> [!NOTE]
> The example in Spec 2 §4 shows a single-backend skeleton for
> teaching purposes. The production form (Spec 2 §4.1) is
> multi-backend. The architecture-doc tutorial is the
> single-source-of-truth; in code, the manager keeps a list.

### 6.1 Third render host — the PC-dictation Activity

The multi-backend list has a **third** attach point besides the IME view
and the floating overlay widget: `PcDictationActivity`
(`core/PcDictationActivity.kt`, pc-dictation-activity). It is a full-screen
"remote keyboard for the PC" — the Dictate keyboard grid (the same
`activity_dictate_keyboard_view` layout, reused verbatim via `<include>`)
with the session history on top.

Key facts:

- **It reuses `ImeViewBackend` unchanged** — the class is host-agnostic
  (it takes a `MotionSurface` + a `LogicalButtonId → View` map + the
  service-owned `ModuleServices`, none IME-specific). The Activity builds
  its own view instances and its own backend, then calls
  `binder.keyboardLayoutManager.attachBackend(...)`. Both the IME's and the
  Activity's backends are `BackendType.IME_VIEW`, so both receive
  `catalog.forKeyboard(state)`. The `attachBackend` duplicate guard is
  by object identity, so two distinct backends coexist fine (a
  bound-but-hidden IME view stays attached while the Activity is up — both
  render the one live state; only the Activity's is visible).

- **Everything diverts to the PC (`features.pcOnly`).** There is no local
  `InputConnection`. While foregrounded the Activity pushes
  `SetPcOnly(true)`; every pipeline terminal then diverts to the paired PC
  source-independently (`WindowsAutoSend.shouldDivertToPc(source, sp, pcOnly)`),
  and a failed dispatch surfaces in the Activity (error banner + retry keyed
  on `DispatchNotice.Error.sessionId`) instead of a local pending part.

- **Two foreground-host binder registrations, with precedence over the
  IME.** A headless recording cannot resolve its `JobRequest` on the
  service-side default resolver (it throws for a fresh recording), and live
  keys must reach the PC, so the Activity registers a `PipelineConfigResolver`
  (an `ImePipelineConfigResolver`, snapshotted at the RECORD send-tap) and a
  PC-only `KeyboardActionDispatcher` via dedicated `delegateForeground*`
  slots on the `LocalBinder`. The consumer lambdas prefer the foreground
  slot and fall back to the IME's when it is cleared (`onStop`) — the IME's
  own registrations are never overwritten.

Owner ADR: [ADR-0027 — pc-dictation-activity](../../decisions/0027-pc-dictation-activity.md) (Accepted).

## 7. `SlotRenderer.applySlotToView` (F-7 / DRY)

The shared helper:

```kotlin
// app/src/main/java/net/devemperor/dictate/keyboard/render/SlotRenderer.kt
fun applySlotToView(
    slot: ButtonSlot,
    view: View,
    state: DictateUiState,
    ctx: Context,
): Boolean {
    val visible = slot.visibilityPredicate(state)
    view.visibility = if (visible) View.VISIBLE else View.GONE
    view.isEnabled = slot.enabledResolver(state)
    view.alpha = slot.alphaResolver(state)
    if (view is MaterialButton) {
        slot.iconResolver(state)?.let { view.icon = ContextCompat.getDrawable(ctx, it) }
        slot.textResolver(state)?.let { view.text = it }
    }
    return visible
}
```

The function is the **only** code path that translates a
`ButtonSlot`'s resolvers into Android view properties. Both
`ImeViewBackend` and `OverlayBackend` call it. A new slot property
(`contentDescription`, `tint`) is added once here and benefits
both backends.

Click-listeners are NOT wired by this function — they're
backend-specific (IME wires once in `wireStaticHandlers`, Overlay
keeps its own approach to play well with the drag handler).

## 8. MotionScene XML

`res/xml/motion_scene_keyboard.xml` carries 5 ConstraintSets for
the KEYBOARD modes:

- `@id/two_row_state` — base definition
- `@id/single_row_state` (derives from two_row_state)
- `@id/two_row_send_mode_state` (derives from two_row_state)
- `@id/single_row_send_mode_state` (derives from single_row_state)
- `@id/reprocess_staging_state` (derives from two_row_state)

Plus `Transition`s between them. Spec 2 §7.1 is the canonical XML.

### 8.1 `motion:visibilityMode="ignore"` on every state-driven button

```xml
<Constraint android:id="@+id/resend_btn"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    motion:layout_constraintTop_toTopOf="@+id/record_pulse_layout"
    motion:layout_constraintEnd_toStartOf="@+id/backspace_btn">
    <PropertySet motion:visibilityMode="ignore" />
</Constraint>
```

Without `visibilityMode="ignore"`, MotionScene would animate the
button's visibility on every transition — overlaying the per-slot
`visibilityPredicate`. The visual result: buttons flickering
in/out during transitions. The `ignore` mode tells MotionLayout
"do not touch the visibility property; the rendering code owns it".

Forbidden pattern (k) in [`forbidden-patterns.md`](forbidden-patterns.md):
omitting `visibilityMode="ignore"` on a state-driven button.

### 8.2 Flat hierarchy (L2)

Buttons are **direct children of the MotionLayout root**. The
pre-refactor design wrapped them in `action_row` + `input_row`
containers; that wrapping is removed (Spec 2 §7.1 + §11.8). The
constraint-chains in the XML reproduce the visual row-layout
without the wrapper indirection.

Forbidden pattern (d): re-parenting buttons across modes.
MotionLayout transitions don't re-parent — they change
ConstraintSets only.

## 9. `firstRender` flag (R.14)

```kotlin
private var firstRender: Boolean = true

override fun attach(onAction: (Action) -> Unit) {
    this.onAction = onAction
    wireStaticHandlers()
}

override fun detach() {
    this.onAction = null
    firstRender = true   // reset for next attach (view-recreate semantics)
}

override fun render(state: DictateUiState, mode: LayoutMode) {
    mode.sceneStateId?.let { sceneId ->
        if (firstRender || !state.layout.animationsEnabled) {
            motionLayout.jumpToState(sceneId)
        } else {
            motionLayout.transitionToState(sceneId)
        }
    }
    firstRender = false
    // … apply slots via applySlotToView …
}
```

Without `firstRender`, every fresh inflate (rotation, theme-change)
would animate from the initial MotionScene state (`@id/two_row_state`)
to the actual mode — a visible 250ms jump. With `firstRender`, the
first render `jumpToState`s directly to the right ConstraintSet.

## 10. `ImeViewBackend.render` flow

```kotlin
override fun render(state: DictateUiState, mode: LayoutMode) {
    require(mode.backend == BackendType.IME_VIEW)
    stateRef = state                       // store for click-listeners
    modeRef = mode

    // 1. MotionScene transition (jump or animate)
    mode.sceneStateId?.let { sceneId ->
        if (firstRender || !state.layout.animationsEnabled) {
            motionLayout.jumpToState(sceneId)
        } else {
            motionLayout.transitionToState(sceneId)
        }
    }
    firstRender = false

    // 2. Per-slot visibility/icon/text/enabled/alpha
    mode.rows.flatMap { it.slots }.forEach { slot ->
        val view = buttonViews[slot.logicalId]
            ?: error("No view registered for ${slot.logicalId} in ImeViewBackend.buttonViews")
        applySlotToView(slot, view, state, ctx)
    }

    // 3. RecordingAnimationController (BorderGlow + PulseLayout, Spec 2 §11.5)
    recordingAnimationController.onState(state)
}
```

A missing view-map entry is a hard `error(...)` — Spec 2 §6 calls
this the "silent-skip protection" (Spec 2 cites it as `Silent-Skip-Schutz`). Without it,
a newly added `LogicalButtonId` without a matching view would
silently fail to render at runtime; with the error, build/run-time
flags the omission.

## 11. `OverlayBackend.render`

Renders the `OVERLAY_5BUTTON` LayoutMode on a `TYPE_APPLICATION_OVERLAY`
window (Spec 3 §4). Same `applySlotToView` helper, different view
hierarchy (the overlay XML at Spec 3 §3.2). Send is disabled when
`state.viewMode == ViewMode.HOVER` via the slot's
`enabledResolver`.

The shared overlay layout is the basis for ADR-0005's `OVERLAY_5BUTTON`
mode-merge (one layout, two ViewModes).

## 12. `ContentAreaController` (R.10 / Issue 2.1.15 Option B)

A second `RenderBackend` for the content-area containers:

```kotlin
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
    }
}
```

This backend runs **in parallel** with `ImeViewBackend` whenever
KEYBOARD is active. The reason for splitting it out: content-area
visibility is conceptually a per-axis decision (one of three
containers visible), not a per-button decision. Modelling it as a
per-slot `visibilityPredicate` would force every main-button slot
to include a `state.layout.contentArea == ContentArea.MAIN_BUTTONS`
gate — duplicate code in 9+ slots.

## 13. Render-path cutover (post-Epic, 2026-05-17)

The teaching material above describes the `RenderBackend` pattern.
This section records its **cutover outcome**: as of the Epic
`dictate-cutover-completion` (Theme-C-R), `RenderBackend` is the
**sole** render driver and the legacy render controllers are gone.

### 13.1 What changed

- **4 legacy render controllers deleted** — `MainButtonsController`,
  `RecordingUiController`, `KeyboardUiController`, `KeyboardStateManager`
  (and the legacy `LanguageController`). Before the Epic these were
  attached **in parallel** to `RenderBackend` (the INT-1
  parallel-dormant anti-pattern, render layer). They no longer exist;
  every remaining mention in the source tree is a historical KDoc /
  `@see` / XML anchor, not a live dependency.
- **`RenderBackend` is the sole render driver** — `doubleWriteCount == 0`
  (no axis written by both the new backend and a legacy controller).
  The `KeyboardLayoutManager` multi-backend list (§6) is the only
  render fan-out.
- **~16 controller behaviour-groups ported to `RenderBackend` owners** —
  `ImeViewBackend`, `SpecialTouchHandlerInstaller`,
  `ContentAreaController`, `PromptVisibilityController`,
  `OverlayResetHandler`, the extracted `EditBarController` /
  `EmojiController` / `OverlayCharactersController`,
  `QwertzRecordingController`, `PipelineStepRowRenderer`, and the
  `EditNumbersAnimator` helper. The per-behaviour-group → owner mapping
  is the **SoT table** in the Epic's
  [`research/render-path-cutover.md` §3 + §11](../../plans/2026-05-15%20-%20dictate-cutover-completion/research/render-path-cutover.md)
  — **not duplicated here** (SSoT: the spec is canonical for the 16-row
  map; this doc points at it).

### 13.2 The staged-cutover safety-net mechanic

The cutover used a **build-but-dormant → `RenderGate`-armed → atomic
per-axis flip → delete** mechanic (the safe answer to RR-1 "silent
listener overwrite" / RR-2 "blank UI from premature drive-removal"):

1. **CR1–CR3** — the new owners are attached but **dormant** behind a
   `state/render/RenderGate` (they compute but do not write the view).
2. **CR4** — `RenderGate.arm()` flips owners live **per axis,
   atomically**; the legacy drive call for that axis is removed in the
   **same chunk** (never both wired at once — RR-1).
3. **CR-DEL** — the legacy controllers are deleted only after the
   mandatory RR-3 per-class responsibility-trace is GREEN (every legacy
   responsibility maps to a verified-present, IME-attached new owner).

`core/audit/VisibilityWriteAuditLogger` is the strict-mode guard for
RR-2: it detects double / zero writes per visibility axis during the
staged window, so a premature drive-removal or a missed owner-attach
is caught before the delete.

> [!NOTE]
> The `RenderGate` / `VisibilityWriteAuditLogger` class headers narrate
> the RR-2 staged-cutover rationale at paragraph length — that is the
> *mechanic's own* rationale (legitimately inline), and it does not
> duplicate this section: this section is the architecture-level
> outcome, the headers are the per-class "why". The SoT for the 16-row
> behaviour-group map remains `render-path-cutover.md` §3.

Decision trail:
[ADR-0004](../../decisions/0004-ui-layout-catalog-motionlayout.md)
(RenderBackend rendering side) and
[ADR-0005 Decision History 2026-05-17](../../decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md#decision-history)
(the IME recording-trigger flip + render-path cutover).

## 14. Information Gaps

(no gaps known at this time — Spec 2 §3 + §4 + §5 + §6 + §7 + §8 cover
the rendering layer; the post-cutover owner map is the SoT in
`render-path-cutover.md` §3)

## 15. Change History

### 2026-05-17 — Render-path cutover outcome added (Epic dictate-cutover-completion)

- **Trigger:** Phase-4.6 documentation update for the Epic
  `dictate-cutover-completion`. The pre-Epic doc described the
  `RenderBackend` pattern but did not record that the legacy
  controllers are deleted and `RenderBackend` is the sole driver.
- **Reasoning:** §13 records the cutover outcome (controllers deleted,
  sole driver, the staged `RenderGate` / `VisibilityWriteAuditLogger`
  safety-net) and points at `render-path-cutover.md` §3 as the SoT for
  the 16-row behaviour-group → owner map (no duplication, per the SSoT
  rule). The §1.1 pre-refactor narrative is intentionally left as-is —
  it is the historical motivation; §13 is the post-cutover present.

### 2026-05-14 — Initial draft

- **Trigger:** Block 0 architecture anchor.
- **Reasoning:** Captures the rendering layer from Spec 2 + ADR-0004
  in tutorial form. The shared `applySlotToView` (F-7), the
  multi-backend (R.10), and the `firstRender` flag (R.14) are
  the architectural-iteration fixes that landed during Spec-2
  review.

## 16. References

- [ADR-0004 — ui-layout-catalog-motionlayout](../../decisions/0004-ui-layout-catalog-motionlayout.md)
- [Spec 2 §3 — ButtonSlot / RowDescriptor / LayoutMode](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md)
- [Spec 2 §4 + §4.1 — KeyboardLayoutManager + multi-backend](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md)
- [Spec 2 §5 + §5.1 — RenderBackend + applySlotToView](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md)
- [Spec 2 §6 — ImeViewBackend](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md)
- [Spec 2 §7 — MotionScene XML](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md)
- [Spec 2 §8 — LayoutCatalog](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md)
- [`wiring-ui.md`](wiring-ui.md)
- [`adding-a-button.md`](adding-a-button.md)
- [`triangle-fsm.md`](triangle-fsm.md)
