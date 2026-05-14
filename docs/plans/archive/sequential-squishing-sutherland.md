# Plan: Single-Row-Modus + Audio-Toggle + State-Machine-Konsolidierung

**Repo:** `/home/lukas/WebStorm/Dictate`
**Branch:** `feature/language-chip-curation`
**Datum:** 2026-05-05 (überarbeitet nach Plan-Quality-Gate, alle 34 Findings eingearbeitet)

---

<!-- EXECUTION-PLAN -->
## Execution Plan

**Erstellt:** 2026-05-05 18:50
**Geplante Chunks:** 3 (Standard-Modus, 502 Plan-Zeilen)

### Strategie

Die Plan-Reihenfolge (Block 0 → 3a → 3b → 3c → 2 → 1) ist aus *Risiko-Sicht* korrekt geordnet, aber als Chunk-Aufteilung zu fein. Drei fachliche Chunks kapseln zusammenhängende Test-Surfaces und Producer-Consumer-Paare, ohne die Plan-interne Reihenfolge zu verletzen. Schichtweise Aufteilung (Domain/UI/Tests separat) wäre falsch, weil ein IME keine REST-Layer-Architektur hat — fachliche Features sind die natürlichen Sustainability-Grenzen.

### Geplante Chunks

| # | Chunk | Plan-Abschnitte | Warum diese Gruppierung? |
|---|-------|-----------------|--------------------------|
| 1 | Foundation: Strukturelle Vorbereitung + State-Cleanup | Block 0a-0g, Block 3a, Block 3b | Alle Änderungen sind "Skeleton + tote Felder weg" — keine user-facing Funktionalität, keine Test-Risiko-Erhöhung über die bestehenden 170+ Tests hinaus. 3a/3b operieren auf demselben Service wie 0c (Callback-Interface umstellen), Mergeschmerz wird minimiert. |
| 2 | Audio-Focus-Toggle + Live-Hook | Block 2, Block 3c | 3c ist die Producer-Seite der `setAudioFocusRuntime`-API; Block 2 ist deren einziger Consumer. Sie zusammen zu landen heißt: Tests gegen `FakeAudioFocusGate` werden gemeinsam mit ihrer ersten produktiven Aufruf-Stelle geschrieben — kein "im Vacuum"-Test. `audio_focus_btn` wird bereits hier angelegt (initial `gone`), damit Chunk 3 ihn nur sichtbar schalten muss (V-3-Klarstellung). |
| 3 | Single-Row-Modus | Block 1 | Größte und am stärksten isolierbare UI-Änderung (Re-Parenting + ConstraintSet-Switching + Animation + Lifecycle). Eigener Chunk mit eigenem Test-Surface (`KeyboardLayoutModeController`). Manuelle Geräte-Verifikation am Ende gebündelt → spart Geräte-Roundtrips. Baut auf `audio_focus_btn` aus Chunk 2 (Modus-Matrix referenziert ihn). |

### Abhängigkeiten

```
Chunk 1 (Foundation)
     │
     ├──> Chunk 2 (Audio-Toggle + Live-Hook)
     │         │
     │         ▼
     └──> Chunk 3 (Single-Row-Modus)
```

Strikt sequentiell — keine Parallelisierung sinnvoll.

### Risiken

- **Chunk 1 → 2:** `KeyboardViews`-DTO-Änderungen aus 0a brechen die Service-Konstruktion. Block-0 muss als Atom landen, sonst dazwischen kein Build.
- **Chunk 2 → 3:** Der `audio_focus_btn` aus Chunk 2 muss in `action_row` existieren, sonst kann Chunk 3 ihn nicht referenzieren.
- **Branch-Naming:** Branch heißt noch `feature/language-chip-curation` — thematisch passt das nicht mehr. Wenn lokal noch nicht gepusht: optional umbenennen am Ende.

---
<!-- /EXECUTION-PLAN -->

## Context

Der Sprach-Pill-Refactor (`language-chip-curation`) ist implementiert und ins Archiv verschoben. Auf dem Gerät steht jetzt eine kompakte 2-Letter-Pill in der Prompts-Leiste, optisch in die normalen Pills integriert. Damit ist die Long-Press-Funktion auf `edit_numbers_btn` (bisher: Sprache cyclen) **funktional redundant** und kann freigegeben werden.

Drei Verbesserungen sollen in dasselbe Feature-Branch:

1. **Single-Row-Modus** — Long-Press auf `edit_numbers_btn` aktiviert ein Layout, in dem die zwei Button-Reihen (`action_row` + `input_row`) zu einer dichteren Reihe zusammengezogen werden. Reihenfolge: `[🗑] [🎤] [Space] [⏸] [⌫] [↵] [↻] [🔊]`.
2. **Audio-Focus-Toggle** — Direktzugriffs-Button (Lautsprecher-Icon), der `Pref.AudioFocus` persistent umschaltet UND laufende Recording-Sessions sofort beeinflusst. Erreichbar in der Edit-Bar oben (immer) und in der Single-Row (wenn aktiv).
3. **State-Machine-Konsolidierung** — Entfernung des praktisch toten `isPreparing`-Felds + Service-Field `audioFocusEnabled` (Doppel-State); neue `setAudioFocusRuntime()`-Methode auf `RecordingStateController` für Mid-Recording-Override.

---

## Strukturelle Vorbereitung (Block 0, vor Block 1)

Diese Refactors werden VON allen drei Blöcken benötigt. Sie werden vorab ausgeführt — bricht man sie auf die Blöcke 1/2 auf, entstehen unaufhebbare Build-Reihenfolgen-Konflikte.

### 0a — `KeyboardViews`-DTO erweitern

`KeyboardStateManager.kt` Z. 23-36. Aktuelles DTO listet nur Visibility-Container. Für Re-Parenting + Layout-Mode-Switching werden zusätzlich benötigt:

```kotlin
data class KeyboardViews(
    // bestehend …
    val mainButtonsCl: ConstraintLayout,
    val actionRow: ConstraintLayout,        // NEU
    val inputRow: ConstraintLayout,         // NEU
    val recordPulseLayout: View,            // NEU — Wrapper, NICHT recordButton
    val trashButton: MaterialButton,        // bestehend
    val pauseButton: MaterialButton,        // bestehend
    val spaceButton: MaterialButton,        // NEU
    val backspaceButton: MaterialButton,    // NEU
    val enterButton: MaterialButton,        // NEU
    val resendButton: MaterialButton,       // NEU
    val audioFocusButtonInRow: MaterialButton, // NEU — Single-Row-Variante
    // …
)
```

**Wichtig — PulseLayout-Wrapping:** Re-Parenting bewegt den **Wrapper** `record_pulse_layout`, NICHT den nackten `record_btn`. Andernfalls bricht die Pulse-Animation, weil ihr Container-Reference-Point verloren geht.

DI-Anpassung: Service Z. 481-484 (`new KeyboardViews(...)`) muss um die neuen Parameter erweitert werden.

### 0b — `MainButtonViews`-DTO + Theming + KeyPress-Animation erweitern

`MainButtonsController.kt`. Zwei neue Buttons brauchen vollen Lifecycle-Anschluss:

- **Field**: `editAudioFocusButton: MaterialButton` (Edit-Bar) + `audioFocusButton: MaterialButton` (Single-Row).
- **`applyTheme(accentColor)`** Z. 314-336: beide Buttons mit `applyButtonColor(button, accentMedium)` einfärben (analog `pauseButton`).
- **`initializeKeyPressAnimations()`** Z. 273-285: beide Buttons in die Tap-Down-Liste aufnehmen.

### 0c — `MainButtonsController.Callback`-Interface erweitern

`MainButtonsController.kt` Z. 46-68. Drei Methoden ändern sich:

- **Entfernen:** `fun onLanguageCycled()` (komplett).
- **Hinzufügen:** `fun onSingleRowModeToggled()`.
- **Hinzufügen:** `fun onAudioFocusToggled()`.

Verifiziert: `onLanguageCycled` hat genau 3 Touch-Points (Interface-Decl Z. 66, Long-Click-Listener Z. 90-94, Service-Implementierung Z. 2549-2568). Keine Hardware-Tasten-Aufrufer.

### 0d — `KeyboardLayoutModeController` als neue Klasse

**SRP-Argument** (Quality-Gate K9): `KeyboardStateManager` ist explizit als "Deterministic visibility calculator" definiert (KDoc Z. 11-22). Layout-Mode-Verwaltung mit `ConstraintSet`-Switching, View-Re-Parenting und `TransitionManager`-Animation gehört nicht hinein.

Neue Datei: `app/src/main/java/net/devemperor/dictate/core/KeyboardLayoutModeController.kt`. Rolle:

- Hält `csTwoRowAction`, `csTwoRowInput`, `csSingleRow` (drei ConstraintSets, siehe Block 1).
- Bietet `setSingleRowMode(enabled: Boolean, animate: Boolean)`.
- Kapselt Re-Parenting (`removeView` / `addView`), `TransitionManager`-Aufruf (mit `Pref.Animations`-Check), und das initiale Apply nach `onCreateInputView`.

`KeyboardStateManager` bleibt damit pure-logic; `applyVisibility()` ruft `layoutModeController.refresh()` am Ende, das ist die einzige Brücke.

### 0e — `Pref.SingleRowMode` anlegen

`DictatePrefs.kt` (nähe Z. 33, "Feature Toggles"-Block):

```kotlin
object SingleRowMode : Pref<Boolean>("net.devemperor.dictate.single_row_mode", false)
```

### 0f — Drawables anlegen

Verifiziert per `ls drawable/`: `ic_baseline_volume_off_24.xml` und `ic_baseline_volume_up_24.xml` **existieren NICHT** im Repo. Pflicht-Step (kein "verifizieren beim Implementieren"):

- `app/src/main/res/drawable/ic_baseline_volume_off_24.xml` — Vector aus Material-Symbols Filled.
- `app/src/main/res/drawable/ic_baseline_volume_up_24.xml` — Vector aus Material-Symbols Filled.

Naming-Konsistent zu bestehenden 56 ic_baseline-Drawables.

### 0g — `AudioFocusGate`-Interface

**Quality-Gate K8:** AudioManager ist `final` + Policy K-1 (handwritten fakes only) + `unitTests.returnDefaultValues = true`. Geforderte `setAudioFocusRuntime`-Tests sind mit aktuellen Bord-Mitteln nicht verifizierbar.

Neue Datei: `app/src/main/java/net/devemperor/dictate/core/AudioFocusGate.kt`:

```kotlin
interface AudioFocusGate {
    fun request(): Boolean   // true = GRANTED
    fun abandon()
}

class RealAudioFocusGate(
    private val audioManager: AudioManager,
    private val request: AudioFocusRequest
) : AudioFocusGate {
    override fun request() =
        audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    override fun abandon() {
        audioManager.abandonAudioFocusRequest(request)
    }
}
```

`RecordingStateController`-Konstruktor: `audioManager + audioFocusRequest` ersetzen durch `gate: AudioFocusGate`. Service-Konstruktion: `RealAudioFocusGate(audioManager, audioFocusRequest)`. Test: `FakeAudioFocusGate` zählt Calls.

---

## Block 1: Single-Row-Modus

### Trigger-Pfad
`MainButtonsController.kt` Z. 90-94: Long-Click-Listener auf `editNumbersButton` ruft (mit `callback.onVibrate()` davor) `callback.onSingleRowModeToggled()`.

### Layout-Strategie: drei ConstraintSets, nicht zwei

**Quality-Gate W2:** Bei Re-Parenting verlieren die `input_row`-Buttons ihre Default-Constraints. Beim Rückwechsel müssen sie wiederhergestellt werden — daher drei Sets:

- `csTwoRowAction` — Default-Constraints von `action_row` (Two-Row-Modus).
- `csTwoRowInput` — Default-Constraints von `input_row` (für Restore nach Single-Row→Two-Row-Wechsel).
- `csSingleRow` — Constraints für alle 8 Buttons in `action_row` (Single-Row-Modus).

Die Sets werden im `KeyboardLayoutModeController`-Konstruktor einmalig per `ConstraintSet().clone(actionRow)` / `clone(inputRow)` aus den initial inflate-ten Containern gezogen, bevor irgendein Mode-Wechsel stattfindet. `csSingleRow` wird programmatisch aufgebaut.

### Re-Parenting

Reihenfolge im Single-Row (User-bestätigt): `[🗑 trash] [🎤 record_pulse_layout] [Space] [⏸ pause] [⌫ backspace] [↵ enter] [↻ resend] [🔊 audio]`.

**Bewegt werden** beim Switch in den Single-Row-Modus: `record_pulse_layout` (NICHT `record_btn`), `space_btn`, `backspace_btn`, `enter_btn`, `resend_btn` aus `input_row` in `action_row`. `trash_btn`, `pause_btn`, `audio_focus_btn` sind bereits in `action_row`.

**Beim Rückwechsel:** umgekehrt; danach `csTwoRowInput.applyTo(inputRow)` damit die wieder eingehängten Buttons ihre Default-Constraints zurückbekommen.

### Animation — TransitionManager

**Quality-Gate W4:** Re-Parenting zwischen ViewGroups erzeugt durch TransitionManager Fade-out/Fade-in (Default-`AutoTransition`), KEINE Movement-Animation. Wer Movement will, müsste eine `ChangeBounds`-Custom-Transition + `excludeChildren()` schreiben — Aufwand zu hoch für UX-Marginale.

**Entscheidung:** Default `AutoTransition` (Fade) akzeptieren, aber **`Pref.Animations`-Check** (Quality-Gate W10): wenn User Animationen abgeschaltet hat, kein TransitionManager-Aufruf, sofortiger Apply.

```kotlin
fun setSingleRowMode(enabled: Boolean, animate: Boolean) {
    if (animate && DictatePrefsKt.get(sp, Pref.Animations.INSTANCE)) {
        TransitionManager.beginDelayedTransition(rootView)
    }
    rehome(enabled)
    if (enabled) csSingleRow.applyTo(actionRow)
    else { csTwoRowAction.applyTo(actionRow); csTwoRowInput.applyTo(inputRow) }
    inputRow.visibility = if (enabled) GONE else VISIBLE
}
```

### View-Recreate-Lebenszyklus

**Quality-Gate W1:** `onCreateInputView()` re-inflated alles. Initial-Apply pflicht:

- Im `LayoutModeController.init {}`-Block: `setSingleRowMode(prefValue, animate = false)`. So zeigt die Tastatur beim ersten Frame den persistenten Modus ohne Animations-Snap.
- Bei Pref-Änderung (per Toggle): `setSingleRowMode(newValue, animate = true)`.

### `editNumbersButton`-Rotations-Konflikt

**Quality-Gate K6:** Click → `animateSmallModeToggle()` rotiert um 180°; Long-Click würde — bei naivem `animateSingleRowToggle()` mit eigener 180°-Rotation — über zwei orthogonale Achsen rotieren. Endzustand toggle-reihenfolgen-abhängig.

**Entscheidung:** Long-Click bekommt KEINE Rotation auf `editNumbersButton`. Stattdessen kurzer **horizontaler Slide** (`translationX`-Bounce, ±8dp, 200ms) als visuelles Feedback für den Long-Press-Erfolg. So kollidiert nichts mit der vorhandenen Rotation.

### Modus-Matrix `SmallMode` × `SingleRowMode`

**Quality-Gate W5:**

| SmallMode | SingleRowMode | Verhalten |
|-----------|---------------|-----------|
| false | false | Two-Row (Default) |
| false | true | Single-Row aktiv |
| true | false | SmallMode (Edit-Bar only, `main_buttons_cl` GONE) |
| true | true | SmallMode hat Vorrang — `main_buttons_cl` GONE; SingleRowMode-Pref persistiert, wirkt erst wieder bei `SmallMode = false` |

**QWERTZ-Mode** (`contentArea = QWERTZ`, Quality-Gate B1-8): SingleRowMode ist nur in `MAIN_BUTTONS`-ContentArea wirksam. Im QWERTZ-Mode greift SingleRowMode nicht — Long-Press auf `edit_numbers_btn` setzt zwar die Pref, aber das visuelle Layout ändert sich erst beim Wechsel zurück nach `MAIN_BUTTONS`. Das vermeidet Layout-Sprünge im QWERTZ-Tippmodus.

### Service-Anpassungen
```java
@Override public void onSingleRowModeToggled() {
    boolean current = DictatePrefsKt.get(sp, Pref.SingleRowMode.INSTANCE);
    boolean next = !current;
    DictatePrefsKt.put(sp.edit(), Pref.SingleRowMode.INSTANCE, next).apply();
    layoutModeController.setSingleRowMode(next, /* animate */ true);
    mainButtonsController.animateEditNumbersBounce();  // Slide statt Rotation
}
```

---

## Block 2: Audio-Focus-Toggle

### Persistenz
`Pref.AudioFocus` existiert bereits in `DictatePrefs.kt` (Z. 30, default `true`). Existing Settings-SwitchPreference in `fragment_preferences.xml` Z. 99-103 bleibt unverändert.

### UI: Zwei physische Views, gemeinsamer Click-Listener

**Edit-Bar-Position** (Quality-Gate W7-Klärung): Die Edit-Bar ist KEIN Chain mit `chainStyle="spread"`, sondern eine Pairwise-Constraint-Kette (jeder Button hat individuelle Start/End-Constraints zum Nachbarn). 12. Button verkleinert jeden um ~9%. **Entscheidung:** akzeptiert als bewusste UX-Engung — Power-User-Audience, AudioFocus ist häufig genug um den Platz wert zu sein. Edit-Bar-Höhe bleibt 36dp.

- Neuer `MaterialButton` `@+id/edit_audio_focus_btn` zwischen `edit_history_btn` und `edit_settings_btn` (Z. 325-347).
- Constraint-Pattern wie alle Edit-Bar-Buttons: `width=0dp`, `height=match_parent`, `marginHorizontal=4dp`.
- `edit_history_btn.End` → `edit_audio_focus_btn`; `edit_settings_btn.Start` → `edit_audio_focus_btn`.

**Single-Row-Position:**
- Neuer `MaterialButton` `@+id/audio_focus_btn` in `action_row`, initial `visibility=gone`.
- **Lifecycle-Asymmetrie** (Quality-Gate Block-2): Edit-Bar-Button ist IMMER sichtbar; Single-Row-Button ist NUR sichtbar wenn `SingleRowMode=true`. Re-Parenting trifft den Single-Row-Audio-Button NICHT — er bleibt permanent in `action_row`, nur seine Visibility schaltet.

**Gemeinsamer Listener** (Quality-Gate Nice-to-have CA-5):
```kotlin
private val audioFocusClickListener = View.OnClickListener {
    callback.onVibrate()
    callback.onAudioFocusToggled()
}
// in registerEditBarListeners + registerMainButtonListeners:
editAudioFocusButton.setOnClickListener(audioFocusClickListener)
audioFocusButton.setOnClickListener(audioFocusClickListener)
```

### Click-Verhalten + Reihenfolge

**Quality-Gate W (Race Window)**: Reihenfolge SP-Write → Live-Hook → Icon-Refresh ist wichtig:

```java
@Override public void onAudioFocusToggled() {
    boolean newValue = !DictatePrefsKt.get(sp, Pref.AudioFocus.INSTANCE);
    // 1. Persist FIRST — andere Komponenten lesen Pref bei Trigger.
    DictatePrefsKt.put(sp.edit(), Pref.AudioFocus.INSTANCE, newValue).apply();
    // 2. Live-Hook auf Recording (siehe Block 3c).
    if (recordingStateController != null) {
        recordingStateController.setAudioFocusRuntime(newValue);
    }
    // 3. UI-Refresh — beide Buttons synchron.
    if (mainButtonsController != null) {
        mainButtonsController.refreshAudioFocusIcon(newValue);
    }
}
```

### Initial-Icon-State nach Re-Inflate

**Quality-Gate K-Block-2** (View-Recreate): Nach `onCreateInputView` muss `mainButtonsController.refreshAudioFocusIcon(sp.get(Pref.AudioFocus))` einmal aufgerufen werden, damit beide Buttons den persistenten Pref-Wert zeigen. Trigger-Stelle: `MainButtonsController.registerAllListeners()` am Ende, oder im Service nach `setupKeyboard()`.

### Bidirektionaler Sync (Edit-Bar ↔ Settings)

**Quality-Gate K5:** Toggle→Pref→Settings funktioniert automatisch (SwitchPreference liest Pref beim Anzeigen). Settings→Pref→Toggle-Icon **nicht** automatisch — wenn User in den Settings toggelt während die Tastatur cached ist, zeigt das Icon den alten Wert.

Lösung — `OnSharedPreferenceChangeListener` analog `inputLanguagesListener` (Service Z. 188, 588-596):

```java
private final SharedPreferences.OnSharedPreferenceChangeListener audioFocusListener =
    (sp, key) -> {
        if (Pref.AudioFocus.INSTANCE.getKey().equals(key)) {
            boolean newValue = DictatePrefsKt.get(sp, Pref.AudioFocus.INSTANCE);
            if (mainButtonsController != null) {
                mainButtonsController.refreshAudioFocusIcon(newValue);
            }
            if (recordingStateController != null) {
                recordingStateController.setAudioFocusRuntime(newValue);
            }
        }
    };
```

Lifecycle: registrieren in `onCreateInputView` neben `inputLanguagesListener`, deregistrieren in `cleanupOldControllers` und `onDestroy` (gleiche Stellen wie heute).

### `AUDIOFOCUS_LOSS`-Wechselwirkung

**Quality-Gate K4:** `OnAudioFocusChangeListener` Z. 331-338 ruft bei `AUDIOFOCUS_LOSS` `togglePause()`. Bei `setAudioFocusRuntime(true)` mid-Active-Recording mit fremdem Fokus-Owner:

- `requestAudioFocus()` returns `_REQUEST_FAILED` → kein Crash; Field bleibt `true` aber kein AudioManager-Modify; nächster `togglePause`/`startRecording` versucht erneut.
- `requestAudioFocus()` returns `_REQUEST_GRANTED`, dann sofort `_LOSS` → bestehender Listener pausiert. **Entscheidung:** akzeptiert — Verhalten konsistent mit "User wollte Auto-Pause, andere App will Audio, also pausiert das Recording wie konfiguriert". Manuelle Verifikation deckt das ab.

### Idempotenz

**Quality-Gate K10:** Doppelter `setAudioFocusRuntime(true)` → `wasEnabled == enabled` → `when`-Branch matcht nicht → kein redundanter `requestAudioFocus`-Call. Field-Update ist Identitäts-Operation. **Verhalten:** idempotent, korrekt.

### Icon-Logik
- `ic_baseline_volume_off_24` (durchgestrichen) → wenn `Pref.AudioFocus = true` (Auto-Pause aktiv).
- `ic_baseline_volume_up_24` → wenn `Pref.AudioFocus = false` (Spotify spielt durch).

### Strings + ContentDescription

**Quality-Gate Nice-to-have B2-8:** Strings als State-Description (TalkBack-konform), nicht als Action:

```xml
<string name="dictate_audio_focus_state_on">Audio-Fokus aktiv: Musik wird beim Aufnehmen pausiert</string>
<string name="dictate_audio_focus_state_off">Audio-Fokus inaktiv: Musik läuft beim Aufnehmen weiter</string>
```

`refreshAudioFocusIcon(enabled)` setzt sowohl `foreground`-Drawable als auch `contentDescription` auf BEIDEN Buttons.

---

## Block 3: State-Machine-Konsolidierung

### 3a — `isPreparing`-Field auflösen

**Stellen — verifiziert per `grep -n "isPreparing"` (FÜNF Zuweisungen, NICHT vier):**

- Z. 120-130: Field-Definition mit ausführlicher KDoc.
- Z. 696, Z. 1639, Z. 1781, **Z. 2515**: `isPreparing = false;` (insbesondere Z. 2515 im Cancel-Pfad — Quality-Gate K7).
- Z. 1570: `isPreparing = true;`.
- Z. 898-902: `} else if (isPreparing) { … }` mit Kommentar "practically unreachable".
- **Z. 1779-1780**: erläuternder Kommentar zu Z. 1781 (Quality-Gate W8) — muss mit weg.

**Refactor:**
- Field samt KDoc entfernen.
- Z. 696, 1570, 1639, 1779-1781, 2515: Zeilen ersatzlos streichen (jeweils inkl. erläuternder Kommentare wo vorhanden).
- Z. 898-902: Branch ersatzlos entfernen. Der nachfolgende `else if (pipelineOrchestrator.isRunning())` (Z. 903) ist der echte Restore-Pfad.

**Sanity-Check vor und nach Refactor:** `grep -n "isPreparing" app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` muss vorher 5+ Treffer, nachher 0 Treffer liefern.

**Verloren:** "defensive preparation" für künftige async-upload-Phase. Wenn diese Phase kommt, kann sie über `KeyboardUiController.state instanceof PipelineUiState.Preparing` rekonstruiert werden — sealed-class-modelled, sauberer als das volatile-Field.

**ADR-Hinweis** (Quality-Gate SA-3): Die KDoc auf `isPreparing` referenziert "Refactor-Plan §R-6". Die Reversal sollte als kurzer ADR-Eintrag dokumentiert werden (oder zumindest im Archiv-README dieses Plans), damit zukünftige Leser den Reversal-Grund finden.

### 3b — Service-Field `audioFocusEnabled` ebenfalls streichen

**Quality-Gate K3:** `DictateInputMethodService.java` Z. 114 hält ein eigenes `audioFocusEnabled`-Feld (gelesen Z. 1260, 1521, übergeben Z. 1522). Mit Live-Hook entstehen zwei Quellen.

**Refactor:**
- Z. 114: Field ersatzlos entfernen.
- Z. 1260: `boolean audioFocusEnabled = DictatePrefsKt.get(sp, Pref.AudioFocus.INSTANCE);` als lokale Variable.
- Z. 1521-1522: ebenfalls direkt aus Pref lesen.

So bleibt nur **eine** persistente Wahrheit (Pref) und **eine** Per-Session-Wahrheit (Controller-Field).

### 3c — `setAudioFocusRuntime()` im RecordingStateController

**Pre-existing:** `audioFocusEnabled: Boolean = true` ist bereits `private var` (Z. 68).

**Methode mit KDoc** (Quality-Gate Nice-to-have B3-6):

```kotlin
/**
 * Mid-recording AudioFocus override. Updates the [audioFocusEnabled] field and,
 * if currently [RecordingState.Active], immediately requests/abandons audio focus.
 *
 * State semantics:
 *  - Idle / Preparing / Paused: only the field is updated. The next state transition
 *    uses the new value via late-binding ([proceedStartRecording] re-reads the field
 *    when transitioning Preparing → Active; [togglePause] re-reads when leaving Paused).
 *  - Active: the field is updated AND AudioManager is mutated synchronously.
 *
 * The next [startRecording] resets the field from the [Pref.AudioFocus] value —
 * this method only affects the running session. Persistent effect comes from the
 * SP-write in [DictateInputMethodService.onAudioFocusToggled].
 *
 * Consumed by Block 2's `onAudioFocusToggled()`.
 */
fun setAudioFocusRuntime(enabled: Boolean) {
    val wasEnabled = audioFocusEnabled
    // Always update the field — Idle/Preparing/Paused defer the AudioManager-Call.
    audioFocusEnabled = enabled
    val current = state
    if (current is RecordingState.Active) {
        when {
            enabled && !wasEnabled -> gate.request()
            !enabled && wasEnabled -> gate.abandon()
        }
    }
}
```

**Verhalten — alle 4 States explizit:**
- **Idle**: nur Field-Update; AudioManager wird nicht angefasst.
- **Preparing** (Bluetooth-SCO-Wartephase): nur Field-Update — `proceedStartRecording()` (Z. 252) liest beim eigentlichen Start den dann aktuellen Wert (Late-Binding).
- **Active**: sofortige `gate.request()` / `gate.abandon()` je nach Übergang.
- **Paused**: nur Field-Update — beim nächsten `togglePause` (Paused→Active) wird der neue Wert in der Z. 130-Logik korrekt verwendet.

Beim nächsten `startRecording()`: das Field wird ohnehin aus dem Pref überschrieben — der Mid-Recording-Override gilt nur für die laufende Session.

---

## Critical Files

| Datei | Änderung |
|-------|----------|
| `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt` | + `Pref.SingleRowMode` (nach Z. 33) |
| `app/src/main/java/net/devemperor/dictate/core/AudioFocusGate.kt` | **NEU** — Interface + RealAudioFocusGate (Block 0g) |
| `app/src/main/java/net/devemperor/dictate/core/KeyboardLayoutModeController.kt` | **NEU** — `csTwoRowAction`/`csTwoRowInput`/`csSingleRow`, Re-Parenting, Animation, View-Recreate-Initial-Apply (Block 0d, Block 1) |
| `app/src/main/java/net/devemperor/dictate/core/MainButtonsController.kt` | DTO + Callback-Interface erweitern (Block 0b/0c); Long-Click-Listener auf `editNumbersButton` umbiegen; gemeinsamer `audioFocusClickListener` für Edit-Bar + Single-Row; `animateEditNumbersBounce()` (Slide statt Rotation, Block 1); `refreshAudioFocusIcon(enabled)` setzt foreground + contentDescription auf beiden Buttons; `applyTheme` + `initializeKeyPressAnimations` für beide Audio-Buttons |
| `app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt` | `KeyboardViews`-DTO erweitern (Block 0a) — `actionRow`, `inputRow`, `recordPulseLayout`, 5 zusätzliche Buttons; `applyVisibility()` ruft am Ende `layoutModeController.refresh()` |
| `app/src/main/java/net/devemperor/dictate/core/RecordingStateController.kt` | Konstruktor: `audioManager+audioFocusRequest` → `gate: AudioFocusGate`; + `setAudioFocusRuntime(enabled: Boolean)` mit KDoc (Block 3c); interne Aufrufer (Z. 252, 130 et al.) auf `gate.request()` / `gate.abandon()` umstellen |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | Implementierung `onSingleRowModeToggled()`, `onAudioFocusToggled()`; `onLanguageCycled()` (Z. 2549-2568) komplett entfernen; `isPreparing`-Cleanup (Z. 120-130, 696, 898-902, 1570, 1639, 1779-1781, **2515**) inkl. Kommentare; `audioFocusEnabled`-Service-Field (Z. 114) streichen, an Z. 1260, 1521-1522 lokal aus Pref lesen; im `onCreateInputView` `KeyboardLayoutModeController` instanziieren + initialer Apply; `audioFocusListener` als `OnSharedPreferenceChangeListener` registrieren (analog Z. 588); `RealAudioFocusGate` konstruieren und an `RecordingStateController` übergeben |
| `app/src/main/res/layout/activity_dictate_keyboard_view.xml` | + `edit_audio_focus_btn` zwischen `edit_history_btn` und `edit_settings_btn`; + `audio_focus_btn` (initial gone) in `action_row`; Constraints von `edit_history_btn.End` und `edit_settings_btn.Start` umhängen |
| `app/src/main/res/drawable/ic_baseline_volume_off_24.xml` | **NEU** (Block 0f, Pflicht-Step) |
| `app/src/main/res/drawable/ic_baseline_volume_up_24.xml` | **NEU** (Block 0f, Pflicht-Step) |
| `app/src/main/res/values/strings.xml` | + `dictate_audio_focus_state_on`, `dictate_audio_focus_state_off` (State-Description, TalkBack-konform) |
| `app/src/test/java/net/devemperor/dictate/core/RecordingStateControllerTest.kt` | Neue Tests gegen `FakeAudioFocusGate` (siehe Verification) |
| `app/src/test/java/net/devemperor/dictate/core/FakeAudioFocusGate.kt` | **NEU** — Test-Fake (Counter für request/abandon) |
| `app/src/test/java/net/devemperor/dictate/preferences/DictatePrefsTest.kt` (oder bestehende Pref-Test-Datei) | + SP-Round-Trip-Test für `Pref.SingleRowMode` (Quality-Gate W11) |

**Hinweis ADR**: Beim Verschieben dieses Plans ins Archiv wird ein ADR-Eintrag (oder zumindest README-Erläuterung) für die `isPreparing`-Reversal gegen den ursprünglichen Refactor-Plan §R-6 erstellt.

---

## Verification

### Unit-Tests (Pre-Device)

**Neue Datei `FakeAudioFocusGate`** — Counter-basierter Test-Fake mit `requestCount`/`abandonCount`.

**Neue Tests in `RecordingStateControllerTest`** — gegen `FakeAudioFocusGate`:

1. `setAudioFocusRuntime in Idle state only updates field, no gate calls`
2. `setAudioFocusRuntime to true in Active state requests focus when previously false (1 request, 0 abandon)`
3. `setAudioFocusRuntime to false in Active state abandons focus when previously true (0 request, 1 abandon)`
4. `setAudioFocusRuntime no-op when value is unchanged in Active (0 request, 0 abandon)`
5. `setAudioFocusRuntime in Paused state only updates field, deferred to next togglePause`
6. `setAudioFocusRuntime in Preparing state only updates field; transition to Active reads new value via late-binding`
7. **Sequenz-Test (Quality-Gate V-2):** `Active + setAudioFocusRuntime(false) + togglePause(→Paused) + togglePause(→Active)` — letzter Active-Übergang erwartet 1 abandon (im setAudioFocusRuntime) PLUS keine zusätzliche request beim Resume, weil Field bereits false.
8. **Multi-Recording-Sequenz (Quality-Gate V-2):** `startRecording(prefTrue) + setAudioFocusRuntime(false) + stopRecording + startRecording(prefTrue)` — zweiter Start MUSS wieder `request()` rufen (Field wurde via Pref-Read überschrieben).
9. **Idempotenz (Quality-Gate K10):** `Active + setAudioFocusRuntime(true) + setAudioFocusRuntime(true)` → exakt 1 request-Call.

**Neue Tests in `DictatePrefsTest`** (Quality-Gate W11):
- `Pref.SingleRowMode round-trips through FakeSharedPreferences`
- `Pref.SingleRowMode default is false`

**Bestehende Test-Suite muss grün bleiben:** 170+ Tests auf Branch `feature/language-chip-curation`. Insbesondere `LanguageControllerTest`, `LanguageLabelResolverTest`, `versioned/*` (Phase-0-Verifikation).

### Phase-0-Regressions-Smoke-Test (Quality-Gate V-5)

Nicht nur "Sprach-Pill funktioniert" sondern explizit:

- [ ] App-Erststart auf einem frischen User-Profil ohne Pref-Datei: `InputLanguagesPlugin`-Migration läuft genau einmal (Logcat: `migrating legacy InputLanguagePos` exakt 1×).
- [ ] App-Zweitstart: keine erneute Migration (Logcat clean).
- [ ] Sprach-Pill-Cycle, Picker-Open, Curated/Others-Sortierung — alles wie vorher.
- [ ] `LanguageLabelResolver`-Init im `Application.onCreate` läuft vor erstem `onCreateInputView` (kein `IllegalStateException`-Crash bei Schnellstart).

### Build & Install

```bash
cd /home/lukas/WebStorm/Dictate
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:assembleDebug --console=plain
adb -s <DEVICE> install -r app/build/outputs/apk/debug/app-debug.apk
```

### Manuelle Geräte-Verifikation

**Single-Row-Modus:**
- [ ] Tastatur öffnen; Long-Press auf `edit_numbers_btn` → Layout wechselt in eine Reihe `[🗑] [🎤] [Space] [⏸] [⌫] [↵] [↻] [🔊]`.
- [ ] `editNumbersButton`-Bounce sichtbar (kein Rotations-Konflikt mit SmallMode-Click).
- [ ] Erneuter Long-Press → zurück zu Two-Row, ALLE Buttons in `input_row` korrekt platziert (csTwoRowInput-Restore).
- [ ] App-Restart → Single-Row-Status persistent, KEIN Initial-Animations-Snap (animate=false).
- [ ] Aufnahme starten **vor** Toggle → Long-Press während Recording → Layout-Switch ohne Audio-Unterbrechung; Pulse-Animation auf `record_btn` läuft weiter (record_pulse_layout-Wrapper bewegt sich, nicht der nackte Button).
- [ ] `Pref.Animations = false` setzen → Toggle ohne TransitionManager-Effekt.
- [ ] Modus-Matrix:
  - [ ] SmallMode aktiv + Long-Press → SingleRowMode-Pref schaltet, kein visueller Effekt (SmallMode Vorrang); SmallMode aus → SingleRow ist sichtbar aktiv.
  - [ ] QWERTZ-ContentArea + Long-Press → Pref schaltet, Layout bleibt QWERTZ; zurück zu MAIN_BUTTONS → SingleRow aktiv.

**Audio-Focus-Toggle:**
- [ ] Spotify im Hintergrund laufen lassen.
- [ ] Edit-Bar-Toggle drücken (Lautsprecher-Slash → Lautsprecher) → Icon ändert sich auf BEIDEN Buttons (Edit-Bar + Single-Row falls aktiv); SP-Wert via `adb shell run-as net.devemperor.dictate cat shared_prefs/...xml` geprüft.
- [ ] Recording starten: Spotify wird **nicht** pausiert (AudioFocus aus).
- [ ] Während Recording erneut drücken → Spotify wird **sofort** pausiert (Live-Hook).
- [ ] Mid-Recording-Edge-Case: Spotify spielt + Recording läuft + setAudioFocusRuntime(true) → Verhalten beobachten (entweder keine Pause, oder togglePause via AUDIOFOCUS_LOSS — beides akzeptiert, dokumentieren was passiert).
- [ ] Erneut drücken → Spotify spielt wieder.
- [ ] Single-Row-Toggle aktivieren, Audio-Toggle in Single-Row drücken → identisches Verhalten zum Edit-Bar-Toggle.
- [ ] Edit-Bar-Toggle und Single-Row-Toggle zeigen synchronisierte Icons.
- [ ] Settings-Screen öffnen → SwitchPreference "Audio Focus" zeigt korrekten Wert.
- [ ] **Bidirektional (Quality-Gate K5):** Settings-SwitchPreference toggeln → zurück zur Tastatur → Edit-Bar-Icon spiegelt neuen Wert OHNE Re-Inflate (audioFocusListener funktioniert).
- [ ] **Initial-State nach Re-Inflate**: Tastatur schließen + öffnen → Icon zeigt persistenten Pref-Wert sofort.
- [ ] TalkBack: ContentDescription liest State, nicht Aktion ("Audio-Fokus aktiv: ...").

**State-Machine-Konsolidierung:**
- [ ] Recording starten und schnell stoppen → keine UI-Hänger im "Preparing"-Übergang.
- [ ] App während aktiver Pipeline rotieren → State wird via `pipelineOrchestrator.isRunning()` korrekt rekonstruiert; kein Crash.
- [ ] App während aktiver Pipeline force-killen + neu öffnen → kein Crash; State `Idle`.
- [ ] **Cancel-Pfad (Z. 2515):** während laufender Pipeline `onCancelClicked` triggern → kein Crash, State sauber Idle. (Wichtig wegen entferntem `isPreparing = false` Z. 2515.)
- [ ] Kein Compile-Warning (unused import, dead code).
- [ ] `grep -n "isPreparing" app/src/main/java/...DictateInputMethodService.java` → 0 Treffer.
- [ ] `grep -n "audioFocusEnabled" app/src/main/java/...DictateInputMethodService.java` → 0 Treffer.

---

## Implementation-Reihenfolge

1. **Block 0 (Strukturelle Vorbereitung)** — DTOs, Callback-Interface, KeyboardLayoutModeController-Klasse-Skeleton, Pref, Drawables, AudioFocusGate-Interface. **Nicht funktional**, aber alle nachfolgenden Blocks bauen darauf.
2. **Block 3a (`isPreparing`-Cleanup)** — kleinstes Verhaltens-Risiko, isolierter Refactor.
3. **Block 3b (`audioFocusEnabled`-Service-Field-Cleanup)** — analog 3a.
4. **Block 3c (`setAudioFocusRuntime` + AudioFocusGate-Wiring)** — neue Methode + Tests, vorbereitend für Block 2.
5. **Block 2 (Audio-Toggle-UI)** — Layout-XML + zwei Buttons + Listener + Bidirektional-Sync + Live-Hook-Konsum.
6. **Block 1 (Single-Row-Modus)** zuletzt — größte Layout-Änderung, baut auf den Audio-Toggle-Button-Existenz auf.

**Sprachliche Klarstellung** (Quality-Gate V-3): "Single-Row reused den Audio-Button" heißt: derselbe `audio_focus_btn` der bereits in `action_row` eingehängt ist, schaltet im Single-Row-Modus von `gone` auf `visible` — kein Re-Parenting, kein zweites Anlegen. Das passt zur Two-Views-Architektur (Edit-Bar + Single-Row sind zwei separate Views).

---

## Plan-Korrekturen (Post-Implementation)

**2026-05-06 — Plan-Z. 185 (Re-Parenting-Liste):**

- Plan behauptete: "`trash_btn`, `pause_btn`, `audio_focus_btn` sind bereits in `action_row`".
- XML-Realität (`activity_dictate_keyboard_view.xml`): nur `audio_focus_btn` ist in `action_row` (Z. 93). `trash_btn` (Z. 117) und `pause_btn` (Z. 143) sind beide in `input_row`.
- Folge im ersten Implementierungs-Pass: `csSingleRow` referenzierte beide IDs in der Chain, aber `rehome()` bewegte sie nicht — die Constraint-Connects gegen IDs ohne Children im `action_row`-Scope wurden stillschweigend ignoriert, und die Buttons blieben unsichtbar im GONE-`input_row`. Nur 6 von 8 Single-Row-Buttons waren sichtbar.
- Korrekte Re-Parenting-Liste für Single-Row: `record_pulse_layout`, `space_btn`, `backspace_btn`, `enter_btn`, `resend_btn`, **`trash_btn`**, **`pause_btn`** (alle aus `input_row` in `action_row`).
- Beim Rückwechsel: alle 7 zurück nach `input_row`.
- Nur `audio_focus_btn` bleibt permanent in `action_row` und schaltet ausschließlich seine Visibility (wie ursprünglich geplant).
- Korrektur erfolgte im Fix-Pass nach Chunk-3-Validation; Implementiert in `KeyboardLayoutModeController.rehome()`.
