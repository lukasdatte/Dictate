# State Machine + Visibility-Ownership Tiefenanalyse

**Datum:** 2026-05-07
**Branch:** feature/language-chip-curation
**Trigger:** Vorbereitung Refactor — hybride Visibility-Mutation auf `resend_btn` zwischen `KeyboardStateManager` (SSOT-Anspruch) und `RecordingUiController` (mutiert direkt) führt zu Race-Bedingungen. Gleiche Frage für andere Buttons offen.
**Verwandte Doku:**
- [`main-button-area-inventory.md`](../main-button-area-inventory.md) — Inventar (Buttons / Modi / Visibility-Matrix)
- [`motionlayout-architecture-options.md`](../motionlayout-architecture-options.md)
- [`keyboard-layout-refactor.md`](../../keyboard-layout-refactor.md)

> Diese Datei vertieft das Inventar entlang der State-Maschine, Owner-Verletzungen und der für A+B-Background-Send-Persistence anstehenden State-Erweiterung. Inventar-Inhalte (Listener-Tabellen, XML-Layout, Animations-Library) werden bewusst NICHT dupliziert — nur referenziert.

---

## 1. Vollständige Visibility-Mutation-Map (Main-Button-Area + Umgebung)

Quelle: `grep -rn '\.visibility\s*=' app/src/main/java/...` und `setVisibility(...)` (Java).
Filter: nur Buttons / Container, die für Main-Button-Area-Refactor relevant sind. Adapter / Settings-Activities (PromptsOverviewAdapter, HistoryAdapter, APISettingsActivity, …) sind weggelassen — die liegen außerhalb des IME.

| # | View / Button | Mutation-Site | Owner-Klasse | Trigger | Sealed-State-Bezug | Bewertung |
|---|---|---|---|---|---|---|
| 1 | `mainButtonsClTyped` (Container) | `KeyboardStateManager.kt:172-173` | KSM | `applyContentAreaVisibility()` aus jedem `applyVisibility()`-Pass | Funktion von `contentArea == MAIN_BUTTONS`. SmallMode wirkt indirekt via `setSmallMode` → forced ContentArea. | OK — SSOT |
| 2 | `editButtonsLl` (Edit-Bar) | `KeyboardStateManager.kt:174-176` | KSM | `applyContentAreaVisibility()` | `contentArea in {MAIN_BUTTONS, QWERTZ}` | OK — SSOT |
| 3 | `qwertzContainer` | `KeyboardStateManager.kt:177-178` | KSM | `applyContentAreaVisibility()` | `contentArea == QWERTZ` | OK — SSOT |
| 4 | `emojiPickerCl` | `KeyboardStateManager.kt:179-180` | KSM | `applyContentAreaVisibility()` | `contentArea == EMOJI_PICKER` | OK — SSOT |
| 5 | `pauseButton` (in input_row / single-row) | `KeyboardStateManager.kt:187` | KSM | `applyRecordingControlsVisibility()` | `RecordingState.Active ∨ Paused ∨ PipelineUiState.ReprocessStaging` | OK — SSOT, Tri-State (visible+enabled, visible+disabled, gone) |
| 6 | `trashButton` | `KeyboardStateManager.kt:191` | KSM | `applyRecordingControlsVisibility()` | gleich wie pauseButton, ohne Disabled-Variante | OK — SSOT |
| 7 | `promptsCl` | `KeyboardStateManager.kt:206` | KSM | `applyPromptsVisibility()` (komplexe Bedingung) | Multi-Axis: `isSmallMode`, `contentArea`, `isActive`, `isPipelineRunning`, `isStaging`, `isRewordingEnabled` | OK — SSOT, mit dokumentierter Bedingungskette |
| 8 | `promptsRv` | `KeyboardStateManager.kt:210-211` | KSM | `applyPromptsVisibility()` | `!isPipelineProgress` (Inverse zu pipeline-progress-LL) | OK — SSOT |
| 9 | `pipelineProgressLl` | `KeyboardStateManager.kt:212-213` | KSM | `applyPromptsVisibility()` | `isPipelineProgressVisible() && !isReprocessStaging()` | OK — SSOT |
| 10 | `promptRecordingControlsLl` | `KeyboardStateManager.kt:218` | KSM | `applyPromptsVisibility()` | `isActive && !isPipelineProgress && contentArea == QWERTZ` | OK — SSOT |
| 11 | `overlayCharactersLl` (top-level reset) | `KeyboardStateManager.kt:162` | KSM | `applyVisibility()` Default-Reset | unconditional GONE bei jedem refresh | OK — implizit "wird per Long-Press wieder geöffnet" |
| 12 | `overlayCharactersLl` | `MainButtonsController.kt:251` | MainButtonsController | enterButton.setOnLongClickListener | View-Lokal — unabhängig von State-Maschine | Akzeptiert (transientes Long-Press-Overlay; kein Lifecycle-Konflikt mit KSM weil KSM auf nächstem refresh wieder GONE setzt — siehe Zeile 162) |
| 13 | `overlayCharactersLl` (sub-children pro Slot) | `MainButtonsController.kt:485,487` | MainButtonsController | `updateOverlayCharacters()` (Theme-Apply) | Funktion von `characters.length` | Akzeptiert — pure View-internal Layout-Logik |
| 14 | `overlayCharactersLl` | `EnterOverlayHandler.kt:56,62` | EnterOverlayHandler | Touch-Up nach Long-Press-Overlay | Touch-Lokal | Akzeptiert — Touch-Handler-internal |
| 15 | `infoCl` | `InfoBarController.kt:49` (`dismiss()`) | InfoBarController | Yes/No-Click in InfoBar; `KSM.applyVisibility() → infoBarController.onStateChanged → dismiss` (KSM:163) | Indirekt: bei `isSmallMode ∨ contentArea != MAIN_BUTTONS` ruft KSM dismiss() | KSM ist Owner indirekt (KSM gibt `suppressDisplay`-Hinweis), Mutation lokal — OK |
| 16 | `infoCl` | `InfoBarController.kt:57` (`showInfo()`) | InfoBarController | `Service.showInfo("update"|"rate"|...)` aus Pipeline-Error / Onboarding-Trigger | Eigene Achse (`type: String`) — kein Sealed | Hybrid — InfoBar ist eigenes Subsystem; das passt, weil KSM nur `mainButtonsClTyped`/`editButtonsLl` etc. besitzt |
| 17 | `infoCl` | `KeyboardUiController.kt:241` (`startPipeline()`) | KeyboardUiController | `startPipeline` setzt `infoCl GONE` direkt | nicht direkter Sealed-Bezug, aber zeitlich "wir starten Pipeline → InfoBar weg" | **Verletzung schwach** — sollte `infoBarController.dismiss()` rufen, nicht direkt mutieren. Symptom: Falls InfoBar-Controller intern weitere State-Bookkeeping bekommt (z.B. einen "letzten Typ"-Cache), wird das umgangen. |
| 18 | `infoYesButton` (n×) | `InfoBarController.kt:65,78,94,111,116,131,139,145,154,163` | InfoBarController | `showInfo()`-Branch pro Type | type-spezifisch | OK — InfoBar-Local |
| 19 | `infoNoButton` | `InfoBarController.kt:58` | InfoBarController | `showInfo()` | konstant VISIBLE in showInfo-Pfad | OK — InfoBar-Local |
| 20 | `binding.iconTv` / `binding.pb` / `binding.durationTv` (Pipeline-Step-Row) | `KeyboardUiController.kt:383-388,421-426,447-448` | KeyboardUiController | `addRunningStep()` / `completeStep()` / `failStep()` | Funktion vom step-state innerhalb `PipelineUiState.Running` | OK — Pipeline-Step-Local |
| 21 | `inputRow` (action_row Sibling) | `KeyboardLayoutModeController.kt:133` | KeyboardLayoutModeController | `setSingleRowMode(enabled)` | Funktion von `Pref.SingleRowMode` | OK — Layout-Owner |
| 22 | `audioFocusButtonInRow` (Single-Row-Variante) | `KeyboardLayoutModeController.kt:138` | KeyboardLayoutModeController | `setSingleRowMode(enabled)` | Funktion von `Pref.SingleRowMode` | OK — Layout-Owner |
| 23 | **`resendButton`** | **`RecordingUiController.kt:137`** | **RecordingUiController** | `applyIdleState()` ← `onStateChanged(Idle)` ← `RecordingStateController.setState(Idle)` | `RecordingState.Idle ∧ getLastAudioFileExists()` | **🔴 Owner-Verletzung 1** — siehe §5 |
| 24 | **`resendButton`** | **`RecordingUiController.kt:158`** | **RecordingUiController** | `applyActiveState()` ← `onStateChanged(Active)` | `RecordingState.Active` | **🔴 Owner-Verletzung 1** — siehe §5 |
| 25 | **`resendButton`** | **`DictateInputMethodService.java:1345,1347`** | **Service** | `onStartInputView` (Idle-Zweig nach Re-Inflate / neuer Editor) | `isIdle && File-Exists && Pref.ResendButton` | **🔴 Owner-Verletzung 1** — vierter Mutator! |
| 26 | **`resendButton`** | **`DictateInputMethodService.java:1669`** | **Service** | `runTranscriptionViaOrchestrator()` direkt vor `uiController.startPipeline()` | "Pipeline beginnt" — kein Sealed-Bezug, prozedural | **🔴 Owner-Verletzung 1** — fünfter Mutator! |
| 27 | **`resendButton`** | **`DictateInputMethodService.java:1839`** | **Service** | `onShowResend()` Callback aus PipelineOrchestrator | Pipeline-Result-Event ("Audio behalten, Pipeline durch") | **🔴 Owner-Verletzung 1** — sechster Mutator! |

### Befund Visibility-Mutation-Map

- **Single-Owner-Tabelle (Buttons 1-22):** Saubere SSOT-Verteilung zwischen `KeyboardStateManager` (Container + zustandsabhängige Buttons), `KeyboardLayoutModeController` (Layout-Mode-Sichtbarkeit), `InfoBarController` (Self-contained Infobar), `KeyboardUiController` (Pipeline-Step-internal), `MainButtonsController`/`EnterOverlayHandler` (Touch-Overlay-internal).
- **resend_btn (#23-#27): SECHS Mutatoren in DREI verschiedenen Klassen.** Das ist die im User-Briefing genannte "race condition". Details in §5.
- **Sekundäre Schwäche:** `KeyboardUiController.kt:241` ruft `infoCl.visibility = GONE` statt `infoBarController.dismiss()`. Ist heute folgenlos (beide setzen GONE), wird aber zum Problem, sobald `dismiss()` zusätzlich State löscht.

---

## 2. Sealed-Class State-Inventar

### 2.1 `RecordingState` — `RecordingState.kt:10-18`

```kotlin
sealed class RecordingState {
    object Idle : RecordingState()
    data class Preparing(val useBluetooth: Boolean) : RecordingState()
    data class Active(val useBluetooth: Boolean) : RecordingState()
    object Paused : RecordingState()

    val isRecordingOrPaused: Boolean
        get() = this is Active || this is Paused
}
```

**Owner / Writer:** `RecordingStateController.kt:106-107` (`var state: RecordingState = Idle; private set`).
Mutation **ausschließlich** über `setState(newState)` (`RecordingStateController.kt:353-357`), das einen Callback-Hook auf `Callback.onStateChanged(old, new)` feuert.

**Reader:**
- `RecordingUiController.onStateChanged` (`RecordingUiController.kt:51-60`) — Hauptkonsument, malt Buttons + ruft `stateManager.refresh()`.
- `KeyboardStateManager` via Lambda-Query `isRecording / isPaused` (Service: `DictateInputMethodService.java:498-499`-Bereich; konkret als `() -> recordingStateController != null && recordingStateController.getState() instanceof RecordingState.Active` etc., aus dem Inventar Z. 167-170).
- `Service.onResendClicked` (`DictateInputMethodService.java:2243`): `currentState.isRecordingOrPaused()` als Guard.
- `Service.onFinishInputView` (`DictateInputMethodService.java:757-758`): Lifecycle-Verzweigung.

**Erlaubte Transitionen** (durch `startRecording` / `stopRecording` / `togglePause` / `cancelRecording` / Bluetooth-Events):

```
                        ┌────────────────────────────────────────┐
                        │                                        │
   ┌──────────┐ start() │   ┌─────────────┐  onScoConnected      │ stop()/cancel()
   │   Idle   │─────────┼──>│ Preparing   │──┐                   │
   │          │         │   │ (useBT)     │  │ proceedStart      │
   └──────────┘         │   └──────┬──────┘  │                   │
        ▲               │          │         │                   │
        │               │   onScoFailed      │                   │
        │               │ proceedStart       ▼                   │
        │ stop()/cancel │   ┌─────────────────────────────┐     │
        └───────────────┴───│   Active(useBluetooth)      │<───┐│
                            │                              │    ││
                            │   togglePause ─────┐         │    ││
                            └────────────────────┼─────────┘    ││
                                                 ▼              ││
                                          ┌──────────────┐      ││
                                          │   Paused     │──────┘│
                                          │              │ togglePause (resume)
                                          └──────┬───────┘
                                                 │
                                                 │ pauseTimeout(60s) → cancel()
                                                 ▼
                                                Idle
```

**Validation:** `startRecording` (Z. 129) blockt re-entry wenn bereits `Active|Paused|Preparing`. `togglePause` (Z. 165-179) ist nur in Active/Paused aktiv, sonst no-op (else-branch). `cancelRecording` ist hingegen unconditional — kann auch aus Idle aufgerufen werden (idempotent).

**Konvertierung zu anderen Sealed-Classes:** keine direkte. **Aber:** `RecordingState ⊥ PipelineUiState` ist NICHT garantiert — nach `stopRecording()` setzt der Service `RecordingState.Idle` UND triggert die Pipeline (`PipelineUiState.Preparing → Running`). Also: in der Übergangszeit (Use Case 5, "Recording-Stop → Send-Modus") existieren **beide** State-Maschinen parallel. Die Buttons spiegeln das wider:
- `RecordingState` regiert den Record-Button selbst (RecordingUiController).
- `PipelineUiState` regiert den Record-Button-Inhalt (KeyboardUiController.refreshRecordButtonFromState — überschreibt!).

→ **Race-Window**: Zwischen `RecordingUiController.applyIdleState()` (setzt `recordButton.text = getDictateButtonText()`) und `KeyboardUiController.preparePipeline()` (setzt `recordButton.text = R.string.dictate_sending`) zeigt der Button kurz "Diktieren" statt "Sende…". In der Praxis durch Reihenfolge im Service unterdrückt (`runTranscriptionViaOrchestrator` ruft `preparePipeline()` synchron nach `recordingStateController.stopRecording()` — beide auf Main-Thread, daher in Praxis 0-Frames-Race; eine Refactor-Umstellung könnte das brechen).

### 2.2 `PipelineUiState` — `PipelineUiState.kt:13-54`

```kotlin
sealed class PipelineUiState {
    object Idle : PipelineUiState()
    object Preparing : PipelineUiState()
    data class Running(totalSteps, completedSteps, currentStepName, autoEnterActive, hasFailure) : PipelineUiState()
    data class ReprocessStaging(targetSessionId, audioDurationSeconds, editableQueue, selectedLanguage, selectedModel?, isStarting) : PipelineUiState()
}
```

**Owner / Writer:** `KeyboardUiController.kt:63-64` (`override var state: PipelineUiState = Idle; private set`).
Mutation **ausschließlich** über `updatePipelineState(newState)` (`KeyboardUiController.kt:147-155`), Hooks: `refreshRecordButtonFromState()` + `callbacks.onPipelineUiStateChanged()` + `stateManager.refresh()`.

**Reader:**
- `KeyboardUiController.refreshRecordButtonFromState` (Z. 464-509) — Idle/Preparing/Running/ReprocessStaging-Branch.
- `KeyboardStateManager` via Lambdas `isPipelineRunning` / `isPipelineProgressVisible` / `isReprocessStaging` (Service-Wiring auf KSM-Konstruktor).
- `LanguageController` (über `PipelineUiStateReader.state`).
- `Service.onResendLongClicked` (Z. 2391: `if (uiController.isBusy()) return;`).
- `Service.onTrashClicked` (Z. 2459: `instanceof ReprocessStaging`-Branch).

**Erlaubte Transitionen:**

```
   ┌──────────────────────────────────────────────────────────────┐
   │                                                              │
   │  Idle ─── preparePipeline() ────> Preparing                  │
   │   ▲                                  │                       │
   │   │                                  │ startPipeline()       │
   │   │                                  ▼                       │
   │   │                           Running(steps, autoEnter, …)   │
   │   │                                  │                       │
   │   │ stopPipeline()                   │ updateRunningState    │
   │   │                                  │ (addRunningStep, complete, fail, toggleAutoEnter)
   │   └──────────────────────────────────┘                       │
   │                                                              │
   │  Idle ─── enterReprocessStaging ──> ReprocessStaging          │
   │   ▲                                  │                       │
   │   │ cancelReprocessStaging           │ updateReprocessQueue   │
   │   │ (Trash) → Idle                   │ updateReprocessLanguage│
   │   │                                  │ s.copy(isStarting=true)│
   │   │                                  ▼                       │
   │   │                           Preparing (via Service.onSendStaging) │
   │   │                                  │                       │
   │   │                                  ▼                       │
   │   └──────── Running ── stopPipeline ──┘                      │
   │                                                              │
   └──────────────────────────────────────────────────────────────┘
```

Validation: `cancelReprocessStaging` (Z. 326-330), `updateReprocessQueue` (Z. 335-340), `updateReprocessLanguage` (Z. 348-353), `toggleAutoEnter` (Z. 292-299) und `updateRunningState` (Z. 161-166) sind alle **typ-sicher** über `if (s is X)`-Branches. Falsch-State-Calls sind als no-op kodiert. `enterReprocessStaging` und `preparePipeline` haben keinen expliziten Guard — Service guarded mit `isBusy()`.

**Konvertierung Sealed↔Sealed:** keine. Beide State-Maschinen leben unabhängig im selben Process. Querverbindungen passieren nur über Service-Code (z.B. nach `RecordingState.stopRecording` ruft Service `KeyboardUiController.preparePipeline`).

### 2.3 Weitere State-relevante Klassen ohne Sealed

| Variable | Typ | Datei:Zeile | Owner | Anmerkung |
|---|---|---|---|---|
| `contentArea` | `enum ContentArea` | `KeyboardStateManager.kt:100` | KSM | Kein Sealed, aber semantisch identisch (geschlossene Domäne, drei Werte) |
| `isSmallMode` | `Boolean` | `KeyboardStateManager.kt:102` | KSM | Plain Pref-Spiegel |
| `audioFocusEnabled` | `Boolean` | `RecordingStateController.kt:110` | RSC | Runtime-Mirror von Pref.AudioFocus |
| `lastAppliedSingleRow` | `Boolean?` | `KeyboardLayoutModeController.kt:95` | KLMC | Performance-Guard, nicht UI-State |
| `JobState` | sealed class (ein Member: Running) | `JobState.kt:7-17` | ActiveJobRegistry | Memory-only, separate Domain (Job-Lifecycle != UI-Pipeline-Lifecycle) |

`JobState` und `JobKind` sind Job-Executor-Domäne, **nicht** UI. Sie kommen ins Spiel über `ActiveJobRegistry.isAnyActive()` (Service-Z. 2361). Eine zukünftige Erweiterung um Background-Send würde hier potenziell andere Job-States erfordern (siehe §7).

---

## 3. State-Übergangs-Diagramm (kombiniert)

### 3.1 Recording-Lifecycle-Diagramm (siehe §2.1)

### 3.2 Pipeline-UI-Lifecycle (siehe §2.2)

### 3.3 Kombiniertes UI-Hauptdiagramm (was der User sieht)

Annahme: ContentArea = MAIN_BUTTONS, SmallMode = false (Standard-Sicht).

```
         ┌────────────────────────────────────────────────────────┐
         │                                                        │
         │  IDLE-Sicht                                            │
         │  ─────────                                             │
         │  RecordingState=Idle,  PipelineUiState=Idle            │
         │  record:[Diktieren]  resend:[V iff lastAudio]          │
         │  pause:[GONE]  trash:[GONE]                            │
         │                                                        │
         └───┬───────────────────────────────────────────────────┘
             │  click record_btn → startRecording()
             ▼
         ┌─────────────────────────────────────────┐
         │  RECORDING (Active|Paused)              │
         │  ────────                               │
         │  RecordingState=Active  PipelineUiState=Idle           │
         │  record:[Senden]  resend:[GONE]                        │
         │  pause:[V/enabled]  trash:[V]  pulse=on  bordergow=on  │
         └───┬─────────────────────┬─────────────────────────────┘
             │ click pause         │ click record_btn (Senden)
             │ → togglePause       │ → stopRecording()
             ▼                     ▼
       ┌─────────────┐       ┌──────────────────────────────────┐
       │ PAUSED      │       │ SEND/PIPELINE-PIPE              │
       │ ─────       │       │ ─────                            │
       │ (mic icon)  │       │ RecordingState=Idle              │
       │             │       │ PipelineUiState=Preparing→Running│
       │             │       │ record:[Sende…]→[2/3 0:04]       │
       │             │       │ resend:[GONE]                    │
       │             │       │ pause:[GONE]  trash:[GONE]       │
       │             │       └────┬─────────────────────────────┘
       │             │            │ pipeline finished
       │             │            ▼
       │             │       ┌──────────────────────────────────┐
       │             │       │ POST-PIPELINE                    │
       │             │       │ ─────                            │
       │             │       │ RecordingState=Idle              │
       │             │       │ PipelineUiState=Idle             │
       │             │       │ record:[Diktieren]               │
       │             │       │ resend:[V iff Pref.ResendButton  │
       │             │       │   ∧ onShowResend gefeuert]       │
       │             │       └──────────────────────────────────┘
       │             │
       └─────────────┘ (loop back to RECORDING via togglePause)


  --- Resend Long-Press (Reprocess-Staging) ---

         IDLE-Sicht
            │
            │  long-press resend_btn
            │  Service.onResendLongClicked → uiController.enterReprocessStaging
            ▼
         ┌──────────────────────────────────────────────┐
         │ REPROCESS_STAGING                            │
         │ ─────                                        │
         │ RecordingState=Idle                          │
         │ PipelineUiState=ReprocessStaging(...)        │
         │ record:[Audio 0:23 · Senden]                 │
         │ resend:[?]   (siehe §5: dieser Pfad NICHT   │
         │              dokumentiert in Inventar-Matrix)│
         │ pause:[V/disabled, alpha 0.4]  trash:[V]     │
         │ Promptbar:[Editable Queue + Language-Chip]   │
         └───┬──────────────────────────────────────────┘
             │  trash → cancelReprocessStaging → IDLE
             │  record (Senden) → JobExecutor.start →
             │    preparePipeline → startPipeline (RUNNING)
             ▼
         (siehe SEND/PIPELINE-PIPE)
```

### 3.4 Orthogonale Achsen (overlay zu obigem)

```
 ContentArea (drei Werte, mutually exclusive)
   ─ MAIN_BUTTONS  → action_row + input_row sichtbar
   ─ QWERTZ        → qwertzContainer sichtbar, action_row hidden, prompt-bar mit recControlsLl
   ─ EMOJI_PICKER  → emojiPickerCl sichtbar

 SmallMode (Boolean, "Pref.SmallMode")
   ─ true  → mainButtonsClTyped GONE, ContentArea forced auf MAIN_BUTTONS
   ─ false → normales Verhalten

 SingleRowMode (Boolean, "Pref.SingleRowMode") — orthogonal zu Visibility, betrifft Layout
   ─ true  → action_row enthält ALLE 8 Buttons (incl. trash, space, pause, enter, audio_focus); input_row GONE
   ─ false → action_row + input_row beide sichtbar mit eigenen Buttons

 Pref.Animations — globaler Modifier, gated TransitionManager + start/cancel von Recording-Animation
```

---

## 4. Use-Case-Verifikation

### Verifikation der vom User gelisteten Use Cases

| UC | Beschreibung User | Im Code wahr? | Code-Pfad / Anmerkung |
|----|-------------------|---------------|------------------------|
| UC1 | Toggle Single-Row ↔ Two-Row im **Idle** | ✅ vollständig | `Service.onSingleRowModeToggled` (Z. 2639-2661) → `KeyboardLayoutModeController.setSingleRowMode(next, animate=true)` + `MainButtonsController.animateEditNumbersBounce`. Reine Pref-Mutation + Constraint-Swap, kein RecordingState/PipelineUiState involviert. |
| UC2 | Toggle während aktivem Recording | ✅ — funktioniert, mit Sub-Effekt | Pfad identisch (long-press edit_numbers_btn). Wichtig: `KeyboardLayoutModeController.refresh()` wird automatisch nach `KeyboardStateManager.applyVisibility()` getriggert (KSM:168) → bei Recording-Tick ruft `recordingUiController.onStateChanged → stateManager.refresh → applyVisibility → layoutModeController.refresh`. **Re-Parenting während aktivem Pulse:** PulseLayout-Wrapper wird mit-bewegt (Inventar-Z. 75-79). Border-Glow-Animation läuft auf record_btn-Foreground und ist re-parent-tolerant. |
| UC3 | Toggle im Send-Modus (Pipeline=Preparing/Running) | ✅ aber FRAGIL | Pfad gleich; allerdings: während `PipelineUiState.Running` setzt `KeyboardUiController.refreshRecordButtonFromState` per-Tick `recordButton.text = "${counter}  ${timer}"` (Z. 478-487). Re-Parent in Single-Row triggert evtl. Layout-Pass mid-Tick. **Kein Owner-Konflikt nachgewiesen, aber fragiles Zusammenspiel.** Siehe §6 Symptom "send-button verdeckt". |
| UC4 | Recording-Start | ✅ vollständig | Click record_btn → `MainButtonsController.recordClickListener` (Z. 76) → `Callback.onRecordClicked()` → `Service` startet `recordingStateController.startRecording`. State Idle→Preparing→Active. UI-Update via Callback-Cascade. |
| UC5 | Recording-Stop → Send-Modus | ✅ vollständig | `record_btn` (mit Text "Senden") → `Service.onRecordClicked` → `Service.stopRecording` → `recordingStateController.stopRecording` → `RecordingState=Idle` → Service.onRecordingCompleted → `runTranscriptionViaOrchestrator` → `uiController.preparePipeline()` + `uiController.startPipeline()`. |
| UC6 | Re-Inflate (Rotation, Theme, Sprache) | ✅ vollständig, aber komplex | Service `cleanupOldControllers` (nicht im Detail untersucht — bekannt aus `KeyboardStateManager.clearLayoutModeController` Z. 129-131) → onCreateInputView baut alles neu. `KeyboardLayoutModeController.init` ruft `setSingleRowMode(persisted, animate=false)` (Z. 100) → korrekter erster Frame. Pipeline-State wird via `restoreReprocessStaging` und `pipelineOrchestrator.isRunning()` Reload (Service Z. 985-1018) wiederhergestellt. |
| UC7 | SmallMode / Compact-Vorrang | ✅ vollständig | `Service.onSmallModeToggled` (Z. 2631-2636) → `stateManager.setSmallMode` → KSM forced ContentArea auf MAIN_BUTTONS (Z. 142-144) → `applyVisibility` setzt mainButtonsClTyped GONE. **SmallMode hat absoluten Vorrang über alle anderen UI-Achsen.** |

### Vom User NICHT erwähnte, aber im Code verdrahtete Use Cases

| UC | Beschreibung | Code-Pfad |
|----|--------------|-----------|
| UC-extra-1 | **Reprocess-Staging starten (long-press resend)** | `Service.onResendLongClicked` (Z. 2389-2415) — **eigener UI-Zustand**, eigener Sealed-Member `PipelineUiState.ReprocessStaging`. Setzt RecordButton-Label "Audio X:YY · Senden", aktiviert editable Prompt-Queue + Language-Chip. Nicht in Inventar §3 "Runtime States" enthalten — dort nur als Rolle in Visibility-Matrix |
| UC-extra-2 | **Reprocess-Staging Senden** | im Service unter `// SEC-7-6` (vermutlich Service Z. 2455+, nicht voll gelesen aber Z. 2529-2533 zeigt `uiController.preparePipeline + uiController.startPipeline`). Transition `ReprocessStaging → Preparing → Running` |
| UC-extra-3 | **Reprocess-Staging abbrechen** | `Service.onTrashClicked` (Z. 2457-2467) — Trash-Button als context-sensitive: in Recording = cancelRecording, in ReprocessStaging = `uiController.cancelReprocessStaging`. **Tri-State-Trash.** |
| UC-extra-4 | **Recording-Pause + Auto-Stop-Timeout** | `RecordingStateController.onKeyboardHidden` (Z. 233-247) → `togglePause + startPauseTimeout(60s)` → bei Ablauf `cancelRecording + onAutoStopTimeout` |
| UC-extra-5 | **Resend-Button Click (kurz)** | `Service.onResendClicked` (Z. 2249-2300) — async DB-Lookup, ResendStatusDispatcher.decide, drei Sub-Pfade: Insert / Resume / NoOp. Setzt Resend-Button **kurz disabled** (500ms cooldown) — eigener orthogonaler Mutator (`mainButtonsController.setResendEnabled`) |
| UC-extra-6 | **Audio-Focus Mid-Recording-Toggle** | `Service.onAudioFocusToggled` (Z. 2664-2687) → 1. Pref-Write 2. `recordingStateController.setAudioFocusRuntime` (Z. 201-212, mutiert AudioManager nur in Active) 3. Icon-Refresh. Eigene Sub-Lifecycle in RSC. |
| UC-extra-7 | **QWERTZ-Toggle / Emoji-Toggle** | `Service.toggleQwertzKeyboard` (Z. 1486-1493), `toggleEmojiPicker` (Z. 1437-1443) → `stateManager.setContentArea` |
| UC-extra-8 | **Pipeline Failure mid-run** | `KeyboardUiController.failStep` (Z. 440-456) — setzt `Running.copy(hasFailure = true)` → `refreshRecordButtonFromState` malt Button rot. Pipeline läuft weiter (failed step ist "completed" für progress) |
| UC-extra-9 | **InfoBar (Update / Rate / Donate / Error)** | `Service.showInfo` (Z. 2139-2144) → `InfoBarController.showInfo(type)`. Komplett orthogonal zu Recording/Pipeline, aber `KeyboardStateManager.applyVisibility` triggert `infoBarController.onStateChanged` (Z. 163) → suppress in SmallMode/non-MAIN_BUTTONS |
| UC-extra-10 | **Auto-Enter Toggle während Pipeline-Running** | `KeyboardUiController.toggleAutoEnter` (Z. 292-299) — in-place mutation auf `PipelineUiState.Running.autoEnterActive`. Bound to record_btn click in Service während Pipeline (nicht voll verfolgt) |

→ **Use-Case-Vollständigkeitsbefund:** Der User-Liste fehlt insbesondere **UC-extra-1/2/3 (ReprocessStaging-Lebenszyklus)**, **UC-extra-5 (Resend-Click-Cooldown)** und **UC-extra-8 (Pipeline-Failure-Anzeige)**. Diese sind beim Refactor gleichwertig zu berücksichtigen.

---

## 5. Ownership-Tabelle (UI-relevante State-Daten)

| State-Datum | Writer | Reader | Rechtmäßiger Owner | Owner-Verletzung? |
|---|---|---|---|---|
| `RecordingState` | RecordingStateController.setState (private) | RecordingUiController, KSM (Lambda), Service (3×) | **RecordingStateController** | **Keine.** Saubere Kapselung. |
| `PipelineUiState` | KeyboardUiController.updatePipelineState (private) | KSM (3 Lambdas), LanguageController, Service (3×) | **KeyboardUiController** | **Keine.** Saubere Kapselung. |
| `contentArea` | KSM.setContentArea | KSM intern, InfoBarController.onStateChanged (via KSM) | **KSM** | Keine. Setter ist private → über `setContentArea(area)` only. |
| `isSmallMode` | KSM.setSmallMode | KSM intern, Service (read-only via getter) | **KSM** | Keine. |
| `Pref.SingleRowMode` | Service.onSingleRowModeToggled | KLMC.init/refresh | **Pref + KLMC** | Keine. Pref ist Truth, KLMC liest. |
| `Pref.AudioFocus` | Service.onAudioFocusToggled, Settings UI | RecordingStateController.startRecording, MainButtonsController.refreshAudioFocusIcon | **Pref + RSC (Runtime-Spiegel)** | Keine — explizit dokumentiert in `setAudioFocusRuntime` KDoc (Z. 201-212). |
| `Pref.SmallMode` | Service.onSmallModeToggled, Service.setupKeyboard (Z. 1402) | KSM.setSmallMode (gespiegelt) | **Pref + KSM** | Keine. |
| `Pref.LastFileName` (lastAudioFileExists) | Service (nach jeder Aufnahme) | RecordingUiController.getLastAudioFileExists Lambda | **Service** | Keine. |
| `Pref.ResendButton` (Resend-Button-Setting) | Settings UI | RecordingUiController-Lambda (kombiniert mit lastAudioFileExists, Service Z. 612-613), Service direct (Z. 1344, 1693-1694) | **Pref** | Keine — Pref-Read ist idempotent. |
| **`resendButton.visibility`** | **6 Stellen in 3 Klassen** | UI | **Niemand klar** — beanspruchter SSOT KSM mutiert es **nicht**, RecordingUiController + Service tun es | **🔴 KRITISCHE Verletzung — siehe unten.** |
| `pauseButton.visibility/isEnabled/alpha` | KSM.applyRecordingControlsVisibility | UI | **KSM** | Keine. |
| `trashButton.visibility` | KSM.applyRecordingControlsVisibility | UI | **KSM** | Keine. |
| `mainButtonsController.setResendEnabled` (Resend-isEnabled) | Service.onResendClicked (cooldown) | UI | **MainButtonsController** | Keine; orthogonal zu visibility. |
| `pipelineProgressLl / promptsRv / promptsCl visibility` | KSM.applyPromptsVisibility | UI | **KSM** | Keine. |
| `infoCl visibility` | InfoBarController + KeyboardUiController.kt:241 | UI | **InfoBarController** | Schwach: KeyboardUiController bypassed mit `infoCl.visibility = GONE` direkt — sollte `infoBarController.dismiss()` rufen. Symptom-frei heute. |
| `autoEnterActive` (innerhalb `Running`) | KeyboardUiController.toggleAutoEnter | record_btn-Renderer | **KeyboardUiController** | Keine. |
| `audioFocusEnabled` (Runtime) | RecordingStateController.setAudioFocusRuntime + startRecording | RSC intern | **RecordingStateController** | Keine. |
| `recordButton.text` | RecordingUiController (applyIdleState/applyActiveState) + KeyboardUiController.refreshRecordButtonFromState + MainButtonsController.updateRecordButtonText (LanguageController-Hook) | UI | **Hybride: 2 Phasen** — RecordingUiController in Idle/Active/Paused, KeyboardUiController in Preparing/Running/ReprocessStaging | Implizit durch Reihenfolge OK; refactor-fragil (siehe §2.1 Race-Window) |
| `recordButton.isEnabled` | RecordingUiController (true bei Idle/Active, false bei Preparing) + KeyboardUiController.refreshRecordButtonFromState (false bei Preparing, true sonst) | UI | gleiche Hybrid-Aufteilung | dito |

### 🔴 Kritische Owner-Verletzung: `resendButton.visibility`

**Sechs Mutatoren in drei Klassen:**

| # | Datei:Zeile | Setzt auf | Trigger |
|---|-------------|-----------|---------|
| 1 | `RecordingUiController.kt:137` | `if (lastAudio) VISIBLE else GONE` | `RecordingState → Idle` |
| 2 | `RecordingUiController.kt:158` | `GONE` | `RecordingState → Active` |
| 3 | `DictateInputMethodService.java:1345` | `VISIBLE` | `onStartInputView` (Idle-Branch) — Re-Inflate / neuer Editor |
| 4 | `DictateInputMethodService.java:1347` | `GONE` | `onStartInputView` (Idle-Branch) — kein Audio |
| 5 | `DictateInputMethodService.java:1669` | `GONE` | `runTranscriptionViaOrchestrator` |
| 6 | `DictateInputMethodService.java:1839` | `VISIBLE` | `onShowResend()` (Pipeline-Result, "Audio behalten") |

**Implikation:**
- Es gibt **kein Single-State**, das die Visibility deterministisch berechnet.
- Stattdessen: Eventbasierte Push-Updates an verschiedenen Lebenszyklus-Punkten — wer zuletzt schreibt, gewinnt.
- KSM (eigentlicher SSOT) berührt resendButton **gar nicht** — fehlt im Inventar in Z. 240 als "GONE in Recording" laut Visibility-Matrix, aber dort steht keine Quelle, weil tatsächlich RecordingUiController#158 das setzt.

**Korrekte SSOT-Form** (Vorschlag, kein Code-Patch):
- KSM erhält Lambda `() -> Boolean isResendEligible` (= `lastAudioFileExists() && Pref.ResendButton && !isRecording() && !isPaused() && !isPipelineRunning() && !isReprocessStaging()`).
- Sechs Mutationsstellen verschwinden zugunsten **eines** `applyResendVisibility()`-Aufrufs in `KSM.applyVisibility`.
- Der `onShowResend()`-Callback aus dem Pipeline-Result wird ein State-Update auf einer neuen Boolean (z.B. `lastAudioFileExists` Cache) — KSM.refresh wird gerufen.

### Sekundäre Schwäche: `recordButton.text/isEnabled` Hybrid

Heute funktioniert das, weil:
- RecordingUiController malt (Idle, Preparing, Active, Paused).
- KeyboardUiController.refreshRecordButtonFromState malt (Idle, Preparing, Running, ReprocessStaging).
- Beide werden per `onStateChanged` getriggert; im typischen Flow überschreibt KeyboardUiController nach RecordingUiController (weil Pipeline NACH Recording-Stop kommt).
- KSM ruft beide implizit nicht direkt; RecordingUiController.onStateChanged ruft `stateManager.refresh()`, was wiederum **nicht** den recordButton-Renderer aufruft → die einzige Refresh-Quelle für KeyboardUiController-Recordbutton ist `updatePipelineState` → `refreshRecordButtonFromState`.

Race-fragil: Wenn ein Refactor (z.B. KSM ruft KeyboardUiController.refreshRecordButtonFromState bei jedem refresh) hinzugefügt würde, könnte ein RecordingState=Active + PipelineUiState=Idle die UI "Diktieren / Senden" flackern lassen.

---

## 6. Bug-Klassen-Mapping

| Symptom | Owner-Verletzung / SSOT-Lücke | Rückführung |
|---------|-------------------------------|-------------|
| **Send-Button verdeckt im Send-Modus + Single-Row** | `recordButton.text/isEnabled`-Hybrid + Re-Parent während aktiver Pipeline-Tick (`KeyboardUiController.refreshRecordButtonFromState` per-Tick + `KeyboardLayoutModeController.refresh` per-Tick) | KSM.applyVisibility → KLMC.refresh **bei jedem Tick**. Wenn der Pipeline-Tick auf den Re-Parent fällt, kann der record_btn temporär falsch positioniert sein. **Sekundär:** Single-Row-Chain hat `space_btn = MATCH_CONSTRAINT`, alle anderen `wrap_content` — wenn `record_btn`-Text während Pipeline-Tick lang wird (`"Sende… 0:01"` → `"Transkription  2/3  0:08"`), verschiebt sich die Chain → optisch "verdeckt". |
| **Resend-Button verschwindet beim Toggle** | `resendButton.visibility` mit 6 Mutatoren (§5) + KSM.refresh-Cascade triggert KLMC.refresh, das **nicht** resendButton-Visibility neu berechnet | Beim Single-Row-Toggle ruft `Service.onSingleRowModeToggled → KLMC.setSingleRowMode(true)` → re-parent + Constraint-Set-Apply. Der re-parente resend_btn behält seinen alten `visibility`-Wert (z.B. VISIBLE im Idle), aber: wenn KSM.refresh nicht parallel getriggert wird (Toggle ist **kein** `applyVisibility`-Trigger! — siehe Service Z. 2655-2657, ruft nur `setSingleRowMode`), bleibt resend in dem Zustand, in dem er vor dem Toggle war. **Kritisch wenn:** zwischen letztem Recording-Stop (resend → VISIBLE via `applyIdleState`) und Single-Row-Toggle ein neuer Recording-Cycle gestartet wurde (resend → GONE via `applyActiveState`) und nach Stop+Toggle die UI wieder zurück sollte → resend bleibt GONE bis nächster RecordingState-Wechsel. |
| **Asymmetrisches Reparenting (gefixt 2026-05-07)** | Single-Target-`rehome` in KLMC vs. heterogene Origin-Parents | Bug-Fix dokumentiert in `KeyboardLayoutModeController.kt:60-74` (`originalParents` map). Ursache: `if (toSingleRow) actionRow else inputRow` blind, ohne Berücksichtigung dass `record_pulse_layout`/`resend_btn`/`backspace_btn` nativ in `action_row` leben. **Kategorie:** klassische "naive bidirectional move" — die fehlende Symmetrie war ein Owner-Verlust (originale Parent-Information wurde nicht vorgehalten). Heute via Map gelöst, aber Map ist fragil bei neuen Buttons (siehe Inventar §6 Risiko-Liste #2). |

### Implizite Bug-Klasse: `infoCl` Direct-Mutation in KeyboardUiController

`KeyboardUiController.kt:241` setzt `views.infoCl.visibility = View.GONE` direkt statt `infoBarController.dismiss()`. Heute folgenlos. Aber wenn InfoBarController später interne Bookkeeping-State pflegt (z.B. ein "letzter angezeigter Type"-Cache zur Wiederholung), umgeht KeyboardUiController diesen — was zu "InfoBar verschwindet, kommt aber bei nächstem Trigger nicht wieder" Symptomen führen kann.

### Implizite Bug-Klasse: KSM.refresh wird NICHT aus jedem state-relevanten Trigger gefeuert

**Trigger, die `KeyboardStateManager.refresh()` rufen:**
- `RecordingUiController.onStateChanged` → ja
- `KeyboardUiController.updatePipelineState` (wenn old != new) → ja
- `Service.runTranscriptionViaOrchestrator` (Z. 1672) → ja, explizit
- `Service.onFinishInputView` (Z. 779, idle-cleanup) → ja

**Trigger, die `applyVisibility()` indirekt umgehen:**
- `Service.onSingleRowModeToggled` → ruft NUR `KLMC.setSingleRowMode`, NICHT `stateManager.refresh()`. Wenn Single-Row-Toggle in einer Phase passiert, in der zwischen Pref und View-State eine Inkonsistenz besteht (z.B. resendButton oder pauseButton sichtbar/unsichtbar je nach KSM-Lambda), bleibt diese Inkonsistenz bestehen.
- `Service.onAudioFocusToggled` → triggert nur `mainButtonsController.refreshAudioFocusIcon`, keine refresh().
- `Service.onShowResend` → setzt `resendButton.setVisibility(VISIBLE)` direkt, refresh() wird NICHT gerufen.

→ **SSOT-Lücke:** Der Cascade `applyVisibility → applyContentAreaVisibility + applyRecordingControlsVisibility + applyPromptsVisibility + KLMC.refresh` ist eingerichtet, aber Single-Row-Toggle und Resend-Visibility bypassen ihn.

---

## 7. Geplanter neuer Use Case: A+B-Background-Send-Persistence

### Vorgesehene Erweiterung der Sealed Classes

Laut User-Briefing wird ein neuer Sealed-Member eingeführt:

```kotlin
// Vermutlich (noch nicht im Code):
sealed class PipelineUiState {
    // existing: Idle, Preparing, Running, ReprocessStaging
    data class Sending.Backgrounded(...) : PipelineUiState()
    data class Sending.BackgroundedReady(...) : PipelineUiState()
}
```

Semantik nach User: Pipeline-Send läuft im Hintergrund weiter, während der User die Tastatur in einen anderen Kontext bringt (App-Switch, IME-Hide). "BackgroundedReady" = Output liegt vor, wartet auf User-Aufmerksamkeit.

### Anpassungs-Bedarf in der State-Maschine

| Stelle | Datei:Zeile | Anpassung |
|--------|-------------|-----------|
| **Sealed-Class** | `PipelineUiState.kt:13-54` | Neue Member hinzufügen. Nested Sealed (`Sending.Backgrounded` / `Sending.BackgroundedReady`) sauber, wenn `sealed class Sending : PipelineUiState()` zwischengeschoben wird. Compiler erzwingt dann Branch-Coverage. |
| **`refreshRecordButtonFromState`** | `KeyboardUiController.kt:464-509` | Zwei neue Branches. **Risiko:** der Pipeline-Total-Timer (Z. 257-264) lebt weiter während Backgrounded → Button zeigt Live-Timer auch im "minimierten" Modus? Oder soll Backgrounded den Timer einfrieren? Designentscheidung. |
| **`KeyboardStateManager` Lambdas** | KSM-Konstruktor + Service-Wiring | Neue Lambdas: `isBackgroundedSending`, `isBackgroundedReady`. Eventuell: `isPipelineProgressVisible` muss auch Backgrounded ausschließen (nicht "Pipeline läuft visuell" sondern "läuft headless"). |
| **`applyPromptsVisibility`** | `KeyboardStateManager.kt:194-224` | `showPrompts`-Berechnung muss Backgrounded berücksichtigen — vermutlich SOLL Promptbar im Background-Modus **NICHT** als Pipeline-Progress angezeigt werden, sondern entweder normale Prompts oder ein eigenes "BackgroundedReady"-Indikator. Branchaufwand. |
| **`applyRecordingControlsVisibility`** | `KeyboardStateManager.kt:183-192` | Pause / Trash heute nur `isActive ∨ isStaging`. Im Backgrounded müssen sie höchstwahrscheinlich GONE sein (Recording ist längst fertig); BackgroundedReady ist semantisch "Ergebnis abholen" → Trash könnte als "Verwerfen" fungieren. Designfrage. |
| **`onStartInputView`** | Service-Z. ~1280-1410 (Re-Inflate-Pfad) | Heute restored Service `restoreReprocessStaging` und `pipelineOrchestrator.isRunning()` (Z. 985-1018). Backgrounded muss persistent sein über Re-Inflate hinweg → entweder analog `restore…`-Variable oder DB-getrieben (Session-Status). **Großer Aufwand.** |
| **`Service.onFinishInputView`** | Z. 750-783 | Aktuell: 3 Branches (Recording-Active, Pipeline-Running, Idle). Pipeline-Running-Branch (Z. 765-768) führt heute KEIN Cleanup (lässt Pipeline weiterlaufen). Backgrounded ist **genau** dieser Pfad — die UI-Seite muss entscheiden: setzt das Service `state = Backgrounded` aktiv um, oder bleibt der State `Running` und der "Backgrounded"-Aspekt liegt nur in der Lifecycle-Komponente? |
| **`onPipelineFinished`** | Service-Z. 1869+ | Heute: `uiController.stopPipeline()` → `Idle`. Wenn Pipeline im Backgrounded fertig wird, muss stattdessen Transition `Backgrounded → BackgroundedReady` passieren. Side-Effect: Output-Commit darf nicht erfolgen (kein InputConnection im Backgrounded). |
| **`onShowResend`** | Service-Z. 1836-1841 | Wird heute am Pipeline-Ende gefeuert. Im Backgrounded-Pfad (User ist nicht da) → Trigger muss verzögert werden bis BackgroundedReady → User-Aktion. |
| **`KSM.refresh`-Quellen** | sechs Stellen | Backgrounded-State-Wechsel muss alle Visibility-relevanten Updates triggern. Da `updatePipelineState` bereits `stateManager.refresh()` ruft, sollte das automatisch greifen — vorausgesetzt der State-Wechsel geht durch `updatePipelineState`. |

### Fragile Stellen, die Background-Send brechen können

1. **resend_btn 6-Mutator-Chaos (§5):** `onShowResend` aus Pipeline-Result triggert direkt `resendButton.setVisibility(VISIBLE)`. Im Backgrounded soll das vielleicht NICHT passieren (kein UI da), oder es soll passieren aber visuell nur sichtbar werden, sobald `BackgroundedReady` → `Idle` (User kommt zurück). **Refactor zu KSM-SSOT MUSS vor Background-Send geschehen**, sonst entstehen neue Bugs in dem Bereich.

2. **`recordButton.text/isEnabled` Hybrid (§5 Sekundär):** Im Backgrounded ist die UI evtl. nicht sichtbar (IME hidden), aber der Tick läuft weiter (Pipeline-Total-Timer). KeyboardUiController.refreshRecordButtonFromState aktualisiert pro-Tick → unnötige View-Updates auf einer GONE-View. **Performance-Risiko, kein Korrektheitsbug**, aber: wenn der RecordingUiController parallel (z.B. ein neuer Recording-Cycle wird im Backgrounded gestartet — ist das überhaupt erlaubt?) den Button überschreibt, entsteht Konflikt.

3. **Single-Job-Lock (`ActiveJobRegistry.isAnyActive()`):** Wenn der Background-Job läuft, blockiert der `JobExecutor` neue Jobs (siehe `Service.startResumeJob` Z. 2361). Frage: Darf der User im Backgrounded-Zustand ein neues Recording starten? Heute wahrscheinlich blockiert via `uiController.isBusy()` (Z. 188) — Backgrounded-States müssen `isBusy() = true` zurückgeben, sonst fängt ein zweiter Send-Pfad an parallel zu laufen. Test: `KeyboardUiController.isBusy() = state !is Idle` ist GENERISCH — neuer Sealed-Member ist automatisch "busy". ✅ kein Anpassungsbedarf.

4. **`onResendLongClicked` (UC-extra-1):** Guard ist `if (uiController.isBusy()) return;` — analog 3 schon abgedeckt.

5. **`KeyboardLayoutModeController.refresh()`-Cascade (KSM:168):** Bei jedem `applyVisibility` wird KLMC neu angewandt. Im Backgrounded macht das nichts (wenn UI hidden, kein Layout sichtbar), aber **wenn die View nicht GONE sondern nur unsichtbar ist** (z.B. main_buttons_cl ist VISIBLE, aber Service.onFinishInputView räumt nicht auf), läuft KLMC.refresh per-Tick weiter. Idempotent (Z. 122 lastAppliedSingleRow-Guard) → Performance OK, **kein Korrektheitsbug**.

6. **Promptbar als Pipeline-Progress (KSM Z. 195-213):** `isPipelineProgress = isPipelineProgressVisible() && !isReprocessStaging()`. Soll Backgrounded auch `isPipelineProgressVisible()` true setzen? Wenn ja, zeigt die Promptbar Pipeline-Steps **während IME hidden ist** (wenn IME wieder shown wird). Wenn nein, muss `isPipelineProgressVisible` beide Backgrounded-States ausschließen.

7. **Recording vs Pipeline-Race im Backgrounded:** Heute gilt invariant `RecordingState=Idle ⇔ Pipeline kann starten` (Service-Code-Reihenfolge). Im Backgrounded läuft Pipeline weiter, RecordingState ist Idle. Wenn der User in dieser Phase ein **neues** Recording startet, was passiert? Im Code blockt `JobExecutor` aktuell — aber UI-seitig würde RecordingUiController.applyActiveState in einer Phase laufen, wo KeyboardUiController.state=Backgrounded ist → KeyboardUiController.refreshRecordButtonFromState überschreibt RecordingUiController.applyActiveState's Button-Mutationen. **Korrektheits-fragil.**

### Empfehlung vor Background-Send-Implementierung

1. **Erst** `resendButton.visibility` zu KSM-SSOT konsolidieren (§5).
2. **Dann** `recordButton.text/isEnabled` Hybrid auflösen (eindeutiger Owner — entweder KeyboardUiController allein, mit RecordingState-Hooks, oder ein neuer kombinierter Renderer der beide State-Maschinen liest).
3. **Erst dann** Backgrounded-Sealed-Member einführen — die Branch-Erweiterung ist mechanisch, aber nur stabil wenn die zugrundeliegende Owner-Verteilung sauber ist.

---

## Anhang A — Datei-Pointer (alle absolute Pfade)

- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/RecordingState.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/RecordingStateController.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/PipelineUiState.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/PipelineUiStateReader.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/KeyboardStateManager.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/KeyboardLayoutModeController.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/MainButtonsController.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/InfoBarController.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/ContentArea.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/JobState.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt`
- `/home/lukas/WebStorm/Dictate/app/src/main/java/net/devemperor/dictate/keyboard/EnterOverlayHandler.kt`
- `/home/lukas/WebStorm/Dictate/docs/plans/2026-05-07 - keyboard-layout-refactor/research/main-button-area-inventory.md` (Inventar — ergänzt durch dieses Dokument)
