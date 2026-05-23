# Capability Inventory: Main-Button-Area of the Dictate IME

**Date:** 2026-05-07
**Research agent:** general-purpose, very thorough
**Trigger:** Preparation of the layout-refactor plan — we need a complete inventory of what the main-button-area can do today, so that no functionality is lost during the refactor (removal of `action_row`/`input_row` in favour of a flat MotionLayout or a flat ConstraintLayout).
**Related plan:** [keyboard-layout-refactor.md](../keyboard-layout-refactor.md)

---

## Objective of the Research

Complete inventory of the main-button-area along six axes: buttons, layout modes, runtime states, state transitions, animations, responsibility distribution. All statements are verified against concrete file lines.

**Investigation scope:** the layout container `main_buttons_cl` in `app/src/main/res/layout/activity_dictate_keyboard_view.xml` together with its two nested ConstraintLayouts (`action_row`, `input_row`) and all associated controller classes.

---

## 1. Buttons & Their Behavioural Idiosyncrasies

### Layout structure (XML: `activity_dictate_keyboard_view.xml`, L. 12-172)

**main_buttons_cl (LinearLayout, vertical):**
- Parent: LinearLayout (height: wrap_content, padding: 72dp Start / 16dp End)
- Two children: `action_row` (ConstraintLayout) + `input_row` (ConstraintLayout)

#### action_row (L. 26-105)

| Button | ID | Type | Layout width | Visibility (XML) | Listener |
|--------|----|----|---------------|-------------------|----------|
| record_pulse_layout (wrapper) | `record_pulse_layout` | PulseLayout | 0dp (MATCH_CONSTRAINT) | Visible | Click: onRecordClicked; LongClick: onRecordLongClicked |
| record_btn (content) | `record_btn` | MaterialButton | match_parent | — | (via wrapper) |
| resend_btn | `resend_btn` | MaterialButton | wrap_content | gone (L. 66) | Click: onResendClicked; LongClick: onResendLongClicked |
| backspace_btn | `backspace_btn` | MaterialButton | wrap_content | Visible | Click: onBackspaceClicked; LongClick/Touch: BackspaceSwipeHandler |
| audio_focus_btn | `audio_focus_btn` | MaterialButton | wrap_content | gone (L. 99) | Click: audioFocusClickListener |

**Icon/Drawables:**
- **record_btn**: text `@string/dictate_record` (14sp), CompoundDrawables: mic_20 (Start) + folder_open_20 (End)
- **resend_btn**: foreground icon `ic_outline_change_circle_24`, minWidth=0dp
- **backspace_btn**: foreground icon `ic_baseline_keyboard_backspace_24`, minWidth=56dp
- **audio_focus_btn**: foreground icon `ic_baseline_volume_off_24` (XML), minWidth=56dp

#### input_row (L. 108-170)

| Button | ID | Type | Layout width | Visibility (XML) | Listener |
|--------|----|----|---------------|-------------------|----------|
| trash_btn | `trash_btn` | MaterialButton | wrap_content | gone (L. 123) | Click: onTrashClicked |
| space_btn | `space_btn` | MaterialButton | 0dp (MATCH_CONSTRAINT) | Visible | Touch: CursorSwipeTouchHandler |
| pause_btn | `pause_btn` | MaterialButton | wrap_content | gone (L. 150) | Click: onPauseClicked |
| enter_btn | `enter_btn` | MaterialButton | wrap_content | Visible | Click: onEnterClicked; LongClick: show overlay; Touch: EnterOverlayHandler |

**Icon/Drawables:**
- **trash_btn**: foreground icon `ic_baseline_delete_24`, minWidth=0dp
- **space_btn**: text button, no icon (drawables only during swipe animation)
- **pause_btn**: foreground icon `ic_baseline_pause_24`, minWidth=0dp
- **enter_btn**: foreground icon `ic_baseline_subdirectory_arrow_left_24`, minWidth=56dp

### Click handlers & animations

**MainButtonsController** (`MainButtonsController.kt`, L. 76-259):

| Button | Click handler | Long-click handler | Special touch |
|--------|--------------|-------------------|--------------|
| record_btn | `recordClickListener` (L. 76) → callback.onRecordClicked() | L. 163-167 → callback.onRecordLongClicked() | — |
| resend_btn | L. 170 → callback.onResendClicked() | L. 174 → callback.onResendLongClicked() | — |
| backspace_btn | L. 181 → callback.onBackspaceClicked() | L. 185 → callback.onBackspaceLongClicked() | BackspaceSwipeHandler (L. 189-194) |
| trash_btn | L. 197 → callback.onTrashClicked() | — | — |
| space_btn | CursorSwipeTouchHandler (L. 203-231): onTap = space, onSwipe = cursor-move ±1 | — | Touch (L. 225-232) |
| pause_btn | L. 235 → callback.onPauseClicked() | — | — |
| enter_btn | L. 245 → callback.onEnterClicked() | L. 249 → show overlay (L. 251) | EnterOverlayHandler (L. 254-259) |
| audio_focus_btn | L. 242 → audioFocusClickListener (L. 87-90) | — | — |
| edit_numbers_btn | L. 102 → callback.onSmallModeToggled() | L. 112 → callback.onSingleRowModeToggled() (L. 114) | — |

### Special animations

1. **record_pulse_layout (PulseLayout):**
   - File: `app/src/main/java/net/devemperor/dictate/widget/PulseLayout.kt`
   - XML attributes (L. 38-42): pulseCount=3, pulseDuration=2000ms, pulseStartAlpha=0.3, pulseMaxRadiusFactor=1.4, pulseStyle=fill
   - Methods: `startPulse()` (L. 105), `pausePulse()` (L. 116), `resumePulse()` (L. 120), `stopPulse()` (L. 124)
   - **Critical:** the pulse **wrapper** is re-parented, NOT the bare record_btn — otherwise the animation breaks. Documented in `KeyboardLayoutModeController` plan L. 185.

2. **edit_numbers_btn bounce animation:**
   - File: MainButtonsController.kt, L. 452-477
   - Visual feedback for the SingleRowMode toggle: 8dp translationX bounce (±8dp, ~200ms total, returns to 0)
   - Respects the `Pref.Animations` flag

3. **RecordingAnimation (BorderGlowAnimation):**
   - Interface: `RecordingAnimation.kt`
   - Implementation: `BorderGlowAnimation.kt`
   - Lifecycle: prepare() → start() → pause()/resume() → cancel()
   - Shows during recording: send icon (left) + amplitude bars (centre) + timer (right)
   - Wired in RecordingUiController: `recordingAnimation.start()` (L. 162), `.pause()` (L. 190), `.cancel()` (L. 124)

---

## 2. Layout Modes

### Two-Row (standard, default)

**Configuration:**
- action_row: visible, horizontal chain `[record_pulse]—[resend]—[backspace]`
- input_row: visible, horizontal chain `[trash]—[space]—[pause]—[enter]`
- audio_focus_btn: gone (only visible in SingleRowMode)

**Margins/Constraints:**
- action_row: children with 8dp marginEnd/marginStart spacing (XML L. 37, 62, 77, etc.)
- input_row: marginBottom=16dp (L. 112)

**Source:** KeyboardLayoutModeController, L. 47-50:
```kotlin
private val csTwoRowAction = ConstraintSet().apply { clone(views.actionRow) }
private val csTwoRowInput = ConstraintSet().apply { clone(views.inputRow) }
```

### Single-Row (enabled by Pref.SingleRowMode)

**Programmatic reconfiguration:**
- File: KeyboardLayoutModeController.kt, L. 202-272 (`buildSingleRowConstraintSet()`)
- **Chain order:** `[trash]—[record_pulse]—[space]—[pause]—[backspace]—[enter]—[resend]—[audio_focus_btn]`
- space_btn: 0dp width (MATCH_CONSTRAINT), fills the remaining space
- All others: wrap_content
- Margins: 4dp (L. 235, mirrored from the edit-bar XML)
- Vertical: all buttons TOP/BOTTOM-aligned to the action_row parent

**Re-parenting (L. 183-191):**
- Moved views: [record_pulse_layout, space_btn, backspace_btn, enter_btn, resend_btn, trash_btn, pause_btn]
- Original parents are captured in the `originalParents` map (L. 66-74), so that the revert returns each button to its native row
- **Bug fix 2026-05-07:** previously the action_row natives (record_pulse, resend, backspace) were wrongly stuffed into input_row on revert

**Persistence:** Pref.SingleRowMode (DictatePrefs.kt, L. 34)

**Lifecycle asymmetry (KeyboardStateManager L. 134-138):**
- audio_focus_btn: stays permanently in action_row, only visibility follows the mode
- Edit-bar copy: always visible

### Other modes

No further layout modes besides TWO_ROW / SINGLE_ROW. There is, however, the **ContentArea** (axis 3), which is orthogonal:

**ContentArea enum** (ContentArea.kt, L. 4-8):
```kotlin
enum class ContentArea {
    MAIN_BUTTONS,   // action_row + input_row
    QWERTZ,         // QWERTZ keyboard (qwertz_keyboard_container)
    EMOJI_PICKER    // Emoji picker (emoji_picker_cl)
}
```

---

## 3. Runtime States (Other Than Layout Mode)

### RecordingState

**Definition:** RecordingState.kt, L. 10-18
```kotlin
sealed class RecordingState {
    object Idle : RecordingState()
    data class Preparing(val useBluetooth: Boolean) : RecordingState()
    data class Active(val useBluetooth: Boolean) : RecordingState()
    object Paused : RecordingState()
}
```

**Owner:** RecordingStateController (`var state: RecordingState`)
**Reader:** RecordingUiController (implements `RecordingStateController.Callback`)
**Query lambda in KeyboardStateManager (L. 532-533):**
```kotlin
() -> recordingStateController != null && recordingStateController.getState() instanceof RecordingState.Active,
() -> recordingStateController != null && recordingStateController.getState() instanceof RecordingState.Paused,
```

### PipelineUiState

**Definition:** PipelineUiState.kt, L. 13-54
```kotlin
sealed class PipelineUiState {
    object Idle : PipelineUiState()
    object Preparing : PipelineUiState()
    data class Running(...) : PipelineUiState()
    data class ReprocessStaging(...) : PipelineUiState()
}
```

**Owner:** KeyboardUiController (`var state: PipelineUiState`)
**Query lambda in KeyboardStateManager (L. 538-541):**
```kotlin
() -> uiController != null && uiController.getState() instanceof PipelineUiState.Running,
() -> uiController != null && uiController.getState() instanceof PipelineUiState.ReprocessStaging
```

### ContentArea

**Definition:** ContentArea.kt, L. 4-8 (see above)
**Owner:** KeyboardStateManager (`var contentArea: ContentArea`)
**Setter:** `setContentArea(area)` (L. 135-138)

### SmallMode

**Definition:** Pref.SmallMode (DictatePrefs.kt, L. 33)
**Owner:** KeyboardStateManager (`var isSmallMode: Boolean`, L. 102)
**Setter:** `setSmallMode(enabled)` (L. 140-146)
**Persistence:** SharedPreferences

**Special rule (L. 142-144):**
```kotlin
if (enabled && contentArea != ContentArea.MAIN_BUTTONS) {
    contentArea = ContentArea.MAIN_BUTTONS
}
```
SmallMode precedence: auto-switch to MAIN_BUTTONS when SmallMode is enabled.

### Pref.SingleRowMode

**Definition:** DictatePrefs.kt, L. 34
**Persistence:** SharedPreferences
**Reader:** KeyboardLayoutModeController.init (L. 100):
```kotlin
setSingleRowMode(sp.get(Pref.SingleRowMode), animate = false)
```

### Pref.Animations

**Definition:** DictatePrefs.kt, L. 32
**Respected in:**
- MainButtonsController.animateSmallModeToggle (L. 425) — check before animate()
- MainButtonsController.animateEditNumbersBounce (L. 453) — early return when false
- KeyboardLayoutModeController.setSingleRowMode (L. 123) — gate TransitionManager
- RecordingUiController.applyActiveState (L. 161) — gate recordingAnimation.start()
- RecordingUiController.applyIdleState (L. 123) — gate recordingAnimation.cancel()
- RecordingUiController.applyPausedState (L. 189) — gate recordingAnimation.pause()

### Visibility matrix (main-button-area buttons only)

Reduced to the **relevant** state combinations (ContentArea = MAIN_BUTTONS, SmallMode = false):

| Button | Idle | Recording | Paused | ReprocessStaging | Pipeline-Running |
|--------|------|-----------|--------|------------------|------------------|
| record_pulse_layout | VISIBLE (PulseLayout inactive) | VISIBLE (pulse active) | VISIBLE (pulse paused) | VISIBLE (pulse inactive) | VISIBLE (pulse inactive) |
| record_btn | Enabled, text = lang label | Enabled, text = "Send" | Enabled, text = "Send" | Enabled (depends on parent state) | Overridden by RecordingUiController |
| resend_btn | VISIBLE (iff last audio exists) | GONE | GONE | GONE | GONE |
| backspace_btn | VISIBLE | VISIBLE | VISIBLE | VISIBLE | VISIBLE |
| trash_btn | GONE | VISIBLE | VISIBLE | VISIBLE | VISIBLE |
| space_btn | VISIBLE | VISIBLE | VISIBLE | VISIBLE | VISIBLE |
| pause_btn | GONE | VISIBLE | VISIBLE | VISIBLE (disabled, alpha 0.4) | GONE |
| enter_btn | VISIBLE | VISIBLE | VISIBLE | VISIBLE | VISIBLE |
| audio_focus_btn | GONE (only SingleRow) | GONE (only SingleRow) | GONE (only SingleRow) | GONE (only SingleRow) | GONE (only SingleRow) |

**Special cases:**

1. **Pause button staging mode (L. 187-189, KeyboardStateManager):**
```kotlin
views.pauseButton.visibility = if (isActive || isStaging) View.VISIBLE else View.GONE
views.pauseButton.isEnabled = isActive
views.pauseButton.alpha = if (isActive) 1.0f else 0.4f
```

2. **Resend button (RecordingUiController.kt, L. 137, 158):**
```kotlin
resendButton.visibility = if (getLastAudioFileExists()) View.VISIBLE else View.GONE  // Idle
resendButton.visibility = View.GONE  // Active
```

3. **SmallMode (L. 140):** when enabled → `views.mainButtonsClTyped.visibility = View.GONE` (the entire container)

---

## 4. State Transitions & Triggers

### Layout-mode change (Two-Row ↔ Single-Row)

**Trigger:** long-press on edit_numbers_btn

**Call chain (DictateInputMethodService.java, L. 2639-2661):**
1. Pref.SingleRowMode flip + persist (L. 2652-2654)
2. layoutModeController.setSingleRowMode(next, animate=true) (L. 2656)
3. mainButtonsController.animateEditNumbersBounce() (L. 2659)

**Animated by:** TransitionManager.beginDelayedTransition (iff Pref.Animations) (KeyboardLayoutModeController L. 124)

**Guard:** SmallMode precedence (L. 2646-2651) — the whole animation is invisible when SmallMode=true, but the pref still persists

### ContentArea change

**Trigger:** clicks on edit_buttons_keyboard_ll:
- edit_emoji_btn → ContentArea.EMOJI_PICKER
- edit_keyboard_btn (long-press) → ContentArea.QWERTZ
- etc.

**Setter:** KeyboardStateManager.setContentArea (L. 135) → applyVisibility()

**Side effect:** applyVisibility() calls layoutModeController.refresh() (L. 168) to re-apply SingleRowMode

### SmallMode change

**Trigger:** click on edit_numbers_btn

**Call chain (DictateInputMethodService L. 2631-2636):**
1. Pref.SmallMode flip + persist (L. 2633)
2. stateManager.setSmallMode(newSmallMode) (L. 2634)
3. mainButtonsController.animateSmallModeToggle(true) (L. 2635)

**Side effect:** when SmallMode=true → ContentArea forced to MAIN_BUTTONS

### RecordingState change

**Trigger:** the recording manager (RecordingStateController)

**State transitions:**
- Idle → Preparing (Bluetooth init) or → Active (immediately, if no BT)
- Active → Paused (long-press pause)
- Paused/Active → Idle (Stop/Cancel)

**UI hook:** RecordingUiController.onStateChanged (L. 51-60) → stateManager.refresh()

**Pulse animation wired in line 162 (start) & 190 (pause):**
```kotlin
if (isAnimationEnabled()) {
    recordingAnimation.start()  // BorderGlowAnimation → record_pulse_layout.startPulse()
}
```

---

## 5. Animations & Performance-Relevant Details

### PulseLayout (record_pulse_layout)

**File:** `app/src/main/java/net/devemperor/dictate/widget/PulseLayout.kt`, L. 1-142

**Lifecycle:**
- `startPulse()` (L. 105): ValueAnimator 0→1, repeat INFINITE, LinearInterpolator, duration=2000ms
- `pausePulse()` (L. 116): animator.pause() (API 24+)
- `resumePulse()` (L. 120): animator.resume()
- `stopPulse()` (L. 124): animator.cancel(), animator=null, invalidate()

**Draw logic (onDraw, L. 79-103):**
- Multiple concentric circles (pulseCount=3)
- Fades out with progress (alpha = (1-progress) * pulseStartAlpha * 255)
- Radius computation: minRadius + (maxRadius - minRadius) * progress

### BorderGlowAnimation (record_btn visual during recording)

**File:** `BorderGlowAnimation.kt`

**Start (L. 62-93):**
- Save original button state
- Create AmplitudeVisualizerDrawable
- Clear text + drawables
- Set visualizer as foreground

**Pause handling (L. 95-99):**
- isPaused = true
- applyBackgroundLevel(0.12f)

### TransitionManager.beginDelayedTransition()

**Wired in:** KeyboardLayoutModeController.setSingleRowMode (L. 124)

**Effect:** fade-out/fade-in (AutoTransition default) for the view-hierarchy change (re-parent input_row buttons)

**Gated by:** Pref.Animations

**Scene root:** mainButtonsClTyped (LinLay parent of action_row + input_row)

---

## 6. Responsibility Distribution (Owners & SSOT)

### Persistent state (SharedPreferences)

| Key | Owner (write) | Reader | Type |
|-----------|-------------|--------|-----|
| Pref.SingleRowMode | Service (onSingleRowModeToggled) | KeyboardLayoutModeController.init | Boolean |
| Pref.SmallMode | Service (onSmallModeToggled) | KeyboardStateManager (setter) | Boolean |
| Pref.Animations | (Settings) | Multiple consumers | Boolean |
| Pref.AudioFocus | Service (onAudioFocusToggled) | RecordingStateController.startRecording | Boolean |
| Pref.ResendButton | (Settings) | RecordingUiController (lambda) | Boolean |

### Runtime state

| State variable | Owner | Mutation | Reader |
|---|---|---|---|
| KeyboardStateManager.contentArea | KeyboardStateManager | setContentArea() | applyContentAreaVisibility() |
| KeyboardStateManager.isSmallMode | KeyboardStateManager | setSmallMode() | applyVisibility() |
| RecordingStateController.state | RecordingStateController | setState() (internal) | lambdas in ctor |
| KeyboardUiController.state | KeyboardUiController | updatePipelineState() | lambdas in ctor |

### Visibility computation

**The sole authoritative source:** KeyboardStateManager.applyVisibility() (L. 158-169)

**Three sub-functions:**
1. applyContentAreaVisibility() (L. 171-181)
2. applyRecordingControlsVisibility() (L. 183-192)
3. applyPromptsVisibility() (L. 194-224)

**Callbacks of the sub-functions (all lambdas from ctor):**
- isRecording()
- isPaused()
- isPipelineRunning()
- isRewordingEnabled()
- isPipelineProgressVisible()
- isReprocessStaging()

### Layout-mode application

**Owner:** KeyboardLayoutModeController

**Methods:**
- setSingleRowMode(enabled, animate) (L. 115-140) — main entry point
- rehome(toSingleRow) (L. 183-191) — re-parent buttons
- refresh() (L. 150-152) — re-apply the current pref without animation

**Constraints application:**
- csTwoRowAction.applyTo(views.actionRow)
- csTwoRowInput.applyTo(views.inputRow)
- csSingleRow.applyTo(views.actionRow)

**Input-row visibility (L. 133):**
```kotlin
views.inputRow.visibility = if (enabled) View.GONE else View.VISIBLE
views.audioFocusButtonInRow.visibility = if (enabled) View.VISIBLE else View.GONE
```

### Bridge between runtime state and UI update

**Primary bridge:** KeyboardStateManager.refresh()

**Called from:**
1. RecordingStateController.Callback.onStateChanged (RecordingUiController L. 59)
2. KeyboardUiController.updatePipelineState (L. 153)
3. DictateInputMethodService (Service) when explicitly needed

**Cascade effect:**
```
stateManager.refresh()
  → applyVisibility()
    → applyRecordingControlsVisibility()
    → applyPromptsVisibility()
    → layoutModeController.refresh()  // re-apply SingleRowMode
      → setSingleRowMode(sp.get(Pref.SingleRowMode), animate=false)
```

**Secondary bridge:** KeyboardStateManager.setLayoutModeController(controller)
- Setter injection (L. 115-117)
- Breaks the circular dependency
- Inject after MainButtonsController construction (DictateInputMethodService L. 589)

---

## Summary: What the Refactor Must Preserve

1. **The pulse animation of record_pulse_layout:** the **wrapper** re-parenting logic is critical. Moving only the inner button breaks the animation.

2. **The click-handler chain:**
   - record_btn → recordClickListener → callback.onRecordClicked()
   - All 9 buttons have distinct handlers
   - Callbacks centralised in the MainButtonsController.Callback interface

3. **The visibility matrix:**
   - pause_btn must be disabled+alpha0.4 in ReprocessStaging
   - resend_btn must be GONE in Recording
   - trash_btn must be VISIBLE in Recording+Staging
   - Authoritative source: KeyboardStateManager.applyVisibility() — no decentralised visibility calls

4. **Layout-mode switching:**
   - The originalParents map must be preserved (prevents the 2026-05-07 bug) — *or replaced by a better pattern*
   - Re-parenting must be idempotent (multiple refresh() calls per frame safe)
   - audio_focus_btn is special: stays in action_row, only visibility changes

5. **Preferences:**
   - Pref.SingleRowMode, Pref.SmallMode, Pref.Animations must continue to be persisted + loaded
   - AudioFocus sync (Settings ↔ Service ↔ buttons) must stay live

6. **Listener pattern:**
   - The edit-bar buttons (edit_numbers_btn, edit_keyboard_btn, etc.) are **outside** main_buttons_cl
   - But their callbacks (SmallMode toggle, SingleRow toggle) affect the main-button-area
   - Decoupling via callbacks is important

## Risk List

1. **PulseLayout re-parenting fragile:**
   - Test: moving only record_btn (not the wrapper) breaks the animation
   - Mitigation: unit tests for PulseLayout.startPulse() after re-parent

2. **originalParents-map dependency:**
   - When new buttons are added to input_row, they must go into the map
   - Missing entries → the 2026-05-07 bug again

3. **KeyboardStateManager as the visibility SSOT:**
   - But RecordingUiController also makes visibility mutations (resend_btn L. 137, 158)
   - Can lead to race conditions if both mutate simultaneously
   - **Solution approach:** RecordingUiController should only report a state change; KeyboardStateManager computes visibility

4. **TransitionManager lifecycle:**
   - When `mainButtonsClTyped` (the scene root) is GONE (SmallMode), TransitionManager is inactive
   - Intended, but fragile if the refactor changes the layout hierarchy

5. **Audio-focus button lifecycle asymmetry:**
   - edit_audio_focus_btn: always visible, separate from the layout mode
   - audio_focus_btn: in action_row, visibility = SingleRowMode
   - Both must be **identically** synchronised (icon + contentDescription)
   - Wired via mainButtonsController.refreshAudioFocusIcon (L. 368-387)
   - **Risk:** if either of the two is not wired, the icon is out of sync

6. **Bounce-animation vs rotation-animation conflict:**
   - edit_numbers_btn has two animations: rotation (SmallMode, L. 426) + translationX (SingleRow, L. 461)
   - When toggled back-to-back: a translationX drift can occur
   - Mitigation present: `btn.animate().cancel()` (L. 459)

## Open Questions (Only the User Can Answer)

1. **Which breaking changes are acceptable?** E.g. re-implement the audio-focus button as a separate component instead of dual wiring?

2. **Performance requirement for the layout change?** Is a TransitionManager fade enough, or must it be faster?

3. **Will new buttons be added?** If so: change the Single-Row chain order?

4. **Is the `originalParents`-map approach sustainable long-term,** or should the "native parent" be defined in a per-button annotation? (With MotionLayout / a flat container it is obsolete anyway.)

5. **RecordingUiController.resendButton visibility vs KeyboardStateManager visibility:** who is the owner? (Currently hybrid — dangerous.)

---

## File References (for the Plan)

- XML layout: `app/src/main/res/layout/activity_dictate_keyboard_view.xml`
- Visibility/state mgmt: `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt`
- Layout mode: `app/src/main/java/net/devemperor/dictate/core/KeyboardLayoutModeController.kt`
- Buttons/handler: `app/src/main/java/net/devemperor/dictate/core/MainButtonsController.kt`
- Recording UI: `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt`
- Service (wiring): `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (L. 400-746, 2630-2687)
- Pulse widget: `app/src/main/java/net/devemperor/dictate/widget/PulseLayout.kt`
- Recording animation: `app/src/main/java/net/devemperor/dictate/widget/BorderGlowAnimation.kt`
- Preferences: `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt` (L. 12-106)
- States: `app/src/main/java/net/devemperor/dictate/core/RecordingState.kt`, `PipelineUiState.kt`, `ContentArea.kt`
