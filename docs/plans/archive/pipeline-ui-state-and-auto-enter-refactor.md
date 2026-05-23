<!-- EXECUTION-PLAN -->
## Execution Plan

**Erstellt:** 2026-03-29 00:15
**Geschätzte Chunks:** 3

### Meine Strategie

Der Plan wird in 3 logische Chunks aufgeteilt, die aufeinander aufbauen. Chunk 1 schafft die Basis (neue Sealed Class + Utility-Funktionen), Chunk 2 refactored den zentralen Controller, und Chunk 3 verdrahtet alles im Service und fügt die QWERTZ-Anzeige hinzu. Sequentiell, weil jeder Chunk vom vorherigen abhängt.

### Geplante Chunks

| # | Chunk | Plan-Abschnitte | Warum diese Gruppierung? |
|---|-------|-----------------|--------------------------|
| 1 | Foundation: Types & Utilities | Phase 0, Phase 1 | PipelineUiState + formatElapsedCompact + getCompletedSteps — Basis für alles |
| 2 | KeyboardUiController Refactor | Phase 2 | Kern-Refactoring: State-Pattern, neue API, alte Methoden entfernen |
| 3 | QWERTZ + Service Wiring | Phase 3, Phase 4 | RecordingUiController + DictateInputMethodService + MainButtonsController — Integration |

### Abhängigkeiten & Risiken

- **Chunk 1 → 2:** PipelineUiState + formatElapsedCompact müssen existieren
- **Chunk 2 → 3:** KeyboardUiController neue API (startPipeline, stopPipeline, setAutoEnter) muss fertig sein
- **Risiko:** Chunk 3 (Service-Java) hat viele Callsites — alle resetToPromptButtons()-Aufrufe müssen gefunden werden

---
<!-- /EXECUTION-PLAN -->

# Feature: Pipeline-Fortschritt in Record-Button & QWERTZ-Button

## Context

**Problem 1 — Pipeline-Fortschritt:**
Wenn die Pipeline läuft (Transkription → Formatierung → Prompts), gibt es im großen Record-Button zwar einen Step-Namen und bedingt einen Counter, aber:
1. **Kein Timer** im Button — nur die Prompts-Leiste oben zeigt per-Step-Timer
2. **Counter zeigt laufenden Step** (1/3 = Step 1 läuft), nicht abgeschlossene (0/3)
3. **Counter nur bei >2 Steps** sichtbar
4. **QWERTZ-Button wird gar nicht aktualisiert** — bleibt mit eingefrorenem Recording-Timer stehen (bestehender Bug)

**Problem 2 — Auto-Enter-Toggle:**
Der Auto-Enter-Toggle während der Pipeline hat architektonische Schwächen:
5. **Click-Listener-Austausch:** `showAutoEnterToggle()` ersetzt den Record-Button-Click-Listener, `hideAutoEnterToggle()` stellt ihn via Callback wieder her — fragil bei View-Recreation
6. **Kein zentraler State:** `autoEnterOverride` ist ein nacktes `Boolean?`-Feld im Service, UI-Updates sind manuell-imperativ
7. **QWERTZ-Button hat keinen Auto-Enter-Toggle** — der User kann nur über den großen Button toggeln
8. **Kein State-Pattern:** Im Gegensatz zu `RecordingState` (sealed class + Controller + Callback) gibt es für den Pipeline-UI-State keine formale Struktur

**Ziel:**
- **Pipeline-Fortschritt:** Beide Buttons zeigen Step-Counter + Gesamt-Timer
- **Auto-Enter-Toggle:** Auf beiden Buttons bedienbar, kein Click-Listener-Austausch
- **State-Pattern:** Neuer `PipelineUiState` nach `RecordingState`-Vorbild (sealed class + Controller + Callback)
- **Prompts-Leiste oben:** Bleibt unverändert parallel bestehen

**Step-Zählung:**
- Anzeige: `completedSteps/totalSteps` — "0/1" → "1/1" bei einfacher Transkription
- Transkription = 1 Step, Auto-Formatierung = +1, jede Prompt = +1

---

## Design-Prinzipien

### 1. State-Pattern nach RecordingState-Vorbild

Das Projekt hat mit `RecordingState` + `RecordingStateController` ein bewährtes Pattern:
- **Sealed Class** definiert endliche, typsichere Zustände
- **Ein Owner-Controller** hält den State, bietet Mutations-Methoden
- **Callback** benachrichtigt Konsumenten bei Änderungen
- **Click-Handler** prüfen den State, werden nie ausgetauscht

Dieses Pattern wird auf den Pipeline-UI-State übertragen. Die Konvention wird per KDoc auf `PipelineUiState` dokumentiert (kein Interface — `StateOwner<S>` wäre ein reiner Marker ohne polymorphe Nutzung, YAGNI).

### 2. Wiederverwendbare Bausteine

Alle neuen Logik-Bausteine werden **einmal definiert und mehrfach referenziert**.

| Baustein | Definiert in | Genutzt von |
|----------|-------------|-------------|
| `PipelineUiState` | `PipelineUiState.kt` (Sealed Class) | `KeyboardUiController`, `RecordingUiController`, `DictateInputMethodService` |
| `formatElapsedCompact(ms)` | `ElapsedTimer.kt` (Top-Level) | `KeyboardUiController`, `RecordingUiController` |
| `getCompletedSteps()` | `PipelineOrchestrator.kt` | `DictateInputMethodService` (restoreUiState, onLayoutRebuilt) |
| `ensureOriginalsSaved(btn)` | `RecordingUiController.kt` (private) | `updateQwertzRecButton()`, `updateQwertzRecButtonForPipeline()` |

### 3. Threading (Main-Thread-Contract)

`KeyboardUiController` ist laut KDoc **Main-Thread-only** — alle Methoden (`startPipeline`, `stopPipeline`, `preparePipeline`, `setAutoEnter`, `addRunningStep`, `completeStep`, `failStep`, `updatePipelineState`) dürfen ausschließlich vom Main-Thread gerufen werden. `PipelineOrchestrator`-Callbacks feuern aus Worker-Threads; im Service werden Controller-Aufrufe deshalb konsequent in `mainHandler.post(() -> ...)` gewrapt.

Der Getter `PipelineOrchestrator.getCompletedSteps()` wird dagegen aus `restoreUiState()` (Main-Thread) UND potenziell aus dem Layout-Rebuild-Pfad gerufen, während der Orchestrator im Worker den `currentStepIndex` inkrementiert. Das Feld sollte `@Volatile` sein (oder hinter einem Lock), damit der Main-Thread einen konsistenten Wert sieht — die KDoc-Doku auf dem Getter weist explizit auf den Cross-Thread-Zugriff hin:

```kotlin
/**
 * Number of steps that have finished (= started steps minus the currently running one).
 *
 * Thread-Hinweis: Wird vom Main-Thread aus `restoreUiState()` gerufen, während die
 * inkrementierende Schreibseite (`currentStepIndex`) im Worker-Thread läuft.
 * `currentStepIndex` muss `@Volatile` sein, damit der Getter einen konsistenten
 * Snapshot sieht.
 */
fun getCompletedSteps(): Int = maxOf(0, currentStepIndex - 1)
```

### 4. Was entfällt (Listener-Austausch eliminiert)

| Entfernt | Ersetzt durch |
|----------|--------------|
| `showAutoEnterToggle(active, onToggle, onRestore)` | `PipelineUiState.Running.autoEnterActive` + State-Callback |
| `hideAutoEnterToggle()` | Automatisch bei `PipelineUiState.Idle` |
| `updateAutoEnterToggle(active)` | `setAutoEnter(active)` → `updatePipelineState()` |
| `autoEnterToggleCallback` / `autoEnterRestoreCallback` Felder | Weg — kein Listener-Austausch mehr |
| `reRegisterRecordButtonListener()` | Weg — Record-Button behält permanent seinen Listener |
| QWERTZ-Pipeline-Timer-Lambda (inline in Service) | `onPipelineTimerTick` Callback auf `KeyboardUiController` |

---

## Implementierungsplan

### Phase 0: Architektur-Grundlage

#### 0a) `PipelineUiState` Sealed Class

**Neue Datei:** `app/src/main/java/net/devemperor/dictate/core/PipelineUiState.kt`

```kotlin
/** UI state for the pipeline progress display on record button and QWERTZ button. */
sealed class PipelineUiState {
    /** No pipeline active — normal prompt buttons mode. */
    object Idle : PipelineUiState()

    /** Audio being uploaded / pipeline not yet started. Button disabled, shows "Sending...". */
    object Preparing : PipelineUiState()

    /** Pipeline running — progress display active. */
    data class Running(
        val totalSteps: Int,
        val completedSteps: Int,
        val currentStepName: String,
        val autoEnterActive: Boolean
    ) : PipelineUiState()
}
```

**Transition-Diagramm:**
```
Idle ──preparePipeline()──▶ Preparing ──startPipeline()──▶ Running ──stopPipeline()──▶ Idle
  ▲                                                                                      │
  └──────────────────────── stopPipeline() (Fehler-/Cancel-Pfad) ───────────────────────┘
```

Der `Preparing`-Zustand deckt das Fenster zwischen Recording-Stop und tatsächlichem Pipeline-Start ab, in dem Audio hochgeladen wird. Der Record-Button ist während Preparing disabled und zeigt "Sending..." mit Send-Icon — das verhindert, dass der User in diesem kurzen Fenster eine neue Aufnahme startet oder Auto-Enter toggelt, bevor `Running` etabliert ist.

**KDoc-Konvention:** Der KDoc-Kommentar auf `PipelineUiState` dokumentiert das State-Pattern für zukünftige Controller:
```
Pattern: Sealed class → ein Owner-Controller → Callback-Benachrichtigung → Click-Handler prüfen State statt Listener-Austausch.
Vorbild: RecordingState + RecordingStateController.
```

### Phase 1: Shared Utilities

#### 1a) Timer-Formatierung zentralisieren

**Datei:** `ElapsedTimer.kt`

Neue Top-Level-Funktion (neben der Klasse, nicht darin):
```kotlin
/** Formats elapsed milliseconds as compact duration string, e.g. "4.2s". */
fun formatElapsedCompact(ms: Long): String =
    String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
```

**Warum Top-Level statt Companion:** Die Funktion hat keine Abhängigkeit auf `ElapsedTimer`-Instanzen. Als Top-Level-Funktion ist sie aus Kotlin direkt und aus Java via `ElapsedTimerKt.formatElapsedCompact(ms)` aufrufbar.

Die bestehende `formatDuration()` in `KeyboardUiController.kt` (Zeile 311) wird **komplett entfernt**. Alle Aufrufe (4 Stellen: `addRunningStep`, `completeStep`, `refreshRecordButtonFromState`, und der Pipeline-Step-Timer) verwenden direkt `formatElapsedCompact()`. Kein Wrapper — zwei Namen für dieselbe Funktion sind unnötig.

#### 1b) `getCompletedSteps()` im PipelineOrchestrator

**Datei:** `PipelineOrchestrator.kt`

Neuer Getter neben den bestehenden `getTotalSteps()`, `getCurrentStep()`, `getCurrentStepName()` (Zeile 274-281):
```kotlin
/**
 * Number of steps that have finished (= started steps minus the currently running one).
 *
 * Threading: Wird vom Main-Thread (`restoreUiState`, `onLayoutRebuilt`) gerufen, während
 * `currentStepIndex` im Worker-Thread inkrementiert wird. Das Feld muss daher `@Volatile`
 * sein, damit der Getter einen konsistenten Snapshot sieht. Siehe Design-Prinzipien → Threading.
 */
fun getCompletedSteps(): Int = maxOf(0, currentStepIndex - 1)
```

**Warum:** `currentStepIndex` wird beim **Start** eines Steps inkrementiert. "Completed" = `index - 1`. Statt diese Arithmetik an jeder Aufrufstelle zu wiederholen, kapselt der Getter die Semantik.

### Phase 2: KeyboardUiController als StateOwner

**Datei:** `KeyboardUiController.kt`

#### 2a-0) Dual-Source-of-Truth `currentMode` vs. `state` — bewusst behalten

Ein kritischer Leser wird bemerken, dass `KeyboardUiController` weiterhin ein `currentMode: PromptAreaMode`-Feld hält (neben dem neuen `state: PipelineUiState`). Strikt nach State-Pattern wäre dies redundant: `state is Idle` ↔ `PROMPT_BUTTONS`, sonst `PIPELINE_PROGRESS`. Diskutierte Varianten:

- **Variante (a) — currentMode entfernen:** `KeyboardStateManager` liest `state` direkt für Visibility. Konsequent, aber `KeyboardStateManager` kennt `PipelineUiState` heute nicht und müsste einen neuen Import erhalten.
- **Variante (b) — currentMode behalten (Ground-Truth):** `currentMode` bleibt als Sichtbarkeits-Indikator für `KeyboardStateManager.refresh()`. `stopPipeline()` setzt `currentMode` VOR `state`, damit `stateManager.refresh()` (via `updatePipelineState()`) die korrekte Visibility berechnet. Ein Mini-Reihenfolge-Kontrakt.

**Entscheidung:** Ground-Truth nutzt Variante (b). **Bewusst behalten, weil** `KeyboardStateManager` ein eigenständiges Modul mit breiter Verantwortung ist, das nicht direkt vom neuen Pipeline-State abhängen sollte — der `PromptAreaMode`-Enum wirkt dort als Abstraktionsgrenze. Der Design-Bruch (zwei Wahrheiten für "Modus") wird hier explizit dokumentiert: Der Reihenfolge-Kontrakt in `stopPipeline()` ist der einzige Ort, an dem beide Felder gekoppelt werden — wenn der Kontrakt bricht, kippt die Visibility-Berechnung.

Eine saubere Migration (Variante a) ist als Follow-up sinnvoll, gehört aber nicht in diesen Plan.

#### 2a) Pipeline UI State + neue Felder

```kotlin
class KeyboardUiController(
    private val views: PipelineViews,
    private val stateManager: KeyboardStateManager
) {

    // ── Pipeline UI State ──
    var state: PipelineUiState = PipelineUiState.Idle
        private set

    /** Fires on actual state changes (step completed, auto-enter toggled, pipeline start/stop). */
    var onPipelineUiStateChanged: ((old: PipelineUiState, new: PipelineUiState) -> Unit)? = null

    /** Fires every 100ms while pipeline runs — for QWERTZ timer updates. */
    var onPipelineTimerTick: ((state: PipelineUiState.Running, elapsedMs: Long) -> Unit)? = null

    // ── Callback-Design-Diskussion ──
    //
    // Das Projekt nutzt an anderer Stelle ein Interface-basiertes Callback-Pattern
    // (Vorbild: RecordingStateController.Callback mit Default-Methoden für
    // onStateChanged / onAmplitudeUpdate / onTimerTick / onRecordingError / ...).
    //
    // Die saubere Variante wäre hier analog:
    //
    //     interface PipelineUiCallback {
    //         fun onStateChanged(old: PipelineUiState, new: PipelineUiState) {}
    //         fun onTimerTick(state: PipelineUiState.Running, elapsedMs: Long) {}
    //         // zukünftig z.B. fun onPipelineError(errorKey: String) {}
    //     }
    //     var callback: PipelineUiCallback? = null
    //
    // Vorteile: Java-Konvenienz (eine anonyme Klasse statt zwei Lambdas), konsistent
    // zum Projekt-Stil, leichter um neue Events zu erweitern, einfacher zu testen.
    //
    // Ground-Truth-Implementierung: Zwei lose var-Lambda-Felder (siehe oben).
    // **Bewusste Abweichung vom Projekt-Konvention**, weil es aktuell nur zwei Events
    // gibt und der Java-Service diese ohnehin mit Method-References anbindet. Sobald
    // ein drittes Event hinzukommt (z.B. onPipelineError für SEC-2-4), sollte auf das
    // Interface-Pattern migriert werden. Bis dahin ist das Abweichen von
    // RecordingStateController.Callback dokumentiert und bewusst.

    private var pipelineTotalTimer: ElapsedTimer? = null
    private var latestPipelineElapsedMs: Long = 0

    /** Read-only Zugriff auf den letzten Timer-Wert — für Layout-Rebuild ohne Flackern. */
    fun getLatestPipelineElapsedMs(): Long = latestPipelineElapsedMs

    // ── Convenience-Properties (Tell-don't-ask) ──

    /** True genau dann, wenn der Pipeline-UI-State [PipelineUiState.Running] ist. */
    fun isPipelineRunning(): Boolean = state is PipelineUiState.Running

    /** True bei [PipelineUiState.Running] ODER [PipelineUiState.Preparing]. */
    fun isPipelineActive(): Boolean =
        state is PipelineUiState.Running || state is PipelineUiState.Preparing

    // ... bestehende Felder (currentMode, totalSteps, currentStep, stepRows etc.) bleiben ...
```

#### 2b) State-Mutations-Methode

```kotlin
private fun updatePipelineState(newState: PipelineUiState) {
    val old = state
    state = newState
    refreshRecordButtonFromState()
    if (old != newState) {
        onPipelineUiStateChanged?.invoke(old, newState)
        stateManager.refresh()  // Nur bei tatsächlicher State-Änderung
    }
}
```

#### 2c) Neue API-Methoden (ersetzen die bisherigen)

**`preparePipeline()` — neu (Idle → Preparing):**
```kotlin
/**
 * Enters the "Preparing" state: audio is being uploaded, pipeline hasn't started yet.
 * Disables the record button and shows "Sending...".
 * Transitions: Idle → Preparing → Running (via [startPipeline]).
 */
fun preparePipeline() {
    // Save text colors before changing them (for restoreRecordButtonIdle)
    if (savedRecordButtonTextColors == null) {
        savedRecordButtonTextColors = views.recordButton.textColors
    }
    updatePipelineState(PipelineUiState.Preparing)
}
```

**`startPipeline()` — ersetzt `showPipelineProgress()`:**
```kotlin
fun startPipeline(totalSteps: Int, autoEnterActive: Boolean, initialCompletedSteps: Int = 0) {
    // Text-Farben sichern (für restoreRecordButtonIdle nach Pipeline-Ende)
    if (savedRecordButtonTextColors == null) {
        savedRecordButtonTextColors = views.recordButton.textColors
    }

    // Prompts-Leiste: Pipeline-Progress-Modus
    currentMode = PromptAreaMode.PIPELINE_PROGRESS
    views.pipelineStepsContainer.removeAllViews()
    views.infoCl.visibility = View.GONE
    stepRows.clear()
    this.totalSteps = totalSteps
    currentStep = 0

    // State setzen
    updatePipelineState(PipelineUiState.Running(
        totalSteps = totalSteps,
        completedSteps = initialCompletedSteps,
        currentStepName = "",
        autoEnterActive = autoEnterActive
    ))

    // Gesamt-Timer starten
    latestPipelineElapsedMs = 0
    pipelineTotalTimer?.stop()
    pipelineTotalTimer = ElapsedTimer.start(views.mainHandler) { ms ->
        latestPipelineElapsedMs = ms
        refreshRecordButtonFromState()
        val s = state
        if (s is PipelineUiState.Running) {
            onPipelineTimerTick?.invoke(s, ms)
        }
    }
}
```

**`stopPipeline()` — ersetzt `resetToPromptButtons()`:**
```kotlin
fun stopPipeline() {
    pipelineTotalTimer?.stop()
    pipelineTotalTimer = null
    activeTimer?.stop()
    activeTimer = null
    // WICHTIG: Mode VOR State setzen, damit stateManager.refresh() (via updatePipelineState)
    // die korrekte Visibility berechnet. Das bestehende resetToPromptButtons() macht es genauso.
    currentMode = PromptAreaMode.PROMPT_BUTTONS
    updatePipelineState(PipelineUiState.Idle)
    // onPipelineUiStateChanged feuert → Service kann QWERTZ resetten
}
```

**`setAutoEnter(active)` — neu:**
```kotlin
fun setAutoEnter(active: Boolean) {
    val s = state
    if (s is PipelineUiState.Running && s.autoEnterActive != active) {
        updatePipelineState(s.copy(autoEnterActive = active))
    }
}
```

#### 2c-bis) DRY-Helper `updateRunningState()`

Die Aufrufseiten `addRunningStep`, `completeStep` und `failStep` enthalten alle dasselbe Pattern: State-Check → `copy(...)` → `updatePipelineState(...)`. Statt das Pattern dreimal zu kopieren, wird ein Helper eingeführt:

```kotlin
/**
 * Mutates the current state IFF it is [PipelineUiState.Running].
 * No-op for Idle/Preparing.
 */
private inline fun updateRunningState(transform: (PipelineUiState.Running) -> PipelineUiState.Running) {
    val s = state
    if (s is PipelineUiState.Running) {
        updatePipelineState(transform(s))
    }
}
```

Aufrufseiten werden damit zu One-Liner:
```kotlin
updateRunningState { it.copy(currentStepName = stepName) }
updateRunningState { it.copy(completedSteps = it.completedSteps + 1) }
```

Der Helper reduziert Duplikation und macht zukünftige State-Updates einheitlich — wenn ein neuer State-Typ hinzukommt, muss nur der Helper angefasst werden.

#### 2d) `addRunningStep()` und `completeStep()` anpassen

**`addRunningStep()`:** Neben der bestehenden UI-Logik (Step-Row erstellen, Timer starten) auch den State aktualisieren — via Helper:
```kotlin
// Bestehende Logik bleibt (Step-Row für Prompts-Leiste)
// Zusätzlich:
updateRunningState { it.copy(currentStepName = stepName) }
```

**`completeStep()`:** Nach Timer-Stop den completed-Counter hochzählen:
```kotlin
// Bestehende Logik bleibt (Checkmark-Icon, Duration-Text)
// Zusätzlich:
updateRunningState { it.copy(completedSteps = it.completedSteps + 1) }
```

**`failStep()`:** Auch nach Fehler den Counter hochzählen (ein fehlgeschlagener Step ist "abgeschlossen" im Sinne des Fortschritts):
```kotlin
// Bestehende Logik bleibt (Cross-Icon)
// Zusätzlich:
updateRunningState { it.copy(completedSteps = it.completedSteps + 1) }
```

#### 2e) Record-Button-Rendering aus State

**`refreshRecordButtonFromState()` — ersetzt `updateRecordButtonForStep()`:**
```kotlin
private fun refreshRecordButtonFromState() {
    when (val s = state) {
        is PipelineUiState.Idle -> {
            views.recordButton.isEnabled = true
            // Compound-Drawables entfernen (Auto-Enter-Icon)
            views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        }
        is PipelineUiState.Preparing -> {
            // "Sending..." — Button disabled, Send-Icon links, kein Auto-Enter-Toggle
            views.recordButton.isEnabled = false
            views.recordButton.setText(R.string.dictate_sending)
            views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_baseline_send_20, 0, 0, 0)
            views.recordButton.setTextColor(Color.WHITE)
        }
        is PipelineUiState.Running -> {
            // Pipeline läuft — Button enabled für Auto-Enter-Toggle
            views.recordButton.isEnabled = true
            // Text: "StepName  0/3  4.2s"
            val counter = "${s.completedSteps}/${s.totalSteps}"
            val timer = formatElapsedCompact(latestPipelineElapsedMs)
            views.recordButton.text = if (s.currentStepName.isNotEmpty()) {
                "${s.currentStepName}  $counter  $timer"
            } else {
                "$counter  $timer"
            }
            views.recordButton.setTextColor(Color.WHITE)
            // Auto-Enter-Icon (rechtes Compound-Drawable)
            updateAutoEnterAppearance(s.autoEnterActive)
        }
    }
}
```

#### 2f) Entfernte Methoden

Folgende Methoden werden **komplett entfernt** (Logik jetzt in State-Pattern):
- `showAutoEnterToggle(active, onToggle, onRestore)` → weg
- `hideAutoEnterToggle()` → weg
- `updateAutoEnterToggle(active)` → weg
- `updateRecordButtonForStep(stepName)` → weg (ersetzt durch `refreshRecordButtonFromState()`)

`autoEnterToggleCallback` und `autoEnterRestoreCallback` Felder werden ebenfalls entfernt.

**`updateAutoEnterAppearance(active)` bleibt** — wird jetzt von `refreshRecordButtonFromState()` aufgerufen statt von `showAutoEnterToggle()`.

#### 2g) `stopActiveTimer()` anpassen

```kotlin
fun stopActiveTimer() {
    activeTimer?.stop()
    activeTimer = null
    pipelineTotalTimer?.stop()
    pipelineTotalTimer = null
}
```

### Phase 3: QWERTZ-Button Pipeline-Anzeige

**Datei:** `RecordingUiController.kt`

#### 3a) Original-State-Saving extrahieren

Bestehende Duplikation entfernen — die Logik zum Sichern der Original-Werte wird einmal definiert:

```kotlin
private var qwertzRecOriginalIconTint: ColorStateList? = null
private var qwertzRecOriginalIconGravity: Int? = null

private fun ensureQwertzOriginalsSaved(btn: MaterialButton) {
    if (qwertzRecOriginalIconPadding == null) {
        qwertzRecOriginalIconPadding = btn.iconPadding
        qwertzRecOriginalTextColors = btn.textColors
        qwertzRecOriginalIconTint = btn.iconTint
        qwertzRecOriginalIconGravity = btn.iconGravity
        qwertzRecOriginalPadding = intArrayOf(
            btn.paddingLeft, btn.paddingTop,
            btn.paddingRight, btn.paddingBottom
        )
    }
}
```

**`updateQwertzRecButton(false)` Idle-Reset erweitern:** Neben den bestehenden Restaurationen auch `iconTint` und `iconGravity` aus dem gesicherten Wert restaurieren (statt hart `ICON_GRAVITY_TEXT_START` zu setzen — das wäre bei abweichendem Theme-Default falsch):
```kotlin
qwertzRecOriginalIconTint?.let { recButton.iconTint = it }
qwertzRecOriginalIconGravity?.let { recButton.iconGravity = it }
```

**`updateQwertzRecButton(isActive=true)` Zeile 211-217:** Die bestehende If-Guard durch `ensureQwertzOriginalsSaved(recButton)` ersetzen.

#### 3b) Pipeline-Anzeige-Methode (typsicher via State)

**Performance-Aufteilung:** Der Timer-Tick feuert 10×/Sekunde. Die komplette Methode (Icon, Color, Padding, Text) bei jedem Tick auszuführen erzwingt ein teures Re-Measure auf der Keyboard-UI. Deshalb wird die Darstellung in zwei Methoden aufgeteilt:

```kotlin
/**
 * Einmaliger Eintritt in den Pipeline-Display-Modus:
 * setzt Icon, Color, Padding — wird nur bei State-Übergang (Idle/Preparing → Running)
 * aufgerufen, nicht bei jedem Timer-Tick.
 */
fun enterPipelineDisplay(state: PipelineUiState.Running) {
    val recButton = qwertzRecButtonProvider() ?: return
    ensureQwertzOriginalsSaved(recButton)
    recButton.icon = null
    recButton.setTextColor(Color.WHITE)
    val density = recButton.resources.displayMetrics.density
    recButton.setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
    // Initialer Text (Timer = 0); wird sofort vom ersten Tick überschrieben
    recButton.text = renderPipelineText(state, 0)
}

/**
 * Pro-Tick-Update im Pipeline-Modus: nur der Text wird neu gesetzt.
 * Kein Icon/Color/Padding-Update — diese Properties wurden einmalig in
 * [enterPipelineDisplay] gesetzt.
 */
fun updatePipelineTimer(state: PipelineUiState.Running, elapsedMs: Long) {
    val recButton = qwertzRecButtonProvider() ?: return
    recButton.text = renderPipelineText(state, elapsedMs)
}

private fun renderPipelineText(state: PipelineUiState.Running, elapsedMs: Long): String {
    val counter = "${state.completedSteps}/${state.totalSteps}"
    val enterIndicator = if (state.autoEnterActive) " \u21B5" else ""
    val timer = formatElapsedCompact(elapsedMs)
    return "$counter$enterIndicator\n$timer"
}
```

**Ground-Truth-Hinweis:** Der echte Code (`RecordingUiController.kt:250`) nutzt noch die monolithische `updateQwertzRecButtonForPipeline()`, die bei jedem Tick Icon/Padding/Color setzt. Das ist eine bewusst zu optimierende Altlast — die Aufteilung in `enterPipelineDisplay()` + `updatePipelineTimer()` ist als Migration in diesem Plan dokumentiert. Konsistent zum bestehenden Recording-Pfad (`onTimerTick` setzt nur `btn.text`).

**Signatur-Entscheidung:** Die Methode nimmt `PipelineUiState.Running` statt 3 einzelne Parameter. Das ist typsicherer — wenn `Running` ein neues Feld bekommt, muss die Signatur nicht ändern. Der QWERTZ-Button zeigt den Auto-Enter-State als ↵-Textzeichen (statt als Compound-Drawable wie beim großen Button), weil der Platz bei 42dp Höhe knapp ist.

**Alternative-Signatur (diskutiert, nicht umgesetzt):** `updateQwertzRecButtonForPipeline(state: PipelineUiState, elapsedMs: Long)` mit internem `when`. Das würde den Aufrufer entlasten (keine Type-Checks im Service), aber die Methode müsste intern sowohl `Preparing` als auch `Running` rendern. **Der Ground-Truth-Code nutzt `PipelineUiState.Running`** (Engeres Eingangstyp) — `Preparing` wird im Service nicht über diese Methode gerendert, sondern der QWERTZ-Button bleibt in der `Preparing`-Phase unverändert (Recording-Animation-Reste bis zum `Idle → Preparing`-Callback), bzw. der Service ruft bei Preparing den bestehenden `updateQwertzRecButton(false)`/Recording-Reset-Pfad auf. Siehe Callback-Block in Phase 4a für Details.

**Idle-Reset:** Die bestehende `updateQwertzRecButton(false)` stellt den Idle-State her. Kein neues Reset nötig.

### Phase 4: Service-Verdrahtung

**Datei:** `DictateInputMethodService.java`

#### 4a) Callbacks registrieren (in `onCreateInputView()`, nach Controller-Erstellung)

Statt Lambdas in `showPipelineProgress()` durchzureichen, werden die Callbacks **einmalig** bei Controller-Erstellung gesetzt:

```java
// Nach Erstellung von uiController und recordingUiController:

uiController.setOnPipelineTimerTick((runningState, elapsedMs) -> {
    // Nur Text-Update, kein Re-Measure — konsistent zum Recording-Pfad
    if (recordingUiController != null) {
        recordingUiController.updatePipelineTimer(runningState, elapsedMs);
    }
    return kotlin.Unit.INSTANCE;
});

uiController.setOnPipelineUiStateChanged((oldState, newState) -> {
    if (recordingUiController == null) return kotlin.Unit.INSTANCE;
    if (newState instanceof PipelineUiState.Idle) {
        recordingUiController.updateQwertzRecButton(false);  // QWERTZ → Mic-Icon
    } else if (newState instanceof PipelineUiState.Running) {
        // Einmalige Setup-Kosten beim State-Übergang (Icon/Color/Padding)
        recordingUiController.enterPipelineDisplay((PipelineUiState.Running) newState);
    } else if (newState instanceof PipelineUiState.Preparing) {
        // Preparing: Recording ist gestoppt, aber Pipeline-Rendering läuft noch nicht.
        // Der QWERTZ-Button wird NICHT aktiv gesetzt — der Idle-Pfad (Mic-Icon) ist korrekt,
        // bis der erste Timer-Tick des Running-States den Pipeline-Render übernimmt.
        recordingUiController.updateQwertzRecButton(false);
    }
    // Running-Branch oben: enterPipelineDisplay setzt Icon/Color/Padding einmalig.
    // Ab da übernimmt onPipelineTimerTick (→ updatePipelineTimer) das reine Text-Update.
    return kotlin.Unit.INSTANCE;
});
```

**Warum einmalig statt pro Pipeline-Start:** Die Callbacks referenzieren Service-Felder (`recordingUiController`). Da `uiController` bei View-Recreation neu erstellt wird, werden auch die Callbacks neu registriert. Kein Leak-Risiko.

#### 4b) `onRecordClicked()` pipeline-aware machen (Zeile 1488-1497)

**Kein Click-Listener-Austausch mehr.** Stattdessen prüft `onRecordClicked()` den Pipeline-State:

```java
@Override
public void onRecordClicked() {
    infoBarController.dismiss();
    if (uiController.isPipelineRunning()) {
        // Pipeline läuft → Auto-Enter toggeln (wirkt für BEIDE Buttons)
        toggleAutoEnterOverride();
    } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
        openSettingsActivity();
    } else if (recordingStateController.getState() instanceof RecordingState.Idle) {
        startRecording();
    } else if (recordingStateController.getState().isRecordingOrPaused()) {
        stopRecording();
    }
}
```

**Wichtig: Zustandsquelle ist der UI-State, nicht der Orchestrator.** Früher war der Check `pipelineOrchestrator.isRunning()`, was während `Preparing` falsch ist (Orchestrator ist dort noch nicht running, aber das Recording ist schon gestoppt — der User würde ohne `isEnabled = false`-Schutz eine neue Aufnahme starten können). Der neue Check `uiController.isPipelineRunning()` (siehe Block 2 — Convenience-Properties) liest den UI-State als Single Source of Truth. Während `Preparing` gibt `isPipelineRunning()` false zurück; der Record-Button ist zusätzlich über `refreshRecordButtonFromState()` disabled, sodass der Klick gar nicht ankommen kann — doppelter Schutz.

**Ground-Truth-Hinweis:** Der echte Code (`DictateInputMethodService.java:1512`) prüft aktuell noch `pipelineOrchestrator.isRunning()`. Das ist eine bewusst zu migrierende Altlast — im Rahmen dieses Plans wird der Check auf `uiController.isPipelineRunning()` umgestellt, um Preparing korrekt abzudecken.

**Warum das für beide Buttons funktioniert:** Sowohl der große Record-Button (via `MainButtonsController.Callback.onRecordClicked()`) als auch der QWERTZ-Rec-Button (via `QwertzKeyboardController.onRecord` → `onRecordClicked()`) rufen dieselbe Methode auf. Ein Ort, eine Logik.

#### 4c) `toggleAutoEnterOverride()` aktualisiert den State (Zeile 1273-1277)

```java
private void toggleAutoEnterOverride() {
    if (autoEnterOverride == null) return;
    autoEnterOverride = !autoEnterOverride;
    // Service ist Single Source of Truth für autoEnterOverride —
    // setzt den konkreten Wert statt unabhängig zu toggeln (keine Sync-Gefahr)
    uiController.setAutoEnter(autoEnterOverride);
}
```

**Vorher:** `uiController.updateAutoEnterToggle(autoEnterOverride)` — nur Record-Button.
**Nachher:** `uiController.setAutoEnter(value)` → `updatePipelineState()` → Callback feuert → QWERTZ wird auch aktualisiert. `setAutoEnter` statt `toggleAutoEnter` eliminiert die Dual-Source-of-Truth-Gefahr — der Service gibt den konkreten Wert vor.

#### 4d) `runTranscriptionViaOrchestrator()` vereinfachen (Zeile 1070-1113)

```java
// Vorher (6 Zeilen Auto-Enter-Setup + Lambda):
uiController.showPipelineProgress(totalSteps);
autoEnterOverride = sp.getBoolean("net.devemperor.dictate.auto_enter", false);
uiController.showAutoEnterToggle(autoEnterOverride,
    () -> { toggleAutoEnterOverride(); return kotlin.Unit.INSTANCE; },
    () -> { mainButtonsController.reRegisterRecordButtonListener(); return kotlin.Unit.INSTANCE; });

// Nachher (3 Zeilen — mit Preparing-State):
uiController.preparePipeline();  // Idle → Preparing, Button zeigt "Sending..."
autoEnterOverride = sp.getBoolean("net.devemperor.dictate.auto_enter", false);
uiController.startPipeline(totalSteps, autoEnterOverride);  // Preparing → Running
```

**Ablauf mit Preparing:** `runTranscriptionViaOrchestrator()` wird aufgerufen, sobald das Recording gestoppt und die Audio-Datei bereit zur Übertragung ist. In diesem Moment wird `preparePipeline()` gerufen — der Record-Button schaltet disabled und zeigt "Sending...". Anschließend ermittelt der Service die Anzahl der Steps (abhängig von Prompts) und ruft `startPipeline(totalSteps, autoEnter)` — der State wechselt von `Preparing` auf `Running` und der Timer startet. Der User sieht also den typischen Hochlade-Moment mit korrekter UX.

Der Record-Button behält seinen Click-Listener (`onRecordClicked()`). Kein `reRegisterRecordButtonListener()` nötig.

#### 4e) `runStandalonePromptViaOrchestrator()` vereinfachen (Zeile 1119-1151)

```java
// Vorher:
if (uiController.getCurrentMode() != KeyboardUiController.PromptAreaMode.PIPELINE_PROGRESS) {
    uiController.showPipelineProgress(1);
    autoEnterOverride = sp.getBoolean("net.devemperor.dictate.auto_enter", false);
    uiController.showAutoEnterToggle(autoEnterOverride, ...);
}

// Nachher:
autoEnterOverride = sp.getBoolean("net.devemperor.dictate.auto_enter", false);
// WICHTIG: Bei Chain-Wechsel (pendingLivePromptChain) IMMER startPipeline aufrufen —
// auch wenn der State bereits Running ist. Sonst erbt die neue Prompt-Chain den
// Counter der vorherigen Transkription (z.B. "2/2 8.4s" statt "0/1 0.0s").
// Siehe Block 8 / SEC-4-2.
uiController.startPipeline(1, autoEnterOverride, 0);  // Counter-Reset durch initialCompletedSteps=0
```

**Hinweis:** Der frühere Guard `if (!(state instanceof Running)) { ... }` wird ersatzlos gestrichen. Der Counter-Reset ist Teil des `Running → Running`-Übergangs und wird über `startPipeline()` explizit gewollt.

**Mode-Check:** Statt `getCurrentMode()` ist der Pipeline-State jetzt die Single Source of Truth — aber im umgeschriebenen Code spielt das hier keine Rolle mehr, da `startPipeline()` unconditional aufgerufen wird.

#### 4f) `onPipelineFinished()` vereinfachen (Zeile 1245-1266)

```java
mainHandler.post(() -> {
    if (uiController == null) return;
    uiController.stopPipeline();  // → updatePipelineState(Idle) → Callback → QWERTZ reset
    uiController.restoreRecordButtonIdle(
        getDictateButtonText(),
        R.drawable.ic_baseline_mic_20,
        R.drawable.ic_baseline_folder_open_20);
    // QWERTZ-Reset passiert automatisch via onPipelineUiStateChanged Callback
});
```

**Was wegfällt:** `uiController.resetToPromptButtons()` wird durch `stopPipeline()` ersetzt. Manueller QWERTZ-Reset fällt weg (automatisch via Callback).

#### 4g) `restoreUiState()` anpassen (Zeile 619-653)

```java
if (pipelineOrchestrator.isRunning()) {
    int total = pipelineOrchestrator.getTotalSteps();
    int completedSoFar = pipelineOrchestrator.getCompletedSteps();
    String stepName = pipelineOrchestrator.getCurrentStepName();

    // startPipeline statt showPipelineProgress — State wird korrekt gesetzt
    boolean autoEnter = autoEnterOverride != null ? autoEnterOverride
        : sp.getBoolean("net.devemperor.dictate.auto_enter", false);
    uiController.startPipeline(total > 0 ? total : 1, autoEnter, completedSoFar);

    // Prompts-Leiste: aktuellen Step anzeigen
    uiController.addRunningStep(stepName != null ? stepName : "\u2026");
}
```

**Was wegfällt:** Separater `showAutoEnterToggle()`-Aufruf und QWERTZ-Callback-Lambda. Der State enthält bereits `autoEnterActive`, der Callback ist schon in 4a registriert.

**Preparing-Fall:** Das Fenster "Audio-Upload läuft, Pipeline noch nicht gestartet" ist extrem kurz, aber es kann durch eine Rotation theoretisch getroffen werden. Der Orchestrator meldet in dieser Phase `isRunning() == false` — der aktuelle `restoreUiState()`-Code würde den Preparing-Zustand verlieren. Zwei Optionen:
- **(a) Pragmatisch (Ground-Truth-Stand):** Den Fall ignorieren. `restoreUiState()` prüft nur `isRunning()`. Bei Rotation während Preparing landet man kurz in Idle, bis der echte `Running`-Übergang via `onPipelineUiStateChanged` den State korrekt setzt. Akzeptables Flackern für einen extrem seltenen Zustand.
- **(b) Vollständig:** Service hält ein `volatile boolean isPreparing`-Flag, das zwischen `preparePipeline()` und `startPipeline()` true ist, und wird in `restoreUiState()` ausgewertet: `if (isPreparing) uiController.preparePipeline();`.

**Entscheidung:** Variante (a) — der Ground-Truth-Code setzt sie um, der Zeit-Fenster ist zu klein, um einen Flag-basierten Fix zu rechtfertigen. Wird als bewusste Abweichung dokumentiert.

#### 4h) QWERTZ `onLayoutRebuilt` Callback (Zeile 350-357)

```java
() -> {
    if (recordingUiController != null && recordingStateController != null && uiController != null) {
        PipelineUiState s = uiController.getState();
        if (s instanceof PipelineUiState.Running) {
            // Pipeline active — set current state, timer tick will keep updating.
            // elapsedMs wird aus dem Controller gelesen (read-only `latestPipelineElapsedMs`),
            // damit beim Rebuild kein Flackern "0.0s" entsteht — siehe Block 9 (SEC-3-4).
            PipelineUiState.Running running = (PipelineUiState.Running) s;
            // Rebuild: erst Setup (Icon/Color/Padding), dann Timer-Text
            recordingUiController.enterPipelineDisplay(running);
            recordingUiController.updatePipelineTimer(
                running, uiController.getLatestPipelineElapsedMs());
        } else if (s instanceof PipelineUiState.Preparing) {
            // Preparing: kein Pipeline-Render auf QWERTZ — Mic-Icon bleibt.
            recordingUiController.updateQwertzRecButton(false);
        } else {
            // Idle: Recording-State rendern (Mic bei Idle, Send bei Recording/Paused)
            recordingUiController.updateQwertzRecButton(
                recordingStateController.getState().isRecordingOrPaused()
            );
        }
    }
    return kotlin.Unit.INSTANCE;
}
```

**Verbesserung vs. alter Plan:** Kein `pipelineOrchestrator.getCompletedSteps()` + `getTotalSteps()` mehr — stattdessen wird der `PipelineUiState.Running` direkt vom `KeyboardUiController` gelesen. Single Source of Truth.

#### 4i) Weitere `resetToPromptButtons()`-Callsites migrieren

Zwei weitere Stellen im Service rufen `resetToPromptButtons()` auf und müssen auf `stopPipeline()` umgestellt werden:

1. **`onPipelineCancelClicked()` (Zeile ~1629):** User bricht Pipeline manuell ab → `resetToPromptButtons()` durch `stopPipeline()` ersetzen.
2. **`onFinishInputView()` (Zeile ~506):** Keyboard wird geschlossen während Pipeline läuft → `resetToPromptButtons()` durch `stopPipeline()` ersetzen.

Falls `onStartInputView()` ebenfalls `resetToPromptButtons()` aufruft: analog migrieren.

#### 4j) `MainButtonsController.reRegisterRecordButtonListener()` entfernen

Diese Methode existiert nur, um den Click-Listener nach `hideAutoEnterToggle()` wiederherzustellen. Da der Click-Listener nicht mehr ausgetauscht wird, ist sie überflüssig. Alle Referenzen entfernen.

**Ebenso entfernen:** Den `onRestore`-Parameter aus dem `MainButtonsController.Callback`-Interface, falls er nur dafür existiert. (Prüfung bei Implementation nötig.)

---

## Dateien

| Datei | Änderung | Umfang |
|-------|----------|--------|
| `PipelineUiState.kt` | **Neu:** Sealed Class `Idle` + `Running` + KDoc State-Pattern-Doku | ~20 Zeilen |
| `ElapsedTimer.kt` | +`formatElapsedCompact()` Top-Level-Funktion | 3 Zeilen |
| `PipelineOrchestrator.kt` | +`getCompletedSteps()` Getter | 3 Zeilen |
| `KeyboardUiController.kt` | +`startPipeline()`, +`stopPipeline()`, +`setAutoEnter()`, +`refreshRecordButtonFromState()`, −`showAutoEnterToggle()`, −`hideAutoEnterToggle()`, −`updateAutoEnterToggle()`, −`updateRecordButtonForStep()`, −`formatDuration()`, −Callback-Felder | ~50 Zeilen netto |
| `RecordingUiController.kt` | +`ensureQwertzOriginalsSaved()`, +`updateQwertzRecButtonForPipeline(Running, Long)`, +`iconTint` Save/Restore | ~30 Zeilen |
| `DictateInputMethodService.java` | Callback-Registrierung, `onRecordClicked()` pipeline-aware, vereinfachte Pipeline-Starts, Migration aller `resetToPromptButtons()`-Callsites, −`reRegisterRecordButtonListener()` | ~45 Zeilen netto |
| `MainButtonsController.kt` | −`reRegisterRecordButtonListener()` (Prüfung bei Impl.) | ~−5 Zeilen |

---

## Edge Cases

### Timer nach View-Recreation (Rotation)
Der Gesamt-Timer startet nach Rotation bei 0. Das ist akzeptabel — die Prompts-Leiste zeigt weiterhin die genauen per-Step-Zeiten. Der `PipelineUiState` (completedSteps, totalSteps, autoEnterActive) wird korrekt wiederhergestellt via `restoreUiState()` → `startPipeline(total, autoEnter, completedSoFar)`.

### QWERTZ-Button null
`qwertzRecButtonProvider()` gibt null zurück wenn die QWERTZ-Tastatur nicht sichtbar ist. Alle Aufrufe haben bereits Null-Guards.

### Text-Abschneidung im Record-Button
"Transkription  0/3  4.2s" ist ~25 Zeichen. Der Record-Button ist full-width, das passt. Bei langen Step-Namen (z.B. Prompt-Namen) wird der Text automatisch via `ellipsize` abgeschnitten.

### Schnelle Step-Übergänge
Wenn `completeStep()` und `addRunningStep()` direkt nacheinander aufgerufen werden, feuert `updatePipelineState()` zweimal: erst `completedSteps + 1`, dann `currentStepName = neuerName`. Beide Updates propagieren korrekt über den Callback.

### Live-Prompt-Chain / Counter-Reset

Wenn eine Transkription mit `pendingLivePromptChain = true` beendet wird, ruft `onPipelineFinished()` den Reset NICHT (Early-Return). Stattdessen wird unmittelbar `runStandalonePromptViaOrchestrator(liveEntity)` aufgerufen, um die nächste Pipeline-Runde (den Live-Prompt) zu starten. In diesem Moment ist `state is PipelineUiState.Running` noch `true` — mit `totalSteps = N` und `completedSteps = N` der vorherigen Transkription.

**Ohne Counter-Reset** würde der User "N/N 8.4s" sehen, während der neue Standalone-Prompt mit `totalSteps=1` den Counter zu "N/1" oder "N+1/1" überschreibt — inkonsistent und irritierend.

**Fix:** `runStandalonePromptViaOrchestrator()` ruft `uiController.startPipeline(1, autoEnterOverride, 0)` **unconditional** (kein Guard auf "State ist bereits Running") — `initialCompletedSteps = 0` setzt den Counter explizit zurück. Der `autoEnterOverride` wird dabei erhalten, weil er als Service-Feld lebt (siehe Auto-Enter-Wahrheit).

Der frühere Guard `if (!(state instanceof Running)) { startPipeline(...) }` wird ersatzlos gestrichen — die Chain-Fall-Invariante "neuer Run = neuer Counter" ist wichtiger als die Mini-Optimierung "startPipeline nicht doppelt aufrufen".

### Auto-Enter-Wahrheit: State-Feld vs. Service-Feld (bewusste Abweichung)

Der Plan behauptet "Service ist Single Source of Truth für `autoEnterOverride`", behält aber gleichzeitig `autoEnterActive` als Feld in `PipelineUiState.Running`. Damit existiert der Wert an zwei Orten — `commitTextToInputConnection()` liest `autoEnterOverride`, `refreshRecordButtonFromState()` liest `state.autoEnterActive`. Zwei diskutierte Varianten:

- **Variante (a) — State als alleinige Wahrheit:** `autoEnterActive` wird als berechnete Property am Controller aus `state` abgeleitet (`fun isAutoEnterActive() = (state as? Running)?.autoEnterActive ?: false`). Das Service-Feld `autoEnterOverride` entfällt, `commitTextToInputConnection()` ruft `uiController.isAutoEnterActive()` statt eines eigenen Feldes. Passt besser zum State-Pattern (eine Wahrheit), aber erfordert, dass der Controller den "Pre-Pipeline"-Default vom Service übergeben bekommt (Preparing vor Running existiert, aber `autoEnterActive` ist dort noch nicht relevant).
- **Variante (b) — Service hält das Feld, State spiegelt:** Der Service bleibt Owner, der State-Wert wird über `setAutoEnter()` gespiegelt. Das ist die Ground-Truth-Implementierung. Nachteil: Dual-Source-of-Truth, zwei Code-Pfade schreiben unabhängig.

**Ground-Truth-Entscheidung:** Der Code nutzt Variante (b) (Service-Feld `autoEnterOverride` + Spiegelung via `setAutoEnter(autoEnterOverride)`). Die Sync-Gefahr wird dadurch entschärft, dass `setAutoEnter` einen konkreten Wert übernimmt (nicht toggelt) — ein einziger Schreibpfad vom Service aus. **Bewusste Abweichung vom State-Pattern-Puristen-Ideal**, weil der Service den Wert auch außerhalb der Pipeline braucht (als Default für den nächsten Run) und der Controller-State ausschließlich während `Running` existiert. Die Struktur ist nicht ideal, aber funktional korrekt und in einem nachgelagerten Refactor mit Variante (a) zu bereinigen.

### Auto-Enter-Toggle Synchronisation
`toggleAutoEnterOverride()` aktualisiert `autoEnterOverride` (Service-Feld) **und** `PipelineUiState.Running.autoEnterActive` (via `uiController.setAutoEnter(autoEnterOverride)`). Die Sync-Gefahr ist eliminiert, weil `setAutoEnter(active)` den konkreten Wert vom Service übernimmt (nicht unabhängig toggelt):
- Service-Feld steuert die `isAutoEnterActive()` Entscheidung in `commitTextToInputConnection()`
- State-Feld steuert die UI-Darstellung auf beiden Buttons
- Ein Klick auf **entweder** Button aktualisiert **beide** (über denselben Codepfad)
- **Kein unabhängiges Toggeln** — der Service ist Single Source of Truth

### failStep-Counter ohne visuelle Unterscheidung im Record-Button

Wenn der letzte Step in einer Pipeline per `failStep()` fehlschlägt, zählt der Counter im Record-Button und im QWERTZ-Button genauso hoch wie bei Erfolg ("N/N 4.2s"). Der User sieht den Unterschied zwischen Erfolg und Fehler also NUR in der Prompts-Leiste oben (Cross-Icon in rot). Bei sofortigem `onPipelineFinished()`-Reset kann dieser Indikator verpasst werden; im QWERTZ-Button ist überhaupt kein Fehler-Hinweis sichtbar.

**Bewusste Entscheidung:** Fehler-Feedback wird NUR in der Prompts-Leiste angezeigt; Record-Button und QWERTZ-Button zeigen ausschließlich Fortschritt (keine Farb- oder Icon-Unterscheidung für Fehler). Gründe:
- Der Record-Button hat keinen Platz für ein zusätzliches Fehler-Icon neben Step-Name/Counter/Timer.
- Der QWERTZ-Button ist mit 42dp Höhe zu klein für eine verlässliche visuelle Differenzierung.
- Fehler produzieren ohnehin einen InfoBar-Toast mit der Error-Message — das ist der Haupt-Feedback-Kanal.

Eine zukünftige Erweiterung könnte ein `Running.hasFailure: Boolean`-Feld + roten Counter einführen; das ist aber außerhalb des Scopes dieses Plans.

### onRecordClicked() während Pipeline
Ohne Click-Listener-Austausch könnte der User theoretisch versuchen, während der Pipeline eine Aufnahme zu starten. Der neue `uiController.isPipelineRunning()`-Check in `onRecordClicked()` fängt das ab → Toggle statt Recording. Während `Preparing` ist der Record-Button zusätzlich über `refreshRecordButtonFromState()` disabled, sodass der Klick gar nicht durchkommt (doppelter Schutz).

### Auto-Enter-Toggle auf QWERTZ-Button: Visuelles Feedback
Der QWERTZ-Button zeigt ↵ als Text-Zeichen (nicht als Compound-Drawable). Beim Toggle wechselt die Anzeige zwischen "0/1 ↵\n4.2s" und "0/1\n4.2s". Das ist ausreichend visuelles Feedback für den kleinen Button.

---

## Verification

### Pipeline-Fortschritt
- [ ] Einfache Transkription (1 Step): Button zeigt "Transkription 0/1 1.2s" → "1/1" kurz sichtbar → Idle
- [ ] Transkription + Formatierung (2 Steps): "0/2" → "1/2" → "2/2" → Idle
- [ ] Transkription + 2 Prompts (3 Steps): Counter zählt korrekt hoch
- [ ] QWERTZ-Button zeigt "0/1\n1.2s" während Pipeline
- [ ] QWERTZ-Button kehrt zu Mic-Icon nach Pipeline-Ende zurück
- [ ] QWERTZ Layout-Wechsel während Pipeline: Button behält Pipeline-State

### Auto-Enter-Toggle (beide Buttons)
- [ ] Klick auf Record-Button während Pipeline → Auto-Enter toggelt, Icon ↵ wechselt
- [ ] Klick auf QWERTZ-Rec-Button während Pipeline → Auto-Enter toggelt, ↵ im Text wechselt
- [ ] Toggle auf einem Button → anderer Button zeigt ebenfalls neuen State
- [ ] Auto-Enter aktiv + Pipeline endet → Enter wird ausgeführt
- [ ] Auto-Enter inaktiv + Pipeline endet → kein Enter
- [ ] Kein Click-Listener-Austausch: Record-Button startet nie versehentlich Aufnahme während Pipeline

### State-Restoration (Rotation)
- [ ] Rotation während Pipeline: Counter stimmt, Timer startet bei 0, Auto-Enter-State erhalten
- [ ] Rotation während Pipeline mit Auto-Enter aktiv → Toggle funktioniert nach Rotation
- [ ] Schnelle Mehrfach-Rotation → kein Crash, State konsistent

### Error-Szenarien
- [ ] API-Error bei Transkription → `failStep()` zählt Counter hoch, QWERTZ-Button zeigt aktualisierten Counter
- [ ] Prompt-Fehler in der Mitte der Queue → Counter stimmt, nachfolgende Steps laufen weiter
- [ ] QWERTZ-Button nach Pipeline-Error → kehrt zu Mic-Icon zurück

### Regression
- [ ] Normaler Aufnahme-Flow: Record → Stop → Pipeline → Idle (unverändert)
- [ ] Standalone-Prompt ohne Aufnahme → Pipeline-Fortschritt korrekt
- [ ] `reRegisterRecordButtonListener()` komplett entfernt → kein Compile-Error
- [ ] Build erfolgreich
