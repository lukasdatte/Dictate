<!-- EXECUTION-PLAN -->
## Execution Plan

**Erstellt:** 2026-03-12 15:00
**Geschätzte Chunks:** 1

### Meine Strategie

Der Plan ist kompakt (135 Zeilen) und die 4 Schritte haben starke Abhängigkeiten — besonders `DictateInputMethodService.java` wird in allen 4 Schritten geändert. Deshalb ein einziger Chunk mit folgender Reihenfolge: XML-Layout → Kotlin-Modelle → Kotlin-Controller → Java-Service.

### Geplante Chunks

| # | Chunk | Plan-Abschnitte | Warum diese Gruppierung? |
|---|-------|-----------------|--------------------------|
| 1 | Komplette UI Integration | §1-§4 (Zeilen 21-107) | 135 Zeilen, starke Abhängigkeiten, DictateInputMethodService als zentraler Berührungspunkt |

### Implementierungsreihenfolge innerhalb des Chunks

1. `activity_dictate_keyboard_view.xml` — Container wrappen (Basis für alles)
2. `QwertzKeyDef.kt` — CLOSE_KEYBOARD enum (braucht QwertzLayoutProvider)
3. `QwertzLayoutProvider.kt` — Close-Button in alle Layouts
4. `QwertzKeyboardController.kt` — Close-Callback + Handling
5. `KeyboardUiController.kt` — RECORDING_INDICATOR Mode
6. `DictateInputMethodService.java` — Alles zusammenführen

### Abhängigkeiten & Risiken

- **Schritt 1 → 3:** Prompt-Bar muss im XML über dem QWERTZ-Keyboard bleiben
- **Schritt 2 → 4:** KeyAction.CLOSE_KEYBOARD muss existieren bevor Controller es nutzt
- **Risiko:** Constraint-Anpassungen bei den 9 losen Buttons — müssen sorgfältig auf `parent` umgestellt werden

---
<!-- /EXECUTION-PLAN -->

# QWERTZ Keyboard UI Integration Fix

## Context

Das QWERTZ-Keyboard (Commit `d6e2b76`) hat 4 UI-Integrationsprobleme:
1. Alte Buttons bleiben sichtbar hinter dem Keyboard
2. Kein Schließen-Button
3. Kein Recording-Indikator wenn Keyboard offen
4. Kein Pipeline-Progress wenn Keyboard offen

**Ursache:** Die 9 Main-Buttons (record, space, enter, backspace, switch, small_mode, resend, pause, trash) liegen **lose im Root-ConstraintLayout** ohne Container. Das QWERTZ-Keyboard überlagert sie nur mit `bringToFront()` + `wrap_content` Höhe — es verdeckt nicht alles.

**Referenz:** Das alte Numpad (`numbers_panel_cl`) hatte 320dp feste Höhe, Constraints zu allen 4 Ecken, einen Titel + Close-Button. Es überdeckte die Fläche komplett. Das neue QWERTZ-Keyboard folgt dem gleichen Floating-Pattern, aber ohne Close-Button und mit `wrap_content` statt fester Höhe.

**Worktree:** `/home/lukas/WebStorm/Dictate/worktrees/feature/session-persistierung`

---

## Lösung: 4 Schritte

### 1. Main-Buttons in Container wrappen + QWERTZ-Constraint anpassen

**Datei:** `app/src/main/res/layout/activity_dictate_keyboard_view.xml`

Die 9 losen Buttons in ein neues `ConstraintLayout` wrappen:

```xml
<androidx.constraintlayout.widget.ConstraintLayout
    android:id="@+id/main_buttons_cl"
    android:layout_width="0dp"
    android:layout_height="0dp"
    app:layout_constraintTop_toBottomOf="@id/edit_buttons_keyboard_ll"
    app:layout_constraintBottom_toBottomOf="parent"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintEnd_toEndOf="parent">

    <!-- small_mode_btn, record_btn, resend_btn, backspace_btn -->
    <!-- switch_btn, trash_btn, space_btn, pause_btn, enter_btn -->
    <!-- Constraints bleiben gleich, nur parent-Referenzen anpassen -->

</androidx.constraintlayout.widget.ConstraintLayout>
```

**Constraint-Anpassungen der Buttons:**
- `small_mode_btn`: `top_toBottomOf="@id/edit_buttons_keyboard_ll"` → `top_toTopOf="parent"` (da parent jetzt `main_buttons_cl` ist)
- `record_btn`: analog
- `backspace_btn`: analog
- `switch_btn`, `enter_btn`: `bottom_toBottomOf="parent"` bleibt (parent ist jetzt der Container)
- Alle `16dp` Margins bleiben

**QWERTZ-Container Constraint:**
- Aktuell: `top_toTopOf="parent"` → ganzer Bildschirm
- Neu: `top_toBottomOf="@id/prompts_keyboard_cl"` → sitzt UNTER der Prompt-Bar
- So bleibt `prompts_keyboard_cl` sichtbar über dem QWERTZ-Keyboard

**Datei:** `DictateInputMethodService.java`
- Neues Feld: `private View mainButtonsCl;`
- In `onCreateInputView()`: `mainButtonsCl = dictateKeyboardView.findViewById(R.id.main_buttons_cl);`
- `showQwertzKeyboard()`: `mainButtonsCl.setVisibility(View.GONE);` + `editButtonsKeyboardLl.setVisibility(View.GONE);`
- `hideQwertzKeyboard()`: Restore basierend auf `isSmallMode`

### 2. Dedizierter Close-Button (Bottom-Left in allen Layouts)

**Datei:** `QwertzKeyDef.kt`
- Neuer `KeyAction`: `CLOSE_KEYBOARD`

**Datei:** Neues Drawable `app/src/main/res/drawable/ic_keyboard_hide_24.xml`
- Material Design "keyboard_hide" Icon (Keyboard mit Pfeil nach unten)
- Hinweis: `switch_btn` im Haupt-Layout nutzt bereits `ic_baseline_keyboard_hide_24` — dieses Drawable wiederverwenden!

**Datei:** `QwertzLayoutProvider.kt`
- In **allen 3 Layouts** (QWERTZ, NUMBERS, SYMBOLS): Close-Button als erste Taste der Bottom-Row
- Layout wird: `[↓] [123] [Ctrl] [Tab] [Space] [.] [Enter]`
- KeyDef: `QwertzKeyDef(label = "", keyAction = KeyAction.CLOSE_KEYBOARD, widthWeight = 1f, iconResId = R.drawable.ic_baseline_keyboard_hide_24, colorTier = ColorTier.DARK)`

**Datei:** `QwertzKeyboardController.kt`
- Neuer Constructor-Parameter: `onCloseKeyboard: () -> Unit`
- In `onKeyAction()`: `KeyAction.CLOSE_KEYBOARD -> { vibrate(); onCloseKeyboard() }`

**Datei:** `DictateInputMethodService.java`
- Controller-Erstellung: `this::hideQwertzKeyboard` als Close-Callback übergeben

### 3. Prompt-Bar sichtbar lassen (Recording + Pipeline automatisch)

Da `showQwertzKeyboard()` jetzt `mainButtonsCl` und `editButtonsKeyboardLl` versteckt aber **NICHT** `prompts_keyboard_cl`, bleibt die Prompt-Bar automatisch sichtbar über dem QWERTZ-Keyboard.

**Was das bedeutet:**
- **Prompt-Buttons** (RecyclerView): Sichtbar über dem Keyboard → User kann Prompts sehen
- **Pipeline-Progress** (Steps + Timer): Automatisch sichtbar wenn Pipeline läuft → `KeyboardUiController` wechselt bereits zwischen Prompt-Buttons und Pipeline-Steps
- **Kein zusätzlicher Code nötig** für Pipeline-Progress!

**Für `prompts_keyboard_cl` Sichtbarkeit sicherstellen:**
- `showQwertzKeyboard()` muss `promptsCl.setVisibility(View.VISIBLE)` setzen (falls es `gone` war)
- `hideQwertzKeyboard()` muss den ursprünglichen Zustand wiederherstellen

### 4. Recording-Indikator in Prompt-Bar

**Datei:** `KeyboardUiController.kt`
- Neuer `PromptAreaMode`: `RECORDING_INDICATOR`
- Neues View in `PipelineViews`: ein programmatisch erstelltes Recording-View (roter blinkender Punkt + "Aufnahme..." Text)
- `showRecordingIndicator()`: Versteckt RecyclerView, zeigt Recording-View
- `setMode(PROMPT_BUTTONS)` stellt Normalzustand wieder her

**Datei:** `DictateInputMethodService.java`
- Bei Recording-Start UND QWERTZ sichtbar: `uiController.showRecordingIndicator()`
- Bei Recording-Stop: `uiController.setMode(PROMPT_BUTTONS)` (Pipeline-Start wechselt dann automatisch zu `PIPELINE_PROGRESS`)

---

## Dateien-Übersicht

| Datei | Änderung |
|-------|----------|
| `activity_dictate_keyboard_view.xml` | 9 Buttons in `main_buttons_cl` wrappen; QWERTZ top-Constraint ändern |
| `QwertzKeyDef.kt` | `CLOSE_KEYBOARD` KeyAction |
| `QwertzLayoutProvider.kt` | Close-Button in alle 3 Layouts |
| `QwertzKeyboardController.kt` | Close-Callback + CLOSE_KEYBOARD handling |
| `KeyboardUiController.kt` | `RECORDING_INDICATOR` Mode |
| `DictateInputMethodService.java` | Container-Feld, show/hide-Logik, Close-Callback, Recording-Status |

Kein neues Drawable nötig — `ic_baseline_keyboard_hide_24` existiert bereits.

---

## Verifikation

1. **Build:** `./gradlew assembleDebug`
2. **Auf Gerät testen:**
   - QWERTZ öffnen → keine alten Buttons sichtbar, Prompt-Bar sichtbar darüber
   - Close-Button (↓) drücken → Keyboard schließt, alte UI kehrt zurück
   - Recording starten mit QWERTZ offen → roter Punkt + "Aufnahme..." in Prompt-Bar
   - Recording stoppen → Pipeline-Progress erscheint in Prompt-Bar
   - Pipeline fertig → Prompt-Buttons kehren zurück
   - Small-Mode → QWERTZ schließt sich
