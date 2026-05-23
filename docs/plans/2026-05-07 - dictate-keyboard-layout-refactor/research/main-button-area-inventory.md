# Capability-Inventur: Main-Button-Area der Dictate-IME

**Datum:** 2026-05-07
**Recherche-Agent:** general-purpose, very thorough
**Trigger:** Vorbereitung des Layout-Refactor-Plans — wir brauchen eine vollständige Bestandsaufnahme dessen, was die Main-Button-Area heute kann, damit beim Refactor (Wegfall von `action_row`/`input_row` zu Gunsten eines flachen MotionLayouts oder eines flachen ConstraintLayouts) keine Funktionalität verloren geht.
**Verwandter Plan:** [keyboard-layout-refactor.md](../keyboard-layout-refactor.md)

---

## Zielstellung der Recherche

Vollständige Inventur der Main-Button-Area entlang sechs Achsen: Buttons, Layout-Modi, Runtime-States, State-Übergänge, Animationen, Verantwortungs-Verteilung. Alle Aussagen sind anhand konkreter Datei-Zeilen verifiziert.

**Untersuchungs-Scope:** Layout-Container `main_buttons_cl` in `app/src/main/res/layout/activity_dictate_keyboard_view.xml` mitsamt seinen zwei verschachtelten ConstraintLayouts (`action_row`, `input_row`) und allen zugehörigen Controller-Klassen.

---

## 1. Buttons & ihre Verhaltens-Eigenheiten

### Layout-Struktur (XML: `activity_dictate_keyboard_view.xml`, Z. 12-172)

**main_buttons_cl (LinearLayout, vertikal):**
- Parent: LinearLayout (Höhe: wrap_content, Padding: 72dp Start / 16dp End)
- Zwei Kinder: `action_row` (ConstraintLayout) + `input_row` (ConstraintLayout)

#### action_row (Z. 26-105)

| Button | ID | Typ | Layout-Breite | Sichtbarkeit (XML) | Listener |
|--------|----|----|---------------|-------------------|----------|
| record_pulse_layout (Wrapper) | `record_pulse_layout` | PulseLayout | 0dp (MATCH_CONSTRAINT) | Sichtbar | Click: onRecordClicked; LongClick: onRecordLongClicked |
| record_btn (Inhalt) | `record_btn` | MaterialButton | match_parent | — | (via Wrapper) |
| resend_btn | `resend_btn` | MaterialButton | wrap_content | gone (Z. 66) | Click: onResendClicked; LongClick: onResendLongClicked |
| backspace_btn | `backspace_btn` | MaterialButton | wrap_content | Sichtbar | Click: onBackspaceClicked; LongClick/Touch: BackspaceSwipeHandler |
| audio_focus_btn | `audio_focus_btn` | MaterialButton | wrap_content | gone (Z. 99) | Click: audioFocusClickListener |

**Icon/Drawables:**
- **record_btn**: Text `@string/dictate_record` (14sp), CompoundDrawables: mic_20 (Start) + folder_open_20 (End)
- **resend_btn**: Foreground-Icon `ic_outline_change_circle_24`, minWidth=0dp
- **backspace_btn**: Foreground-Icon `ic_baseline_keyboard_backspace_24`, minWidth=56dp
- **audio_focus_btn**: Foreground-Icon `ic_baseline_volume_off_24` (XML), minWidth=56dp

#### input_row (Z. 108-170)

| Button | ID | Typ | Layout-Breite | Sichtbarkeit (XML) | Listener |
|--------|----|----|---------------|-------------------|----------|
| trash_btn | `trash_btn` | MaterialButton | wrap_content | gone (Z. 123) | Click: onTrashClicked |
| space_btn | `space_btn` | MaterialButton | 0dp (MATCH_CONSTRAINT) | Sichtbar | Touch: CursorSwipeTouchHandler |
| pause_btn | `pause_btn` | MaterialButton | wrap_content | gone (Z. 150) | Click: onPauseClicked |
| enter_btn | `enter_btn` | MaterialButton | wrap_content | Sichtbar | Click: onEnterClicked; LongClick: show overlay; Touch: EnterOverlayHandler |

**Icon/Drawables:**
- **trash_btn**: Foreground-Icon `ic_baseline_delete_24`, minWidth=0dp
- **space_btn**: Text-Button, no icon (Drawables nur bei swipe animation)
- **pause_btn**: Foreground-Icon `ic_baseline_pause_24`, minWidth=0dp
- **enter_btn**: Foreground-Icon `ic_baseline_subdirectory_arrow_left_24`, minWidth=56dp

### Click-Handler & Animationen

**MainButtonsController** (`MainButtonsController.kt`, Z. 76-259):

| Button | Click Handler | Long-Click Handler | Spezial-Touch |
|--------|--------------|-------------------|--------------|
| record_btn | `recordClickListener` (Z. 76) → callback.onRecordClicked() | Z. 163-167 → callback.onRecordLongClicked() | — |
| resend_btn | Z. 170 → callback.onResendClicked() | Z. 174 → callback.onResendLongClicked() | — |
| backspace_btn | Z. 181 → callback.onBackspaceClicked() | Z. 185 → callback.onBackspaceLongClicked() | BackspaceSwipeHandler (Z. 189-194) |
| trash_btn | Z. 197 → callback.onTrashClicked() | — | — |
| space_btn | CursorSwipeTouchHandler (Z. 203-231): onTap = space, onSwipe = cursor-move ±1 | — | Touch (Z. 225-232) |
| pause_btn | Z. 235 → callback.onPauseClicked() | — | — |
| enter_btn | Z. 245 → callback.onEnterClicked() | Z. 249 → show overlay (Z. 251) | EnterOverlayHandler (Z. 254-259) |
| audio_focus_btn | Z. 242 → audioFocusClickListener (Z. 87-90) | — | — |
| edit_numbers_btn | Z. 102 → callback.onSmallModeToggled() | Z. 112 → callback.onSingleRowModeToggled() (Z. 114) | — |

### Spezielle Animationen

1. **record_pulse_layout (PulseLayout):**
   - Datei: `app/src/main/java/net/devemperor/dictate/widget/PulseLayout.kt`
   - XML-Attribute (Z. 38-42): pulseCount=3, pulseDuration=2000ms, pulseStartAlpha=0.3, pulseMaxRadiusFactor=1.4, pulseStyle=fill
   - Methoden: `startPulse()` (Z. 105), `pausePulse()` (Z. 116), `resumePulse()` (Z. 120), `stopPulse()` (Z. 124)
   - **Kritisch:** Die Pulse-**Wrapper** wird re-parented, NICHT die bare record_btn — sonst bricht die Animation. Dokumentiert in `KeyboardLayoutModeController` Plan-Z. 185.

2. **edit_numbers_btn Bounce-Animation:**
   - Datei: MainButtonsController.kt, Z. 452-477
   - Visuelle Feedback für SingleRowMode-Toggle: 8dp-translationX bounce (±8dp, ~200ms total, returns to 0)
   - Respektiert `Pref.Animations`-Flag

3. **RecordingAnimation (BorderGlowAnimation):**
   - Interface: `RecordingAnimation.kt`
   - Implementierung: `BorderGlowAnimation.kt`
   - Lifecycle: prepare() → start() → pause()/resume() → cancel()
   - Zeigt während Recording: Send-Icon (links) + Amplitude-Bars (Mitte) + Timer (rechts)
   - Wired in RecordingUiController: `recordingAnimation.start()` (Z. 162), `.pause()` (Z. 190), `.cancel()` (Z. 124)

---

## 2. Layout-Modi

### Two-Row (Standard, Default)

**Konfiguration:**
- action_row: sichtbar, horizontal chain `[record_pulse]—[resend]—[backspace]`
- input_row: sichtbar, horizontal chain `[trash]—[space]—[pause]—[enter]`
- audio_focus_btn: gone (nur visible in SingleRowMode)

**Margins/Constraints:**
- action_row: Kinder mit 8dp marginEnd/marginStart Spacing (XML Z. 37, 62, 77, etc.)
- input_row: marginBottom=16dp (Z. 112)

**Source:** KeyboardLayoutModeController, Z. 47-50:
```kotlin
private val csTwoRowAction = ConstraintSet().apply { clone(views.actionRow) }
private val csTwoRowInput = ConstraintSet().apply { clone(views.inputRow) }
```

### Single-Row (Enabled by Pref.SingleRowMode)

**Programmatische Neukonfiguration:**
- Datei: KeyboardLayoutModeController.kt, Z. 202-272 (`buildSingleRowConstraintSet()`)
- **Chain-Reihenfolge:** `[trash]—[record_pulse]—[space]—[pause]—[backspace]—[enter]—[resend]—[audio_focus_btn]`
- space_btn: 0dp width (MATCH_CONSTRAINT), füllt restlichen Platz
- Alle anderen: wrap_content
- Margins: 4dp (Z. 235, gemirror-t von Edit-Bar XML)
- Vertikal: alle Buttons TOP/BOTTOM-aligned zu action_row-Parent

**Re-Parenting (Z. 183-191):**
- Bewegte Views: [record_pulse_layout, space_btn, backspace_btn, enter_btn, resend_btn, trash_btn, pause_btn]
- Original-Parents werden in `originalParents`-Map (Z. 66-74) erfasst, sodass Revert jeden Button zur nativen Row zurückbringt
- **Bug-Fix 2026-05-07:** Vorher wurden action_row-Natives (record_pulse, resend, backspace) bei Revert fälschlicherweise in input_row gestopft

**Persistierung:** Pref.SingleRowMode (DictatePrefs.kt, Z. 34)

**Lifecycle-Asymmetrie (KeyboardStateManager Z. 134-138):**
- audio_focus_btn: bleibt permanent in action_row, nur Visibility folgt mode
- Edit-Bar copy: immer sichtbar

### Weitere Modi

Keine weiteren Layout-Modi außer TWO_ROW / SINGLE_ROW. Es gibt aber **ContentArea** (Achse 3), die orthogonal ist:

**ContentArea enum** (ContentArea.kt, Z. 4-8):
```kotlin
enum class ContentArea {
    MAIN_BUTTONS,   // action_row + input_row
    QWERTZ,         // QWERTZ keyboard (qwertz_keyboard_container)
    EMOJI_PICKER    // Emoji picker (emoji_picker_cl)
}
```

---

## 3. Runtime-States (außer Layout-Modus)

### RecordingState

**Definition:** RecordingState.kt, Z. 10-18
```kotlin
sealed class RecordingState {
    object Idle : RecordingState()
    data class Preparing(val useBluetooth: Boolean) : RecordingState()
    data class Active(val useBluetooth: Boolean) : RecordingState()
    object Paused : RecordingState()
}
```

**Owner:** RecordingStateController (`var state: RecordingState`)
**Reader:** RecordingUiController (implementiert `RecordingStateController.Callback`)
**Query-Lambda in KeyboardStateManager (Z. 532-533):**
```kotlin
() -> recordingStateController != null && recordingStateController.getState() instanceof RecordingState.Active,
() -> recordingStateController != null && recordingStateController.getState() instanceof RecordingState.Paused,
```

### PipelineUiState

**Definition:** PipelineUiState.kt, Z. 13-54
```kotlin
sealed class PipelineUiState {
    object Idle : PipelineUiState()
    object Preparing : PipelineUiState()
    data class Running(...) : PipelineUiState()
    data class ReprocessStaging(...) : PipelineUiState()
}
```

**Owner:** KeyboardUiController (`var state: PipelineUiState`)
**Query-Lambda in KeyboardStateManager (Z. 538-541):**
```kotlin
() -> uiController != null && uiController.getState() instanceof PipelineUiState.Running,
() -> uiController != null && uiController.getState() instanceof PipelineUiState.ReprocessStaging
```

### ContentArea

**Definition:** ContentArea.kt, Z. 4-8 (siehe oben)
**Owner:** KeyboardStateManager (`var contentArea: ContentArea`)
**Setter:** `setContentArea(area)` (Z. 135-138)

### SmallMode

**Definition:** Pref.SmallMode (DictatePrefs.kt, Z. 33)
**Owner:** KeyboardStateManager (`var isSmallMode: Boolean`, Z. 102)
**Setter:** `setSmallMode(enabled)` (Z. 140-146)
**Persistierung:** SharedPreferences

**Spezial-Regel (Z. 142-144):**
```kotlin
if (enabled && contentArea != ContentArea.MAIN_BUTTONS) {
    contentArea = ContentArea.MAIN_BUTTONS
}
```
SmallMode-Vorrang: Auto-Switch zu MAIN_BUTTONS wenn SmallMode aktiviert.

### Pref.SingleRowMode

**Definition:** DictatePrefs.kt, Z. 34
**Persistierung:** SharedPreferences
**Reader:** KeyboardLayoutModeController.init (Z. 100):
```kotlin
setSingleRowMode(sp.get(Pref.SingleRowMode), animate = false)
```

### Pref.Animations

**Definition:** DictatePrefs.kt, Z. 32
**Respektiert in:**
- MainButtonsController.animateSmallModeToggle (Z. 425) — check vor animate()
- MainButtonsController.animateEditNumbersBounce (Z. 453) — early return wenn false
- KeyboardLayoutModeController.setSingleRowMode (Z. 123) — gate TransitionManager
- RecordingUiController.applyActiveState (Z. 161) — gate recordingAnimation.start()
- RecordingUiController.applyIdleState (Z. 123) — gate recordingAnimation.cancel()
- RecordingUiController.applyPausedState (Z. 189) — gate recordingAnimation.pause()

### Sichtbarkeits-Matrix (Main-Button-Area-Buttons nur)

Reduziert auf **relevante** Zustandskombinationen (ContentArea = MAIN_BUTTONS, SmallMode = false):

| Button | Idle | Recording | Paused | ReprocessStaging | Pipeline-Running |
|--------|------|-----------|--------|------------------|------------------|
| record_pulse_layout | VISIBLE (PulseLayout inactive) | VISIBLE (pulse active) | VISIBLE (pulse paused) | VISIBLE (pulse inactive) | VISIBLE (pulse inactive) |
| record_btn | Enabled, Text = Lang-Label | Enabled, Text = "Senden" | Enabled, Text = "Senden" | Enabled (depends on parent state) | Overridden by RecordingUiController |
| resend_btn | VISIBLE (iff last audio exists) | GONE | GONE | GONE | GONE |
| backspace_btn | VISIBLE | VISIBLE | VISIBLE | VISIBLE | VISIBLE |
| trash_btn | GONE | VISIBLE | VISIBLE | VISIBLE | VISIBLE |
| space_btn | VISIBLE | VISIBLE | VISIBLE | VISIBLE | VISIBLE |
| pause_btn | GONE | VISIBLE | VISIBLE | VISIBLE (disabled, alpha 0.4) | GONE |
| enter_btn | VISIBLE | VISIBLE | VISIBLE | VISIBLE | VISIBLE |
| audio_focus_btn | GONE (only SingleRow) | GONE (only SingleRow) | GONE (only SingleRow) | GONE (only SingleRow) | GONE (only SingleRow) |

**Besonderheiten:**

1. **Pause-Button Staging-Mode (Z. 187-189, KeyboardStateManager):**
```kotlin
views.pauseButton.visibility = if (isActive || isStaging) View.VISIBLE else View.GONE
views.pauseButton.isEnabled = isActive
views.pauseButton.alpha = if (isActive) 1.0f else 0.4f
```

2. **Resend-Button (RecordingUiController.kt, Z. 137, 158):**
```kotlin
resendButton.visibility = if (getLastAudioFileExists()) View.VISIBLE else View.GONE  // Idle
resendButton.visibility = View.GONE  // Active
```

3. **SmallMode (Z. 140):** Wenn aktiviert → `views.mainButtonsClTyped.visibility = View.GONE` (gesamte Container)

---

## 4. State-Übergänge & Trigger

### Layout-Mode-Wechsel (Two-Row ↔ Single-Row)

**Trigger:** Long-press auf edit_numbers_btn

**Call-Chain (DictateInputMethodService.java, Z. 2639-2661):**
1. Pref.SingleRowMode flip + persist (Z. 2652-2654)
2. layoutModeController.setSingleRowMode(next, animate=true) (Z. 2656)
3. mainButtonsController.animateEditNumbersBounce() (Z. 2659)

**Animated by:** TransitionManager.beginDelayedTransition (iff Pref.Animations) (KeyboardLayoutModeController Z. 124)

**Guard:** SmallMode-Vorrang (Z. 2646-2651) — ganze Animation ist invisible wenn SmallMode=true, aber Pref persistiert sich

### ContentArea-Wechsel

**Trigger:** Clicks auf edit_buttons_keyboard_ll:
- edit_emoji_btn → ContentArea.EMOJI_PICKER
- edit_keyboard_btn (long-press) → ContentArea.QWERTZ
- etc.

**Setter:** KeyboardStateManager.setContentArea (Z. 135) → applyVisibility()

**Side-Effect:** applyVisibility() ruft layoutModeController.refresh() (Z. 168), um SingleRowMode neu zu applizieren

### SmallMode-Wechsel

**Trigger:** Click auf edit_numbers_btn

**Call-Chain (DictateInputMethodService Z. 2631-2636):**
1. Pref.SmallMode flip + persist (Z. 2633)
2. stateManager.setSmallMode(newSmallMode) (Z. 2634)
3. mainButtonsController.animateSmallModeToggle(true) (Z. 2635)

**Side-Effect:** Wenn SmallMode=true → ContentArea forced zu MAIN_BUTTONS

### RecordingState-Wechsel

**Trigger:** Recording-Manager (RecordingStateController)

**State-Transitions:**
- Idle → Preparing (Bluetooth-Init) oder → Active (sofort, wenn kein BT)
- Active → Paused (Long-press pause)
- Paused/Active → Idle (Stop/Cancel)

**UI-Hook:** RecordingUiController.onStateChanged (Z. 51-60) → stateManager.refresh()

**Pulse-Animation wired in Zeile 162 (start) & 190 (pause):**
```kotlin
if (isAnimationEnabled()) {
    recordingAnimation.start()  // BorderGlowAnimation → record_pulse_layout.startPulse()
}
```

---

## 5. Animations & Performance-relevante Details

### PulseLayout (record_pulse_layout)

**Datei:** `app/src/main/java/net/devemperor/dictate/widget/PulseLayout.kt`, Z. 1-142

**Lifecycle:**
- `startPulse()` (Z. 105): ValueAnimator 0→1, repeat INFINITE, LinearInterpolator, duration=2000ms
- `pausePulse()` (Z. 116): animator.pause() (API 24+)
- `resumePulse()` (Z. 120): animator.resume()
- `stopPulse()` (Z. 124): animator.cancel(), animator=null, invalidate()

**Draw-Logik (onDraw, Z. 79-103):**
- Mehrere konzentrische Kreise (pulseCount=3)
- Fades aus mit Progress (alpha = (1-progress) * pulseStartAlpha * 255)
- Radiusberechnung: minRadius + (maxRadius - minRadius) * progress

### BorderGlowAnimation (record_btn Visual während Recording)

**Datei:** `BorderGlowAnimation.kt`

**Start (Z. 62-93):**
- Save original button state
- Create AmplitudeVisualizerDrawable
- Clear text + drawables
- Set visualizer as foreground

**Pause-Handling (Z. 95-99):**
- isPaused = true
- applyBackgroundLevel(0.12f)

### TransitionManager.beginDelayedTransition()

**Wired in:** KeyboardLayoutModeController.setSingleRowMode (Z. 124)

**Effect:** Fade-out/fade-in (AutoTransition default) für View-Hierarchie-Änderung (re-parent input_row buttons)

**Gated by:** Pref.Animations

**Scene Root:** mainButtonsClTyped (LinLay parent of action_row + input_row)

---

## 6. Verantwortungs-Verteilung (Owners & SSOT)

### Persistent State (SharedPreferences)

| Schlüssel | Owner (Write) | Reader | Typ |
|-----------|-------------|--------|-----|
| Pref.SingleRowMode | Service (onSingleRowModeToggled) | KeyboardLayoutModeController.init | Boolean |
| Pref.SmallMode | Service (onSmallModeToggled) | KeyboardStateManager (setter) | Boolean |
| Pref.Animations | (Settings) | Multiple consumers | Boolean |
| Pref.AudioFocus | Service (onAudioFocusToggled) | RecordingStateController.startRecording | Boolean |
| Pref.ResendButton | (Settings) | RecordingUiController (lambda) | Boolean |

### Runtime State

| State-Variable | Owner | Mutation | Reader |
|---|---|---|---|
| KeyboardStateManager.contentArea | KeyboardStateManager | setContentArea() | applyContentAreaVisibility() |
| KeyboardStateManager.isSmallMode | KeyboardStateManager | setSmallMode() | applyVisibility() |
| RecordingStateController.state | RecordingStateController | setState() (internal) | lambdas in ctor |
| KeyboardUiController.state | KeyboardUiController | updatePipelineState() | lambdas in ctor |

### Visibility-Berechnung

**Einzige authoritative Source:** KeyboardStateManager.applyVisibility() (Z. 158-169)

**Drei Sub-Funktionen:**
1. applyContentAreaVisibility() (Z. 171-181)
2. applyRecordingControlsVisibility() (Z. 183-192)
3. applyPromptsVisibility() (Z. 194-224)

**Callbacks der Sub-Funktionen (alle lambdas aus ctor):**
- isRecording()
- isPaused()
- isPipelineRunning()
- isRewordingEnabled()
- isPipelineProgressVisible()
- isReprocessStaging()

### Layout-Mode-Application

**Owner:** KeyboardLayoutModeController

**Methoden:**
- setSingleRowMode(enabled, animate) (Z. 115-140) — main entry point
- rehome(toSingleRow) (Z. 183-191) — re-parent buttons
- refresh() (Z. 150-152) — re-apply current pref ohne animation

**Constraints-Anwendung:**
- csTwoRowAction.applyTo(views.actionRow)
- csTwoRowInput.applyTo(views.inputRow)
- csSingleRow.applyTo(views.actionRow)

**Input-Row Sichtbarkeit (Z. 133):**
```kotlin
views.inputRow.visibility = if (enabled) View.GONE else View.VISIBLE
views.audioFocusButtonInRow.visibility = if (enabled) View.VISIBLE else View.GONE
```

### Bridge zwischen Runtime-State und UI-Update

**Primary Bridge:** KeyboardStateManager.refresh()

**Called from:**
1. RecordingStateController.Callback.onStateChanged (RecordingUiController Z. 59)
2. KeyboardUiController.updatePipelineState (Z. 153)
3. DictateInputMethodService (Service) wenn explizit needed

**Cascade-Effekt:**
```
stateManager.refresh()
  → applyVisibility()
    → applyRecordingControlsVisibility()
    → applyPromptsVisibility()
    → layoutModeController.refresh()  // re-apply SingleRowMode
      → setSingleRowMode(sp.get(Pref.SingleRowMode), animate=false)
```

**Secondary Bridge:** KeyboardStateManager.setLayoutModeController(controller)
- Setter-Injection (Z. 115-117)
- Break circular dependency
- Inject nach MainButtonsController construction (DictateInputMethodService Z. 589)

---

## Zusammenfassung: Was der Refactor erhalten muss

1. **Pulse-Animation des record_pulse_layout:** Die **Wrapper**-Re-Parenting-Logik ist kritisch. Moving only the inner button breaks animation.

2. **Click-Handler-Kette:**
   - record_btn → recordClickListener → callback.onRecordClicked()
   - Alle 9 Buttons haben distinct Handler
   - Callbacks zentral in MainButtonsController.Callback interface

3. **Visibility-Matrix:**
   - pause_btn muss disabled+alpha0.4 in ReprocessStaging sein
   - resend_btn muss GONE in Recording sein
   - trash_btn muss VISIBLE in Recording+Staging sein
   - Authoritative Quelle: KeyboardStateManager.applyVisibility() — keine dezentralen Visibility-Calls

4. **Layout-Mode-Switching:**
   - originalParents-Map muss erhalten bleiben (verhindert Bug 2026-05-07) — *oder durch besseres Pattern ersetzt werden*
   - Re-Parenting muss idempotent sein (multiple refresh() calls pro frame safe)
   - audio_focus_btn ist Special: bleibt in action_row, nur Visibility wechselt

5. **Preferences:**
   - Pref.SingleRowMode, Pref.SmallMode, Pref.Animations müssen weiterhin persisted + geladen werden
   - AudioFocus sync (Settings ↔ Service ↔ buttons) muss live bleiben

6. **Listener-Pattern:**
   - EditBar buttons (edit_numbers_btn, edit_keyboard_btn, etc.) sind **außerhalb** main_buttons_cl
   - Aber ihre Callbacks (SmallMode toggle, SingleRow toggle) beeinflussen Main-Button-Area
   - Decoupling über Callbacks ist wichtig

## Risiko-Liste

1. **PulseLayout Re-Parenting Fragil:**
   - Test: Moving nur record_btn (nicht Wrapper) bricht Animation
   - Mitigation: Unit-Tests für PulseLayout.startPulse() nach Re-Parent

2. **originalParents-Map Dependency:**
   - Wenn neue Buttons zu input_row hinzugefügt werden, müssen sie in die Map
   - Fehlende Einträge → Bug 2026-05-07 erneut

3. **KeyboardStateManager als Visibility-SSOT:**
   - Aber RecordingUiController macht auch Visibility-Mutationen (resend_btn Z. 137, 158)
   - Kann zu Race-Bedingungen führen, wenn beide simultaneously mutieren
   - **Lösungsansatz:** RecordingUiController sollte nur State-Change berichten; KeyboardStateManager berechnet Visibility

4. **TransitionManager Lifecycle:**
   - Wenn `mainButtonsClTyped` (Scene Root) GONE ist (SmallMode), ist TransitionManager inaktiv
   - Beabsichtigt, aber fragil wenn Refactor Layout-Hierarchie ändert

5. **Audio-Focus Button Lifecycle-Asymmetrie:**
   - edit_audio_focus_btn: always visible, separate from layout mode
   - audio_focus_btn: in action_row, visibility = SingleRowMode
   - Beide müssen **identisch** synchronized sein (icon + contentDescription)
   - Wired über mainButtonsController.refreshAudioFocusIcon (Z. 368-387)
   - **Risiko:** Wenn einer der beiden nicht wired ist, unsync icon

6. **Bounce-Animation vs Rotation-Animation Conflict:**
   - edit_numbers_btn hat zwei Animations: rotation (SmallMode, Z. 426) + translationX (SingleRow, Z. 461)
   - Wenn back-to-back toggled: kann translationX-Drift entstehen
   - Mitigation vorhanden: `btn.animate().cancel()` (Z. 459)

## Offene Fragen (nur User kann beantworten)

1. **Welche Breaking Changes akzeptabel?** Z.B. Audio-Focus-Button als separate Komponente re-implementieren statt Dual-Wiring?

2. **Performance-Anforderung für Layout-Wechsel?** Reicht TransitionManager-Fade, oder muss es schneller sein?

3. **Werden neue Buttons hinzukommen?** Falls ja: Single-Row Chain-Reihenfolge ändern?

4. **Ist `originalParents`-Map-Ansatz für Long-Term haltbar,** oder sollte "native parent" in einer Annotation pro Button definiert sein? (Ist mit MotionLayout/flachem Container ohnehin obsolet.)

5. **RecordingUiController.resendButton Visibility vs KeyboardStateManager Visibility:** Wer ist Owner? (Derzeit hybrid — gefährlich.)

---

## Datei-Referenzen (für den Plan)

- XML Layout: `app/src/main/res/layout/activity_dictate_keyboard_view.xml`
- Visibility/State Mgmt: `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt`
- Layout-Mode: `app/src/main/java/net/devemperor/dictate/core/KeyboardLayoutModeController.kt`
- Buttons/Handler: `app/src/main/java/net/devemperor/dictate/core/MainButtonsController.kt`
- Recording-UI: `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt`
- Service (Wiring): `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (Z. 400-746, 2630-2687)
- Pulse-Widget: `app/src/main/java/net/devemperor/dictate/widget/PulseLayout.kt`
- Recording-Animation: `app/src/main/java/net/devemperor/dictate/widget/BorderGlowAnimation.kt`
- Preferences: `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt` (Z. 12-106)
- States: `app/src/main/java/net/devemperor/dictate/core/RecordingState.kt`, `PipelineUiState.kt`, `ContentArea.kt`
