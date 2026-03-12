# QWERTZ Keyboard Feature Plan

<!-- EXECUTION-PLAN -->
## Execution Plan

**Erstellt:** 2026-03-12 15:30
**Geschätzte Chunks:** 6

### Meine Strategie

Bottom-Up-Implementierung entlang der Schichtenarchitektur: Zuerst das Datenmodell und wiederverwendbare Utility-Klassen, dann Layout-Definitionen, dann die View-Schicht, dann der Controller als Logik-Kern, dann Integration in den bestehenden Service, und zuletzt Feinschliff. Jeder Chunk ergibt kompilierbaren Code.

### Geplante Chunks

| # | Chunk | Plan-Tasks | Warum diese Gruppierung? |
|---|-------|-----------|--------------------------|
| 1 | Datenmodell + Utility-Handler | A1, A3, A4, A5 | Reine Daten- und Utility-Klassen ohne UI-Abhängigkeiten – Fundament für alles |
| 2 | Layout-Definitionen + Ressourcen | A2, B2, B3 | Definiert was das Keyboard zeigt, ohne es zu rendern |
| 3 | View-Schicht | B1 | Komplexe Custom ViewGroup, eigener Chunk wegen Umfang |
| 4 | Controller + State-Machine | C1 | Zentraler Logik-Kern, braucht alle vorherigen Chunks |
| 5 | Integration in Service + XML | D1, D2a-d | Alle Integration-Tasks am selben File, strikt sequentiell |
| 6 | Feinschliff + Edge Cases | D3 | Polish-Tasks nach funktionierendem Keyboard |

### Abhängigkeiten & Risiken

- **Chunk 1 → 2:** A2 (LayoutProvider) braucht A1 (KeyDef, Enums)
- **Chunk 1 → 3:** View braucht KeyDef + KeyPressAnimator
- **Chunk 1,2,3 → 4:** Controller braucht alles vorherige
- **Chunk 4 → 5:** Integration braucht fertigen Controller
- **Chunk 5 → 6:** Feinschliff braucht funktionierendes Keyboard
- **Risiko:** DictateInputMethodService.java ist groß und komplex – Integration-Chunk (5) muss sorgfältig bestehenden Code lesen

---
<!-- /EXECUTION-PLAN -->

## Phase 1: Intent

### Feature-Anforderungen

Vollstaendiges SwiftKey-artiges QWERTZ-Keyboard als Ersatz fuer das aktuelle einfache Numpad-Overlay. Das Keyboard soll als permanente Eingabeflaeche dienen, nicht als temporaeres Overlay.

### Use Cases

1. **Schnelle Textkorrektur**: User tippt einzelne Woerter/Zeichen direkt ueber QWERTZ statt Spracheingabe (z.B. Namen, Fachbegriffe die Diktat nicht erkennt)
2. **Nummern-/Sonderzeicheneingabe**: User wechselt per 123-Button auf Nummernblock fuer Telefonnummern, Betraege, Datumswerte
3. **Modifier-basierte Eingabe**: User nutzt Shift fuer Grossbuchstaben, Ctrl+A/C/V fuer Clipboard-Operationen
4. **Umlaut-Eingabe**: User tippt deutsche Umlaute direkt ueber dedizierte Tasten
5. **Nahtloser Wechsel Diktat/Tippen**: User diktiert Text, korrigiert einzelne Stellen per QWERTZ, diktiert weiter

### User Stories

- Als Dictate-User moechte ich ein vollstaendiges QWERTZ-Keyboard, damit ich Text auch ohne Spracheingabe tippen kann
- Als Dictate-User moechte ich per 123-Button schnell zu Zahlen/Sonderzeichen wechseln, damit ich Nummern effizient eingeben kann
- Als Dictate-User moechte ich Shift/Caps Lock nutzen, damit ich Gross-/Kleinschreibung kontrollieren kann
- Als Dictate-User moechte ich Umlaute direkt tippen koennen, ohne Long-Press-Popups

### Scope

- **In Scope**: QWERTZ-Layout (Portrait), Zahlenreihe, 123-Modus (Nummern + Sonderzeichen), Shift (einfach + Caps Lock), Backspace, Enter, Space, Tab, Ctrl-Modifier, Umlaute
- **Out of Scope**: Landscape-Modus, Swipe-Typing, Wort-Vorhersage, Auto-Korrektur, andere Sprach-Layouts, Long-Press-Popups fuer alternative Zeichen, Backspace Swipe-Select (Links-Wischen auf Backspace zum Selektieren von Woertern -- kann spaeter ergaenzt werden, besonders wenn die Touch-Handler bereits in wiederverwendbare Klassen extrahiert sind)

---

## Phase 2: Research

### 2.1 Aktuelles Numpad-Overlay

**Datei**: `app/src/main/res/layout/activity_dictate_keyboard_view.xml` (Zeilen 434-698)

Das aktuelle Numpad ist ein ConstraintLayout-Overlay (`numbers_panel_cl`) mit:
- Feste Hoehe: `320dp`
- Titel-TextView + Close-Button Header
- 4x4 Grid aus MaterialButtons in verschachtelten LinearLayouts
- Layout-Pattern: Vertikales LinearLayout mit 4 horizontalen LinearLayouts, jeweils `layout_weight="1"`
- Buttons nutzen `layout_weight="1"` fuer gleichmaessige Verteilung
- Spezielle Tags: `android:tag=" "` (Space), `android:tag="BACKSPACE"`, `android:tag="ENTER"`
- Button-Styling: `minHeight="0dp"`, `minWidth="0dp"`, `layout_margin="4dp"`, `textSize="18sp"`

**Datei**: `DictateInputMethodService.java`

Numpad-Handling:
- `collectNumberPanelButtons()` (Zeile 1299): Rekursiv alle MaterialButtons aus dem Container sammeln
- Button-Wert wird aus `tag` (falls gesetzt) oder `text` gelesen
- Click-Handler: `BACKSPACE` -> `deleteOneCharacter()`, `ENTER` -> `performEnterAction()`, sonst -> `commitNumberPanelValue(value)`
- `commitNumberPanelValue()` (Zeile 1396): Einfach `inputConnection.commitText(value, 1)`
- `shouldAutomaticallyShowNumberPanel()` (Zeile 1404): Prueft InputType (NUMBER, PHONE, DATETIME)
- `toggleNumberPanel()` / `showNumberPanel()` / `hideNumberPanel()`: Visibility-Toggles, gegenseitiger Ausschluss mit Emoji-Picker

### 2.2 Overlay-System

Die App hat drei Overlay-Panels, die sich gegenseitig ausschliessen:
1. **Emoji-Picker** (`emoji_picker_cl`): 400dp hoch, EmojiPickerView
2. **Number-Panel** (`numbers_panel_cl`): 320dp hoch, 4x4 Button-Grid
3. **Overlay Characters** (`overlay_characters_ll`): Kleine Zeichenleiste ueber Enter-Button

Pattern: `showX()` ruft `hideY()` auf, dann setzt eigene Visibility auf VISIBLE + `bringToFront()`.

### 2.3 Input-Handling Patterns

**commitText vs sendKeyEvent**:
- Zeichen-Eingabe: `inputConnection.commitText(value, 1)` -- fuer alle druckbaren Zeichen
- Backspace: `deleteOneCharacter()` -- nutzt BreakIterator fuer korrekte Grapheme-Cluster-Behandlung
- Enter: `performEnterAction()` -- respektiert IME_ACTION (Go, Search, Send, Next, Done)
- Space: `inputConnection.commitText(" ", 1)` via Touch-Handler
- Cursor-Bewegung: Space-Button Swipe nutzt `commitText("", 2)` / `commitText("", -1)`
- Clipboard: `inputConnection.performContextMenuAction(android.R.id.cut/copy/paste/undo/redo)`

**Long-Press Backspace** (Zeile 545-571):
- Accelerating delete: 50ms -> 25ms (nach 1.5s) -> 10ms (nach 3s) -> 5ms (nach 5s)
- Swipe-Select bei Links-Swipe auf Backspace

### 2.4 Theming/Color Patterns

**Datei**: `DictateInputMethodService.java` (Zeilen 1136-1179)

Drei Farbstufen aus User-Akzentfarbe:
- `accentColor`: Hauptfarbe (Record-Button, Enter im Numpad, Close-Buttons, Titel-TextViews)
- `accentColorMedium`: `darkenColor(accentColor, 0.18f)` -- die meisten Buttons
- `accentColorDark`: `darkenColor(accentColor, 0.35f)` -- Backspace, Switch, Enter

Pattern: `applyButtonColor(button, backgroundColor)` setzt `button.setBackgroundColor(color)`.

Keyboard-Hintergrund: Theme-abhaengig (`dictate_keyboard_background_dark` #1b1b1d / `dictate_keyboard_background_light` #f1f1f1).

### 2.5 Press-Animation Pattern

**Datei**: `DictateInputMethodService.java` (Zeilen 1337-1394)

- Konstanten: `KEY_PRESS_SCALE = 0.92f`, `KEY_PRESS_ANIM_DURATION = 80L`, `DecelerateInterpolator`
- `applyPressAnimation(view)`: Setzt OnTouchListener der `handlePressAnimationEvent()` ruft
- `animateKeyPress(view, pressed)`: Scale-Animation auf 0.92f (press) bzw 1.0f (release)
- Kann per Setting deaktiviert werden: `sp.getBoolean("net.devemperor.dictate.animations", true)`

### 2.6 Vibration Pattern

`vibrate()` Methode (Zeile 1218): `VibrationEffect.EFFECT_TICK` (API 29+) oder `createOneShot(50ms)`.
Gesteuert durch `sp.getBoolean("net.devemperor.dictate.vibration", true)`.

### 2.7 God-Class-Situation

`DictateInputMethodService.java` hat 2149 Zeilen und ist bereits eine God-Class mit:
- View-Initialisierung (~200 Zeilen)
- Recording-Management
- Pipeline-Orchestrierung
- Input-Handling
- Theming
- Overlay-Management

Neuer Keyboard-Code MUSS in separate Klassen extrahiert werden.

### 2.8 Vorhandene Drawables (relevant)

```
ic_baseline_keyboard_backspace_24.xml    -- Backspace-Icon
ic_baseline_keyboard_hide_24.xml         -- Keyboard-Hide
ic_baseline_space_bar_24.xml             -- Spacebar-Icon
ic_baseline_subdirectory_arrow_left_24.xml -- Enter-Icon
ic_baseline_keyboard_arrow_up_24.xml     -- Kann fuer Shift genutzt werden
ic_baseline_settings_24.xml              -- Settings
```

---

## Phase 3: Skeleton Architecture

### 3.1 Dateistruktur

```
app/src/main/
  java/net/devemperor/dictate/
    keyboard/                              # NEUES Package
      QwertzKeyboardController.kt          # CREATE -- Hauptcontroller
      QwertzKeyboardLayout.kt              # CREATE -- Layout-Enum (QWERTZ, NUMBERS, SYMBOLS)
      QwertzKeyDef.kt                      # CREATE -- Key-Definition Datenklasse
      QwertzLayoutProvider.kt              # CREATE -- Liefert Key-Definitionen pro Layout
      QwertzKeyboardView.kt                # CREATE -- Custom ViewGroup, baut Keyboard aus Defs
      AcceleratingRepeatHandler.kt         # CREATE -- Wiederverwendbarer Handler fuer Accelerating-Delete (Backspace)
      CursorSwipeTouchHandler.kt           # CREATE -- Wiederverwendbarer Handler fuer Space Cursor-Swipe
      KeyPressAnimator.kt                  # CREATE -- Extrahierte Press-Animation-Logik (shared mit DictateInputMethodService)
    core/
      DictateInputMethodService.java       # MODIFY -- Integration, Numpad-Code entfernen
  res/
    layout/
      activity_dictate_keyboard_view.xml   # MODIFY -- Numpad entfernen, QWERTZ-Container einfuegen
      keyboard_qwertz_panel.xml            # CREATE -- QWERTZ-Panel Root-Layout
    drawable/
      ic_keyboard_shift_24.xml             # CREATE -- Shift-Icon
      ic_keyboard_shift_filled_24.xml      # CREATE -- Shift-Locked-Icon (Caps Lock)
      ic_keyboard_tab_24.xml               # CREATE -- Tab-Icon
    values/
      strings.xml                          # MODIFY -- Neue Strings
```

### 3.2 Klassen-Skelette

#### QwertzKeyDef.kt
```kotlin
package net.devemperor.dictate.keyboard

/**
 * Definiert eine einzelne Taste im Keyboard-Layout.
 * Immutable data class -- Layouts werden als Listen von Reihen (List<List<QwertzKeyDef>>) definiert.
 */
data class QwertzKeyDef(
    val label: String,                    // Angezeigter Text ("q", "1", "Shift")
    val output: String? = null,           // commitText-Wert (null = funktionale Taste), muss in QwertzLayoutProvider explizit angegeben werden
    val keyAction: KeyAction = KeyAction.COMMIT_TEXT,
    val widthWeight: Float = 1f,          // Relative Breite (1.0 = Standard, 1.5 = breit, etc.)
    val iconResId: Int = 0,               // Drawable statt Text (0 = kein Icon)
    val repeatable: Boolean = false,      // Long-Press Repeat (Backspace, Delete)
    val shiftOutput: String? = null,      // Expliziter Shift-Output (null = output.uppercase()), fuer Sonderfaelle wie ß
    val colorTier: ColorTier = ColorTier.MEDIUM  // Farbstufe fuer Theming
)

enum class KeyAction {
    COMMIT_TEXT,      // inputConnection.commitText(output, 1)
    BACKSPACE,        // deleteOneCharacter()
    ENTER,            // performEnterAction()
    SHIFT,            // Toggle Shift-State
    SWITCH_LAYOUT,    // Wechsel zu NUMBERS/SYMBOLS/QWERTZ
    SPACE,            // Space mit Cursor-Swipe
    TAB,              // Tab-Zeichen
    CTRL_MODIFIER,    // Toggle Ctrl-Modifier-State
}

enum class ColorTier {
    ACCENT,    // accentColor (Enter, Shift aktiv)
    MEDIUM,    // accentColorMedium (Buchstaben, Zahlen)
    DARK       // accentColorDark (Backspace, Modifier)
}
```

#### QwertzKeyboardLayout.kt
```kotlin
package net.devemperor.dictate.keyboard

/**
 * Enum der verfuegbaren Keyboard-Modi.
 */
enum class QwertzKeyboardLayout {
    QWERTZ,          // Hauptlayout: QWERTZ + Zahlenreihe oben
    NUMBERS,         // Nummernblock + haeufige Sonderzeichen
    SYMBOLS          // Erweiterte Sonderzeichen
}
```

#### QwertzLayoutProvider.kt
```kotlin
package net.devemperor.dictate.keyboard

/**
 * Stellt die Key-Definitionen fuer jedes Layout bereit.
 * Reine Daten-Klasse ohne UI-Logik.
 *
 * Jedes Layout ist eine List<List<QwertzKeyDef>> (Reihen von Keys).
 */
object QwertzLayoutProvider {

    // TODO: QWERTZ-Layout definieren
    //   Reihe 0: 1 2 3 4 5 6 7 8 9 0 ss (Zahlenreihe)
    //   Reihe 1: q w e r t z u i o p ue
    //   Reihe 2: a s d f g h j k l oe ae
    //   Reihe 3: [Shift] y x c v b n m [Backspace]
    //   Reihe 4: [123] [Ctrl] [Tab] [Space] [.] [Enter]
    //
    // WICHTIG: output muss fuer jeden Key explizit angegeben werden (kein Default mehr).
    //   Beispiel: QwertzKeyDef(label = "q", output = "q")
    //   Eszett: QwertzKeyDef(label = "ß", output = "\u00DF", shiftOutput = "\u00DF") -- bleibt ß auch bei Shift
    //   Funktionale Tasten: output = null (z.B. Shift, Backspace)
    fun getLayout(layout: QwertzKeyboardLayout, shiftActive: Boolean): List<List<QwertzKeyDef>> {
        // TODO: Implementierung
        return emptyList()
    }

    // TODO: NUMBERS-Layout definieren
    //   Reihe 0: 1 2 3 4 5 6 7 8 9 0
    //   Reihe 1: @ # € & _ - + ( ) /
    //   Reihe 2: [=<] * " ' : ; ! ? [Backspace]
    //   Reihe 3: [ABC] [,] [Space] [.] [Enter]

    // TODO: SYMBOLS-Layout definieren
    //   Reihe 0: ~ ` | ... (erweiterte Sonderzeichen)
    //   Reihe 1: ...
    //   Reihe 2: [123] ... [Backspace]
    //   Reihe 3: [ABC] [,] [Space] [.] [Enter]
}
```

#### QwertzKeyboardView.kt
```kotlin
package net.devemperor.dictate.keyboard

import android.content.Context
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton

/**
 * Custom ViewGroup das ein Keyboard-Layout aus QwertzKeyDef-Listen rendert.
 *
 * Baut fuer jede Reihe ein horizontales LinearLayout mit gewichteten MaterialButtons.
 * Kein XML-Layout noetig -- alles programmatisch aus den KeyDef-Listen.
 *
 * Verantwortlichkeiten:
 * - MaterialButtons erstellen und in LinearLayouts anordnen
 * - Press-Animationen anwenden
 * - Button-Farben setzen (nach ColorTier)
 * - Layout-Wechsel (clearViews + rebuild)
 *
 * NICHT verantwortlich fuer:
 * - Input-Handling (delegiert an KeyActionCallback)
 * - State-Management (Shift, Caps, Ctrl)
 */
class QwertzKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    // TODO: Callback-Interface fuer Key-Actions (Naming: "Callback" fuer Konsistenz mit RecordingCallback, PipelineCallback)
    interface KeyActionCallback {
        fun onKeyAction(keyDef: QwertzKeyDef)
        fun onKeyLongPress(keyDef: QwertzKeyDef)
        fun onKeyReleased(keyDef: QwertzKeyDef)
    }

    // TODO: KeyPressAnimator-Instanz (empfaengt animationsEnabled von aussen)
    //   - Wird auf jeden Button angewendet statt eigener Animation-Logik

    // TODO: buildLayout(keys: List<List<QwertzKeyDef>>)
    //   - removeAllViews()
    //   - Fuer jede Reihe: horizontales LinearLayout erstellen
    //   - Fuer jeden Key: MaterialButton mit weight, text/icon, clickListener
    //   - KeyPressAnimator.applyPressAnimation() auf jeden Button
    //   - Fuer repeatable Keys: OnTouchListener mit Long-Press -> onKeyLongPress(), Release -> onKeyReleased()

    // TODO: applyColors(accentColor: Int, accentColorMedium: Int, accentColorDark: Int)
    //   - Iteriert ueber alle Buttons
    //   - Setzt Farbe basierend auf KeyDef.colorTier

    // TODO: updateShiftVisuals(shiftActive: Boolean, capsLock: Boolean)
    //   - Shift-Button Icon wechseln (outline vs filled)
    //   - Letter-Keys: Text toUpperCase/toLowerCase
}
```

#### QwertzKeyboardController.kt
```kotlin
package net.devemperor.dictate.keyboard

import android.view.inputmethod.InputConnection

/**
 * State-Machine und Input-Handler fuer das QWERTZ-Keyboard.
 *
 * Haelt den aktuellen Zustand (Layout, Shift, Caps Lock, Ctrl) und
 * verarbeitet Key-Actions von QwertzKeyboardView.
 *
 * Verantwortlichkeiten:
 * - State-Management (aktuelles Layout, Shift-State, Caps Lock, Ctrl)
 * - Input-Handling (commitText, sendKeyEvent, Modifier-Kombis)
 * - Delegiert UI-Updates an QwertzKeyboardView
 * - Delegiert InputConnection-Zugriff an Callback (DictateInputMethodService)
 *
 * NICHT verantwortlich fuer:
 * - View-Erstellung (das macht QwertzKeyboardView)
 * - Theming (das macht DictateInputMethodService)
 */
class QwertzKeyboardController(
    private val view: QwertzKeyboardView,
    private val inputConnectionProvider: () -> InputConnection?,
    private val vibrate: () -> Unit,
    private val deleteOneCharacter: () -> Unit,
    private val performEnterAction: () -> Unit
) : QwertzKeyboardView.KeyActionCallback {

    // TODO: State
    //   - currentLayout: QwertzKeyboardLayout = QWERTZ
    //   - shiftState: ShiftState = OFF (OFF, SINGLE, CAPS_LOCK)
    //   - ctrlActive: Boolean = false

    // TODO: Handler-Instanzen (delegierte Touch-Logik)
    //   - acceleratingRepeatHandler: AcceleratingRepeatHandler (fuer Backspace Long-Press)
    //   - cursorSwipeTouchHandler: CursorSwipeTouchHandler (fuer Space Swipe)
    //   Beide Handler werden wiederverwendbar erstellt und koennen auch von DictateInputMethodService genutzt werden.

    // TODO: onKeyAction(keyDef) -- Hauptlogik
    //   - COMMIT_TEXT: handleCharacterInput(keyDef)
    //   - BACKSPACE: deleteOneCharacter()
    //   - ENTER: performEnterAction()
    //   - SHIFT: toggleShift()
    //   - SWITCH_LAYOUT: switchLayout(keyDef)
    //   - SPACE: handleSpace()
    //   - TAB: commitText("\t", 1)
    //   - CTRL_MODIFIER: toggleCtrl()

    // TODO: onKeyLongPress(keyDef) -- Long-Press-Handling
    //   - BACKSPACE: acceleratingRepeatHandler.start(deleteOneCharacter)
    //   - Andere Keys: kein Long-Press-Verhalten

    // TODO: onKeyReleased(keyDef) -- Release-Handling
    //   - BACKSPACE: acceleratingRepeatHandler.stop()

    // TODO: handleCharacterInput(keyDef)
    //   - Wenn ctrlActive: Ctrl+Key Combo (z.B. Ctrl+A = selectAll)
    //   - Wenn shiftState != OFF:
    //     - Wenn keyDef.shiftOutput != null: commitText(shiftOutput, 1) -- fuer Sonderfaelle wie ß
    //     - Sonst: commitText(output.uppercase(), 1)
    //     - Wenn SINGLE: shiftState zuruecksetzen auf OFF
    //   - Sonst: commitText(output, 1)

    // TODO: switchLayout(target: QwertzKeyboardLayout)
    //   - currentLayout = target
    //   - view.buildLayout(QwertzLayoutProvider.getLayout(target, shiftActive))

    // TODO: toggleShift()
    //   - OFF -> SINGLE (naechstes Zeichen gross)
    //   - SINGLE -> CAPS_LOCK (dauerhaft gross)
    //   - CAPS_LOCK -> OFF
    //   - view.updateShiftVisuals()
}
```

#### AcceleratingRepeatHandler.kt
```kotlin
package net.devemperor.dictate.keyboard

import android.os.Handler
import android.os.Looper

/**
 * Wiederverwendbarer Handler fuer beschleunigtes Wiederholen einer Aktion bei Long-Press.
 * Extrahiert aus der bestehenden Backspace-Logik in DictateInputMethodService (Zeilen 545-571).
 *
 * Beschleunigungskurve: 50ms -> 25ms (nach 1.5s) -> 10ms (nach 3s) -> 5ms (nach 5s).
 * Wird sowohl vom QWERTZ-Controller als auch vom bestehenden Backspace genutzt.
 */
class AcceleratingRepeatHandler(private val handler: Handler = Handler(Looper.getMainLooper())) {

    // TODO: start(action: () -> Unit)
    //   - Startet wiederholtes Ausfuehren von action mit beschleunigender Rate
    //   - Tracking der Startzeit fuer Beschleunigungsberechnung

    // TODO: stop()
    //   - Stoppt alle pending Callbacks
    //   - Setzt State zurueck

    // TODO: private fun scheduleNext(action: () -> Unit)
    //   - Berechnet naechstes Delay basierend auf verstrichener Zeit
    //   - 50ms (0-1.5s), 25ms (1.5-3s), 10ms (3-5s), 5ms (5s+)
}
```

#### CursorSwipeTouchHandler.kt
```kotlin
package net.devemperor.dictate.keyboard

import android.view.MotionEvent
import android.view.View

/**
 * Wiederverwendbarer Touch-Handler fuer Cursor-Bewegung per horizontalem Swipe.
 * Extrahiert aus der bestehenden Space-Button-Logik in DictateInputMethodService (Zeilen 729-772).
 *
 * Wird sowohl vom QWERTZ-Space-Button als auch vom bestehenden Space-Button genutzt.
 */
class CursorSwipeTouchHandler(
    private val onTap: () -> Unit,
    private val onCursorMove: (direction: Int) -> Unit  // +1 = rechts, -1 = links
) : View.OnTouchListener {

    // TODO: onTouch(view, event) -- MotionEvent-basierte Swipe-Erkennung
    //   - ACTION_DOWN: Start-Position merken
    //   - ACTION_MOVE: Bei horizontaler Bewegung > Threshold -> onCursorMove()
    //   - ACTION_UP: Falls kein Swipe erkannt -> onTap()
}
```

#### KeyPressAnimator.kt
```kotlin
package net.devemperor.dictate.keyboard

import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Extrahierte Press-Animation-Logik fuer Keyboard-Buttons.
 * Ersetzt die privaten Methoden applyPressAnimation(), handlePressAnimationEvent()
 * und animateKeyPress() aus DictateInputMethodService.
 *
 * Wird von QwertzKeyboardView UND DictateInputMethodService genutzt.
 */
class KeyPressAnimator(private val animationsEnabled: Boolean) {

    companion object {
        const val KEY_PRESS_SCALE = 0.92f
        const val KEY_PRESS_ANIM_DURATION = 80L
    }

    // TODO: applyPressAnimation(view: View)
    //   - Setzt OnTouchListener der animateKeyPress() aufruft
    //   - Wenn !animationsEnabled: kein Listener setzen

    // TODO: animateKeyPress(view: View, pressed: Boolean)
    //   - pressed: Scale auf KEY_PRESS_SCALE mit DecelerateInterpolator
    //   - released: Scale zurueck auf 1.0f
}
```

---

## Phase 4: Detailed Architecture

### 4.1 Layout-Details

#### QWERTZ-Hauptlayout (5 Reihen)

```
Reihe 0 (Zahlen):     [1] [2] [3] [4] [5] [6] [7] [8] [9] [0] [ss]
Reihe 1 (QWERTZ-1):   [q] [w] [e] [r] [t] [z] [u] [i] [o] [p] [ue]
Reihe 2 (QWERTZ-2):    [a] [s] [d] [f] [g] [h] [j] [k] [l] [oe] [ae]
Reihe 3 (QWERTZ-3):   [SHIFT 1.5x] [y] [x] [c] [v] [b] [n] [m] [BACKSPACE 1.5x]
Reihe 4 (Bottom):      [123 1.2x] [Ctrl] [Tab] [____SPACE 4x____] [,] [.] [ENTER 1.5x]
```

#### Massen und Gewichte

| Element | Wert | Begruendung |
|---------|------|-------------|
| Reihen-Hoehe | `42dp` | SwiftKey-Standard, genug Tippflaeche |
| Reihen-Margin vertikal | `2dp` | Kompaktes Layout |
| Button-Margin horizontal | `2dp` | Minimaler Abstand |
| Button minHeight | `0dp` | Wird durch Layout-Weight gesteuert |
| Button minWidth | `0dp` | Wird durch Layout-Weight gesteuert |
| Button textSize | `14sp` (Buchstaben), `12sp` (Zahlen) | Lesbar aber kompakt |
| Panel-Padding | `4dp` horizontal, `2dp` vertikal | Knapper Rand |
| Gesamt-Panel-Hoehe | ~`220dp` (5 Reihen x 42dp + Margins) | Vergleichbar mit SwiftKey |

#### Reihe 2 (ASDF) Einrueckung
Reihe 2 hat 0.5x Gewicht Padding links, damit die Tasten versetzt sind (wie echtes QWERTZ).

#### NUMBERS-Layout (4 Reihen)

```
Reihe 0:  [1] [2] [3] [4] [5] [6] [7] [8] [9] [0]
Reihe 1:  [@] [#] [EUR] [&] [_] [-] [+] [(] [)] [/]
Reihe 2:  [=<] [*] ["] ['] [:] [;] [!] [?] [BACKSPACE 1.5x]
Reihe 3:  [ABC 1.5x] [,] [____SPACE 5x____] [.] [ENTER 1.5x]
```

#### SYMBOLS-Layout (4 Reihen)

```
Reihe 0:  [~] [`] [|] [^] [\] [{] [}] [<] [>] [%]
Reihe 1:  [TAB] [ss] [EUR] [GBP] [YEN] [DOL] [=] [[]] [[]] [DEL]
Reihe 2:  [123] [...] [""] [""] [-] [/] [!?] [BACKSPACE 1.5x]
Reihe 3:  [ABC 1.5x] [,] [____SPACE 5x____] [.] [ENTER 1.5x]
```

### 4.2 State-Machine

```
                    +---------+
                    | QWERTZ  |<----+
                    | Layout  |     |
                    +----+----+     |
                         |          |
            [123-Button] |          | [ABC-Button]
                         v          |
                    +---------+     |
              +---->| NUMBERS |-----+
              |     | Layout  |
              |     +----+----+
              |          |
   [ABC-Btn]  |  [=<-Button]
              |          v
              |     +---------+
              +-----| SYMBOLS |
                    | Layout  |
                    +---------+
```

#### Shift-State-Machine

```
    +-----+  single tap  +--------+  single tap  +-----------+
    | OFF |  ----------> | SINGLE |  ----------> | CAPS_LOCK |
    +-----+              +--------+              +-----------+
       ^                                              |
       |                     single tap               |
       +----------------------------------------------+
```

**Shift-Verhalten:**
- OFF: Alle Buchstaben lowercase
- SINGLE: Naechster Buchstabe uppercase, danach automatisch zurueck auf OFF
- CAPS_LOCK: Alle Buchstaben uppercase bis erneuter Tap

#### Ctrl-State

- Toggle per Ctrl-Button-Tap
- Wenn aktiv: Naechste Buchstaben-Taste wird als Ctrl-Combo interpretiert
- Nach Ctrl-Combo: Ctrl-State zurueck auf OFF
- Unterstuetzte Combos: Ctrl+A (Select All), Ctrl+C (Copy), Ctrl+V (Paste), Ctrl+X (Cut), Ctrl+Z (Undo)

### 4.3 Input-Handling Details

#### Zeicheneingabe
```kotlin
fun handleCharacterInput(keyDef: QwertzKeyDef) {
    val ic = inputConnectionProvider() ?: return
    vibrate()

    if (ctrlActive) {
        handleCtrlCombo(keyDef, ic)
        ctrlActive = false
        return
    }

    val text = when (shiftState) {
        ShiftState.OFF -> keyDef.output
        ShiftState.SINGLE, ShiftState.CAPS_LOCK -> {
            // shiftOutput ueberschreibt die Standard-Uppercase-Logik (z.B. ß bleibt ß)
            keyDef.shiftOutput ?: keyDef.output?.uppercase()
        }
    }

    if (text != null) {
        ic.commitText(text, 1)
    }

    if (shiftState == ShiftState.SINGLE) {
        shiftState = ShiftState.OFF
        refreshLayout()
    }
}
```

#### Ctrl-Combos
```kotlin
fun handleCtrlCombo(keyDef: QwertzKeyDef, ic: InputConnection) {
    when (keyDef.output?.lowercase()) {
        "a" -> ic.performContextMenuAction(android.R.id.selectAll)
        "c" -> ic.performContextMenuAction(android.R.id.copy)
        "v" -> ic.performContextMenuAction(android.R.id.paste)
        "x" -> ic.performContextMenuAction(android.R.id.cut)
        "z" -> ic.performContextMenuAction(android.R.id.undo)
    }
}
```

#### Backspace (Repeat)
Backspace nutzt den existierenden `deleteOneCharacter()` mit dem bestehenden accelerating-delete Pattern. Die Logik wird in `AcceleratingRepeatHandler` extrahiert und sowohl vom QWERTZ-Controller als auch vom bestehenden Backspace-Button genutzt:
- `onKeyAction(BACKSPACE)`: `deleteOneCharacter()` einmal
- `onKeyLongPress(BACKSPACE)`: `acceleratingRepeatHandler.start(deleteOneCharacter)`
- `onKeyReleased(BACKSPACE)`: `acceleratingRepeatHandler.stop()`

#### Space (Cursor-Swipe)
Space uebernimmt das existierende Swipe-Pattern von `spaceButton` (Zeile 729-772). Die Logik wird in `CursorSwipeTouchHandler` extrahiert und sowohl vom QWERTZ-Space als auch vom bestehenden Space-Button genutzt:
- Tap: `commitText(" ", 1)`
- Horizontal Swipe: Cursor bewegen via `commitText("", +/-1)`

### 4.4 Theming-Integration

Das QWERTZ-Panel integriert sich in das bestehende Akzentfarben-System:

```kotlin
// In DictateInputMethodService.onStartInputView(), nach bestehendem Theming-Block:
qwertzController.applyColors(accentColor, accentColorMedium, accentColorDark)
qwertzKeyboardView.setBackgroundColor(keyboardBackgroundColor)
```

Farb-Zuordnung nach ColorTier:
- `ACCENT`: Enter-Button, Shift (wenn aktiv), Ctrl (wenn aktiv)
- `MEDIUM`: Alle Buchstaben, Zahlen, Sonderzeichen, Space, 123/ABC-Button
- `DARK`: Backspace, Tab, Shift (wenn inaktiv), Ctrl (wenn inaktiv)

### 4.5 Integration in DictateInputMethodService

#### Aenderungen an onCreateInputView()

1. QWERTZ-Container im Layout statt Numpad
2. `QwertzKeyboardView` programmatisch erstellen und in Container einfuegen
3. `QwertzKeyboardController` initialisieren mit Callbacks

```java
// Neuer Code (ersetzt numberPanel-Initialisierung):
qwertzContainer = dictateKeyboardView.findViewById(R.id.qwertz_keyboard_container);
qwertzKeyboardView = new QwertzKeyboardView(context);
qwertzContainer.addView(qwertzKeyboardView);
qwertzController = new QwertzKeyboardController(
    qwertzKeyboardView,
    () -> getCurrentInputConnection(),
    () -> vibrate(),
    () -> deleteOneCharacter(),
    () -> performEnterAction()
);
```

#### Entfernter Code

- Alle `numberPanelButtons`-bezogenen Felder und Methoden
- `collectNumberPanelButtons()`, `commitNumberPanelValue()`, `toggleNumberPanel()`, `showNumberPanel()`, `hideNumberPanel()`
- Numpad-XML aus Layout
- `shouldAutomaticallyShowNumberPanel()` wird beibehalten, ruft aber QWERTZ im Numbers-Modus auf

#### Toggle-Logik

```java
// editNumbersButton -> jetzt Toggle fuer QWERTZ-Keyboard
editNumbersButton.setOnClickListener(v -> {
    vibrate();
    toggleQwertzKeyboard();
});
```

Das QWERTZ-Panel verhaelt sich wie das alte Numpad:
- Toggle-Button in der Edit-Leiste
- Auto-Show bei Number-Fields (oeffnet direkt im NUMBERS-Layout)
- Gegenseitiger Ausschluss mit Emoji-Picker
- `showQwertzKeyboard()` muss zusaetzlich `overlayCharactersLl.setVisibility(GONE)` und `infoCl.setVisibility(GONE)` setzen -- analog zum bestehenden `showNumberPanel()`-Pattern
- `showEmojiPicker()` muss `hideQwertzKeyboard()` statt `hideNumberPanel()` aufrufen

#### Recording-Interaktion

Das QWERTZ-Panel bleibt offen wenn der User eine Sprachaufnahme startet. Es gibt kein automatisches Schliessen bei Recording-Start. Der User kann waehrend des Recordings weiterhin ueber das Keyboard tippen oder es manuell schliessen.

### 4.6 Validierungen

#### Codebase-Analyse
- **Bestehendes Pattern gefunden**: MaterialButton + LinearLayout + weight -- exakt wie aktuelles Numpad
- **Bestehendes Pattern gefunden**: `applyPressAnimation()` und `applyButtonColor()` -- wird in KeyPressAnimator extrahiert und wiederverwendet
- **Bestehendes Pattern gefunden**: `vibrate()`, `deleteOneCharacter()`, `performEnterAction()` -- via Callback nutzbar
- **Bestehendes Pattern gefunden**: Overlay-Toggle-Pattern (showX/hideY) -- beibehalten

#### Architektur-Compliance
- Neues `keyboard/`-Package: Konsistent mit bestehendem Package-Schema (`core/`, `ai/`, `database/`)
- Kotlin fuer neue Klassen: Konsistent mit neueren Klassen (`KeyboardUiController.kt`, `RecordingManager.kt`)
- MaterialButton: Konsistent mit gesamter App (kein Android KeyboardView)

#### Dependencies
- **Betroffene Module**: Nur `app`-Modul
- **Neue Dependencies**: Keine -- nur Android SDK + Material Components (bereits vorhanden)
- **Betroffene Dateien**: `DictateInputMethodService.java`, `activity_dictate_keyboard_view.xml`, `strings.xml`

#### Breaking Changes
- **Numpad wird entfernt**: Das alte 4x4 Numpad existiert nicht mehr. Stattdessen oeffnet sich das QWERTZ-Keyboard im NUMBERS-Layout. Funktional equivalent, aber UI aendert sich.
- **Auto-Show bei Number-Fields**: Zeigt jetzt QWERTZ im Numbers-Modus statt altes Numpad. Gleiche Funktionalitaet, neues Aussehen.
- **Small Mode blendet Keyboard aus**: Im Small Mode wird das QWERTZ-Panel jetzt ausgeblendet. Das alte Numpad wurde bei Small Mode nicht ausgeblendet -- das ist bewusst neues Verhalten.
- **Keine API-Breaking Changes**: Rein UI-intern.

---

## Phase 5: Implementation Plan

### Task Group A: Daten-Schicht (keine UI-Abhaengigkeiten)

**Kann parallel zu Group B gestartet werden.**

#### Task A1: QwertzKeyDef und Enums

- **Description**: Key-Definition Datenklasse, KeyAction/ColorTier/ShiftState Enums, QwertzKeyboardLayout Enum
- **Files**:
  - CREATE `keyboard/QwertzKeyDef.kt`
  - CREATE `keyboard/QwertzKeyboardLayout.kt`
- **Requirements**: Alle Enums und die data class wie in Skeleton definiert
- **Dependencies**: Keine
- **Expected Output**: Kompilierbare Kotlin-Dateien mit allen Typdefinitionen

#### Task A2: QwertzLayoutProvider

- **Description**: Statische Layout-Definitionen fuer QWERTZ, NUMBERS, SYMBOLS
- **Files**:
  - CREATE `keyboard/QwertzLayoutProvider.kt`
- **Requirements**:
  - QWERTZ: 5 Reihen mit Zahlenreihe, Umlauten, Shift, Backspace, Space-Bar
  - NUMBERS: 4 Reihen mit Ziffern und haeufigen Sonderzeichen
  - SYMBOLS: 4 Reihen mit erweiterten Sonderzeichen
  - Shift-Varianten (lowercase/uppercase) fuer QWERTZ
  - Korrekte widthWeight-Werte fuer Sondertasten
  - `output` muss fuer jeden Key explizit gesetzt werden (Default ist null)
  - Eszett-Taste: `output = "\u00DF"`, `shiftOutput = "\u00DF"` (bleibt ß auch bei Shift)
- **Dependencies**: A1 (braucht QwertzKeyDef)
- **Expected Output**: `getLayout()` gibt korrekte Key-Listen zurueck

#### Task A3: AcceleratingRepeatHandler

- **Description**: Wiederverwendbarer Handler fuer beschleunigtes Wiederholen bei Long-Press (Backspace)
- **Files**:
  - CREATE `keyboard/AcceleratingRepeatHandler.kt`
- **Requirements**:
  - `start(action: () -> Unit)`: Startet wiederholtes Ausfuehren mit beschleunigender Rate
  - `stop()`: Stoppt alle pending Callbacks
  - Beschleunigungskurve: 50ms -> 25ms (nach 1.5s) -> 10ms (nach 3s) -> 5ms (nach 5s)
  - Extrahiert aus bestehender Logik in DictateInputMethodService (Zeilen 545-571)
  - Wird sowohl vom QWERTZ-Controller als auch vom bestehenden Backspace-Button genutzt
- **Dependencies**: Keine
- **Expected Output**: Wiederverwendbare, testbare Handler-Klasse

#### Task A4: CursorSwipeTouchHandler

- **Description**: Wiederverwendbarer Touch-Handler fuer Cursor-Bewegung per horizontalem Swipe
- **Files**:
  - CREATE `keyboard/CursorSwipeTouchHandler.kt`
- **Requirements**:
  - Implementiert `View.OnTouchListener`
  - Swipe-Erkennung mit Threshold
  - Delegiert Tap an `onTap()`, horizontale Bewegung an `onCursorMove(direction)`
  - Extrahiert aus bestehender Space-Button-Logik (Zeilen 729-772)
  - Wird sowohl vom QWERTZ-Space als auch vom bestehenden Space-Button genutzt
- **Dependencies**: Keine
- **Expected Output**: Wiederverwendbare, testbare Handler-Klasse

#### Task A5: KeyPressAnimator

- **Description**: Extrahierte Press-Animation-Logik fuer Keyboard-Buttons
- **Files**:
  - CREATE `keyboard/KeyPressAnimator.kt`
- **Requirements**:
  - `applyPressAnimation(view: View)`: Setzt OnTouchListener fuer Scale-Animation
  - `animateKeyPress(view: View, pressed: Boolean)`: Scale auf 0.92f (press) / 1.0f (release)
  - `animationsEnabled: Boolean` als Constructor-Parameter
  - Konstanten: `KEY_PRESS_SCALE = 0.92f`, `KEY_PRESS_ANIM_DURATION = 80L`
  - Ersetzt die privaten Methoden in DictateInputMethodService
  - Wird von QwertzKeyboardView UND DictateInputMethodService genutzt
- **Dependencies**: Keine
- **Expected Output**: Wiederverwendbare Animator-Klasse

### Task Group B: View-Schicht

**Kann parallel zu Group A gestartet werden (nutzt nur Interface-Definitionen).**

#### Task B1: QwertzKeyboardView

- **Description**: Custom LinearLayout das Keyboard aus Key-Definitionen rendert
- **Files**:
  - CREATE `keyboard/QwertzKeyboardView.kt`
- **Requirements**:
  - `@JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0)`
  - `buildLayout(keys: List<List<QwertzKeyDef>>)`: Baut UI komplett neu
  - Jede Reihe: Horizontales LinearLayout, `MATCH_PARENT` breit, fixe Hoehe `42dp`
  - Jeder Key: MaterialButton mit `layout_weight = keyDef.widthWeight`
  - `minHeight=0dp`, `minWidth=0dp`, `margin=2dp`
  - Text ODER Icon je nach KeyDef
  - Click-Listener delegiert an `KeyActionCallback`
  - Long-Press und Release fuer repeatable Keys delegiert an `onKeyLongPress()` / `onKeyReleased()`
  - Nutzt `KeyPressAnimator`-Instanz fuer Press-Animationen (empfaengt `animationsEnabled` von aussen)
  - `applyColors()` setzt Farben nach ColorTier
  - `updateShiftVisuals()` aendert Buchstaben-Case und Shift-Icon
  - Reihe 2 (ASDF) bekommt halbes Padding links fuer versetztes Layout
- **Dependencies**: A1 (Interface-Definition von QwertzKeyDef), A5 (KeyPressAnimator)
- **Expected Output**: View das ein Keyboard visuell korrekt rendert

#### Task B2: Drawable-Ressourcen

- **Description**: Icons fuer Shift, Caps Lock, Tab
- **Files**:
  - CREATE `res/drawable/ic_keyboard_shift_24.xml`
  - CREATE `res/drawable/ic_keyboard_shift_filled_24.xml`
  - CREATE `res/drawable/ic_keyboard_tab_24.xml`
- **Requirements**: Material Design Icons, 24dp, schwarze Farbe (wird per Tint ueberschrieben)
- **Dependencies**: Keine
- **Expected Output**: Valide Vector-Drawables

#### Task B3: String-Ressourcen

- **Description**: Neue Strings fuer QWERTZ-Keyboard
- **Files**:
  - MODIFY `res/values/strings.xml`
  - MODIFY `res/values-de/strings.xml`
- **Requirements**: Labels fuer 123-Button, ABC-Button, Shift-Tooltip, etc.
- **Dependencies**: Keine
- **Expected Output**: Neue String-Eintraege in beiden Sprachen

### Task Group C: Controller + State-Machine

**Abhaengig von A1, A2, B1.**

#### Task C1: QwertzKeyboardController

- **Description**: State-Machine und Input-Handler
- **Files**:
  - CREATE `keyboard/QwertzKeyboardController.kt`
- **Requirements**:
  - Implementiert `QwertzKeyboardView.KeyActionCallback`
  - ShiftState-Management (OFF -> SINGLE -> CAPS_LOCK -> OFF)
  - Ctrl-Modifier-State
  - Layout-Switching (QWERTZ <-> NUMBERS <-> SYMBOLS)
  - Character-Input mit Shift/Ctrl-Handling (inkl. `shiftOutput` fuer ß)
  - Backspace: Delegiert Long-Press an `AcceleratingRepeatHandler`
  - Space: Delegiert Swipe an `CursorSwipeTouchHandler`
  - Tab-Eingabe
  - `applyColors()` Delegation
  - `setLayout()` fuer externen Aufruf (z.B. Auto-Show Numbers)
- **Dependencies**: A1, A2, A3, A4, B1
- **Expected Output**: Vollstaendiger Controller der alle Keyboard-Interaktionen handelt

### Task Group D: Integration

**Abhaengig von C1.**

#### Task D1: Layout-XML Anpassung

- **Description**: Numpad-XML entfernen, QWERTZ-Container einfuegen
- **Files**:
  - MODIFY `res/layout/activity_dictate_keyboard_view.xml`
- **Requirements**:
  - `numbers_panel_cl` ConstraintLayout komplett entfernen (Zeilen 434-698)
  - Neues FrameLayout `qwertz_keyboard_container` an gleicher Stelle einfuegen
  - Gleiche Constraints wie altes Numpad (top-to-top, bottom-to-bottom, start-to-start, end-to-end)
  - Visibility `gone` als Default
  - Hoehe `wrap_content` (wird vom QwertzKeyboardView bestimmt)
- **Dependencies**: Keine (kann parallel zu C1)
- **Expected Output**: Valides Layout-XML ohne Numpad, mit QWERTZ-Container

#### Task D2a: QWERTZ-Felder und Initialisierung

- **Description**: QWERTZ-View und Controller in DictateInputMethodService einrichten
- **Files**:
  - MODIFY `core/DictateInputMethodService.java`
- **Requirements**:
  - **Hinzufuegen**: QwertzKeyboardView + QwertzKeyboardController Felder
  - **onCreateInputView()**: QWERTZ-View erstellen, Controller initialisieren, in Container einfuegen
  - **Theming-Block**: `qwertzContainer.setBackgroundColor()` und `qwertzController.applyColors()` ergaenzen
  - **initializeKeyPressAnimations()**: Auf `KeyPressAnimator`-Instanz umstellen (shared mit QWERTZ-View)
- **Dependencies**: C1, D1
- **Expected Output**: QWERTZ-View wird erstellt und in den Container eingefuegt

#### Task D2b: Toggle/Show/Hide-Methoden

- **Description**: Neue Toggle-Methoden fuer QWERTZ-Keyboard, Overlay-Ausschluss-Logik
- **Files**:
  - MODIFY `core/DictateInputMethodService.java`
- **Requirements**:
  - `toggleQwertzKeyboard()`, `showQwertzKeyboard()`, `hideQwertzKeyboard()` implementieren
  - `showQwertzKeyboard()` muss setzen: `emojiPickerCl.setVisibility(GONE)`, `overlayCharactersLl.setVisibility(GONE)`, `infoCl.setVisibility(GONE)`
  - `showEmojiPicker()` aendern: `hideNumberPanel()` -> `hideQwertzKeyboard()`
  - **editNumbersButton onClick**: `toggleQwertzKeyboard()` statt `toggleNumberPanel()`
  - **shouldAutomaticallyShowNumberPanel()**: Beibehalten, ruft `showQwertzKeyboard(NUMBERS)` auf
  - **applySmallMode()**: QWERTZ-Panel bei SmallMode ausblenden
  - **onFinishInputView()**: QWERTZ-Panel schliessen -- alle drei `numbersPanelCl`-Referenzen ersetzen (Recording-State, Pipeline-State, Idle-Cleanup)
  - QWERTZ-Panel bleibt offen wenn User Sprachaufnahme startet (kein automatisches Schliessen bei Recording-Start)
- **Dependencies**: D2a
- **Expected Output**: Toggle-Logik funktioniert, gegenseitiger Ausschluss mit Emoji-Picker und Overlay-Characters

**Migrationspunkte `numbersPanelCl` in `onFinishInputView()`:**
- Recording-State Cleanup (ca. Zeile 944-945)
- Pipeline-State Cleanup (ca. Zeile 951-952)
- Idle-Cleanup (ca. Zeile 970-971)

Alle `numbersPanelCl`-Referenzen im File systematisch pruefen und ersetzen.

#### Task D2c: Numpad-Code entfernen

- **Description**: Alten Numpad-Code aus DictateInputMethodService entfernen
- **Files**:
  - MODIFY `core/DictateInputMethodService.java`
- **Requirements**:
  - **Entfernen**: numberPanelButtons, numbersPanelCl, numbersPanelTitleTv, numbersPanelCloseButton, collectNumberPanelButtons(), commitNumberPanelValue(), toggleNumberPanel(), showNumberPanel(), hideNumberPanel()
  - **initializeKeyPressAnimations()**: `numbersPanelCloseButton` entfernen
  - Alle verbleibenden `numbersPanelCl`-Referenzen entfernen
- **Dependencies**: D2b
- **Expected Output**: Kein Numpad-Code mehr im Service

#### Task D2d: Kompiliertest

- **Description**: Sicherstellen dass das Projekt nach der Integration kompiliert
- **Files**:
  - Alle modifizierten Dateien
- **Requirements**:
  - `./gradlew assembleDebug` muss erfolgreich durchlaufen
  - Keine neuen Lint-Warnungen
- **Dependencies**: D2c
- **Expected Output**: Erfolgreiches Build

#### Task D3: Feinschliff und Edge Cases

- **Description**: Edge Cases, Polish, Accessibility
- **Files**:
  - MODIFY `keyboard/QwertzKeyboardController.kt`
  - MODIFY `keyboard/QwertzKeyboardView.kt`
  - MODIFY `core/DictateInputMethodService.java`
- **Requirements**:
  - Auto-Shift: Erster Buchstabe nach Satzende (nach `. ` oder `! ` oder `? `) automatisch Shift SINGLE
  - Content-Description fuer Icon-Buttons (Accessibility)
  - Keyboard schliessen wenn App in Background geht
  - Ctrl-Button visuell hervorheben wenn aktiv (accentColor statt accentColorDark)
  - Shift-Button visuell: Outline-Icon (OFF), Filled-Icon + accentColor (SINGLE), Filled-Icon + Unterstrich (CAPS_LOCK)
- **Dependencies**: D2d
- **Expected Output**: Polished, zugaengliches Keyboard

### Abhaengigkeits-Graph

```
A1 ──────┬──> A2 ──────────────────┐
         │                         │
         ├──> B1 ◄── A5            ├──> C1 ──> D2a ──> D2b ──> D2c ──> D2d ──> D3
         │                         │
A3 (parallel) ─────────────────────┘
A4 (parallel) ─────────────────────┘
A5 (parallel) ─────────────────────┘
B2 (parallel) ─────────────────────┘
B3 (parallel) ─────────────────────┘
D1 (parallel) ──────────────────────────────┘
```

**Parallelisierbare Gruppen:**
- **Wave 1**: A1, A3, A4, A5, B2, B3, D1 (alle unabhaengig)
- **Wave 2**: A2, B1 (A2 braucht A1, B1 braucht A1 + A5)
- **Wave 3**: C1 (braucht A1, A2, A3, A4, B1)
- **Wave 4**: D2a (braucht C1, D1)
- **Wave 5**: D2b (braucht D2a)
- **Wave 6**: D2c (braucht D2b)
- **Wave 7**: D2d (braucht D2c)
- **Wave 8**: D3 (braucht D2d)

---

## Architektur-Entscheidungen

| Entscheidung | Begruendung |
|-------------|-------------|
| **Programmatisches Layout statt XML** | Key-Layouts aendern sich dynamisch (QWERTZ/NUMBERS/SYMBOLS, Shift-Varianten). XML waere statisch und wuerde 6+ separate Layout-Dateien benoetigen. |
| **QwertzKeyDef als data class** | Deklarative Layout-Definition statt imperativer View-Erstellung. Einfach testbar, erweiterbar. |
| **Separates `keyboard/` Package** | God-Class-Vermeidung. DictateInputMethodService bleibt Integrations-Punkt, Keyboard-Logik ist isoliert. |
| **Kotlin statt Java** | Konsistent mit neueren Klassen im Projekt. Data classes, when-Expressions, null-safety machen den Code kompakter. |
| **MaterialButton beibehalten** | Bestehender Standard der App. Keine Abhaengigkeit von Android KeyboardView (deprecated) oder Jetpack Compose (noch nicht im Projekt). |
| **ColorTier-Enum statt einzelne Farben** | Entkoppelt Layout-Definition von konkreten Farbwerten. Theming bleibt beim Service. |
| **Callbacks statt direkte Service-Referenz** | Controller bekommt Lambdas statt InputMethodService-Instanz. Testbar, keine zyklische Abhaengigkeit. |
| **Kein Long-Press-Popup fuer alternative Zeichen** | Out of Scope, Umlaute sind direkt im Layout. Kann spaeter ergaenzt werden. |
| **3 Layout-Modi statt 2** | NUMBERS fuer haeufige Sonderzeichen, SYMBOLS fuer seltene. Konsistent mit SwiftKey-Pattern. |
| **Touch-Logik in wiederverwendbare Handler extrahiert** | AcceleratingRepeatHandler und CursorSwipeTouchHandler vermeiden Code-Duplizierung zwischen bestehendem Service und QWERTZ-Controller. Unabhaengig testbar. |
| **Press-Animation als eigene Klasse** | KeyPressAnimator wird von QwertzKeyboardView und DictateInputMethodService geteilt. Vermeidet DRY-Verstoss und reduziert God-Class. |
| **KeyActionCallback statt KeyActionHandler** | Konsistent mit bestehendem Naming-Pattern (RecordingCallback, PipelineCallback). Erweitert um onKeyLongPress/onKeyReleased fuer Touch-Interaktionen. |
| **Eszett bleibt bei Shift unveraendert** | `shiftOutput`-Feld erlaubt pro-Key Shift-Overrides. ß -> ß (keine Transformation zu SS). |

---

## Breaking Changes

1. **Numpad-UI komplett ersetzt**: Das 4x4-Grid Numpad wird durch ein vollstaendiges QWERTZ-Keyboard mit Numbers-Modus ersetzt. Funktional gleich (Ziffern, Komma, Punkt, Enter, Backspace, Space), aber komplett neues Aussehen.
2. **Panel-Hoehe aendert sich**: Altes Numpad war 320dp fest. Neues QWERTZ ist ~220dp (5 Reihen) im QWERTZ-Modus, ~180dp (4 Reihen) im NUMBERS-Modus.
3. **Numpad-Titel entfaellt**: Das alte Panel hatte einen "Nummernblock" Titel mit Close-Button. Das neue Keyboard hat keinen Titel -- stattdessen einen ABC/123-Toggle-Button innerhalb des Layouts.
4. **Close-Mechanismus aendert sich**: Altes Numpad hatte einen X-Close-Button oben rechts. Neues Keyboard wird ueber den bestehenden Toggle-Button in der Edit-Leiste geschlossen (gleicher Button der es oeffnet).

---

## Testing-Strategie (Manuell)

### Unit-Tests (optional, koennen spaeter ergaenzt werden)

- `QwertzLayoutProvider`: Alle Layouts haben korrekte Reihenanzahl, keine doppelten Keys
- `QwertzKeyboardController`: ShiftState-Uebergaenge, Ctrl-Combo-Mapping

### Manuelle UI-Tests

| Test | Schritte | Erwartung |
|------|----------|-----------|
| QWERTZ oeffnen | Numbers-Button in Edit-Leiste tippen | QWERTZ-Panel erscheint |
| Buchstabe tippen | "a" tippen | "a" im Textfeld |
| Shift + Buchstabe | Shift tippen, dann "a" | "A" im Textfeld, Shift zurueck auf OFF |
| Caps Lock | Shift 2x tippen, dann "abc" | "ABC" im Textfeld |
| Caps Lock aus | Shift 3x tippen, dann "a" | "a" im Textfeld |
| 123-Modus | 123-Button tippen | Numbers-Layout erscheint |
| Zurueck zu ABC | ABC-Button tippen | QWERTZ-Layout erscheint |
| Symbols-Modus | Im Numbers-Layout "=<" tippen | Symbols-Layout |
| Backspace | Text eingeben, Backspace tippen | Letztes Zeichen geloescht |
| Backspace Long-Press | Backspace halten | Zeichen werden beschleunigt geloescht |
| Enter | Enter tippen | Zeilenumbruch oder IME-Action |
| Space | Space tippen | Leerzeichen |
| Space Swipe | Space halten und nach rechts ziehen | Cursor bewegt sich nach rechts |
| Ctrl+A | Ctrl, dann A | Alles selektiert |
| Ctrl+C/V | Text selektieren, Ctrl+C, Cursor bewegen, Ctrl+V | Text kopiert und eingefuegt |
| Tab | Tab-Taste tippen | Tab-Zeichen eingefuegt |
| Umlaute | ue, oe, ae Tasten | Korrekte Umlaute |
| Theming | Akzentfarbe in Settings aendern | Keyboard-Farben passen sich an |
| Dark Mode | Theme auf Dark setzen | Dunkler Hintergrund |
| Auto-Show Numbers | Number-Feld fokussieren | QWERTZ oeffnet sich im Numbers-Modus |
| Emoji-Toggle | Emoji-Button, dann Numbers-Button | Emoji schliesst, QWERTZ oeffnet |
| Small Mode | Small-Mode aktivieren | QWERTZ-Panel verschwindet |
| Press-Animation | Taste druecken | Scale-Animation sichtbar |
| Vibration | Taste tippen (Vibration an) | Haptisches Feedback |
| Auto-Shift nach Satzende | ". " tippen, dann Buchstabe | Erster Buchstabe automatisch gross |
| Eszett bei Shift | Shift aktivieren, ß tippen | ß bleibt ß (keine Transformation zu SS) |
| QWERTZ waehrend Recording | QWERTZ oeffnen, Recording starten | Panel bleibt offen |
| TalkBack/Accessibility | TalkBack aktivieren, Shift-Button fokussieren | Content-Description wird vorgelesen |
| Home-Button bei QWERTZ offen | QWERTZ oeffnen, Home-Button druecken | Keyboard schliesst sich |
| Overlay-Characters-Ausschluss | Overlay-Characters sichtbar, QWERTZ oeffnen | Overlay-Characters verschwindet |
