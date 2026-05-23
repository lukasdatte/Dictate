# State Machine + Visibility-Ownership Deep Analysis

**Date:** 2026-05-07
**Branch:** feature/language-chip-curation
**Trigger:** Refactor preparation — the hybrid visibility mutation on `resend_btn` between `KeyboardStateManager` (the SSOT claimant) and `RecordingUiController` (mutates directly) leads to race conditions. The same question is open for other buttons.
**Related docs:**
- [`main-button-area-inventory.md`](../main-button-area-inventory.md) — inventory (buttons / modes / visibility matrix)
- [`motionlayout-architecture-options.md`](../motionlayout-architecture-options.md)
- [`keyboard-layout-refactor.md`](../../keyboard-layout-refactor.md)

> This file deepens the inventory along the state machine, owner violations, and the state extension pending for A+B background-send persistence. Inventory content (listener tables, XML layout, animation library) is deliberately NOT duplicated — only referenced.

---

## 1. Complete Visibility-Mutation Map (Main-Button-Area + Surroundings)

Source: `grep -rn '\.visibility\s*=' app/src/main/java/...` and `setVisibility(...)` (Java).
Filter: only buttons / containers relevant to the main-button-area refactor. Adapters / settings activities (PromptsOverviewAdapter, HistoryAdapter, APISettingsActivity, …) are omitted — they are outside the IME.

| # | View / Button | Mutation site | Owner class | Trigger | Sealed-state relation | Assessment |
|---|---|---|---|---|---|---|
| 1 | `mainButtonsClTyped` (container) | `KeyboardStateManager.kt:172-173` | KSM | `applyContentAreaVisibility()` from every `applyVisibility()` pass | Function of `contentArea == MAIN_BUTTONS`. SmallMode acts indirectly via `setSmallMode` → forced ContentArea. | OK — SSOT |
| 2 | `editButtonsLl` (edit bar) | `KeyboardStateManager.kt:174-176` | KSM | `applyContentAreaVisibility()` | `contentArea in {MAIN_BUTTONS, QWERTZ}` | OK — SSOT |
| 3 | `qwertzContainer` | `KeyboardStateManager.kt:177-178` | KSM | `applyContentAreaVisibility()` | `contentArea == QWERTZ` | OK — SSOT |
| 4 | `emojiPickerCl` | `KeyboardStateManager.kt:179-180` | KSM | `applyContentAreaVisibility()` | `contentArea == EMOJI_PICKER` | OK — SSOT |
| 5 | `pauseButton` (in input_row / single-row) | `KeyboardStateManager.kt:187` | KSM | `applyRecordingControlsVisibility()` | `RecordingState.Active ∨ Paused ∨ PipelineUiState.ReprocessStaging` | OK — SSOT, tri-state (visible+enabled, visible+disabled, gone) |
| 6 | `trashButton` | `KeyboardStateManager.kt:191` | KSM | `applyRecordingControlsVisibility()` | same as pauseButton, without the disabled variant | OK — SSOT |
| 7 | `promptsCl` | `KeyboardStateManager.kt:206` | KSM | `applyPromptsVisibility()` (complex condition) | Multi-axis: `isSmallMode`, `contentArea`, `isActive`, `isPipelineRunning`, `isStaging`, `isRewordingEnabled` | OK — SSOT, with a documented condition chain |
| 8 | `promptsRv` | `KeyboardStateManager.kt:210-211` | KSM | `applyPromptsVisibility()` | `!isPipelineProgress` (inverse to the pipeline-progress LL) | OK — SSOT |
| 9 | `pipelineProgressLl` | `KeyboardStateManager.kt:212-213` | KSM | `applyPromptsVisibility()` | `isPipelineProgressVisible() && !isReprocessStaging()` | OK — SSOT |
| 10 | `promptRecordingControlsLl` | `KeyboardStateManager.kt:218` | KSM | `applyPromptsVisibility()` | `isActive && !isPipelineProgress && contentArea == QWERTZ` | OK — SSOT |
| 11 | `overlayCharactersLl` (top-level reset) | `KeyboardStateManager.kt:162` | KSM | `applyVisibility()` default reset | unconditional GONE on every refresh | OK — implicitly "is reopened via long-press" |
| 12 | `overlayCharactersLl` | `MainButtonsController.kt:251` | MainButtonsController | enterButton.setOnLongClickListener | View-local — independent of the state machine | Accepted (transient long-press overlay; no lifecycle conflict with KSM because KSM sets it GONE again on the next refresh — see line 162) |
| 13 | `overlayCharactersLl` (sub-children per slot) | `MainButtonsController.kt:485,487` | MainButtonsController | `updateOverlayCharacters()` (theme apply) | Function of `characters.length` | Accepted — pure view-internal layout logic |
| 14 | `overlayCharactersLl` | `EnterOverlayHandler.kt:56,62` | EnterOverlayHandler | touch-up after the long-press overlay | Touch-local | Accepted — touch-handler-internal |
| 15 | `infoCl` | `InfoBarController.kt:49` (`dismiss()`) | InfoBarController | Yes/No click in the InfoBar; `KSM.applyVisibility() → infoBarController.onStateChanged → dismiss` (KSM:163) | Indirectly: on `isSmallMode ∨ contentArea != MAIN_BUTTONS` KSM calls dismiss() | KSM is the owner indirectly (KSM gives the `suppressDisplay` hint), the mutation is local — OK |
| 16 | `infoCl` | `InfoBarController.kt:57` (`showInfo()`) | InfoBarController | `Service.showInfo("update"|"rate"|...)` from a pipeline error / onboarding trigger | Own axis (`type: String`) — no sealed | Hybrid — the InfoBar is its own subsystem; that fits because KSM only owns `mainButtonsClTyped`/`editButtonsLl` etc. |
| 17 | `infoCl` | `KeyboardUiController.kt:241` (`startPipeline()`) | KeyboardUiController | `startPipeline` sets `infoCl GONE` directly | not a direct sealed relation, but temporally "we start the pipeline → InfoBar gone" | **Weak violation** — should call `infoBarController.dismiss()`, not mutate directly. Symptom: if the InfoBar controller internally gets further state bookkeeping (e.g. a "last type" cache), that is bypassed. |
| 18 | `infoYesButton` (n×) | `InfoBarController.kt:65,78,94,111,116,131,139,145,154,163` | InfoBarController | `showInfo()` branch per type | type-specific | OK — InfoBar-local |
| 19 | `infoNoButton` | `InfoBarController.kt:58` | InfoBarController | `showInfo()` | constant VISIBLE in the showInfo path | OK — InfoBar-local |
| 20 | `binding.iconTv` / `binding.pb` / `binding.durationTv` (pipeline step row) | `KeyboardUiController.kt:383-388,421-426,447-448` | KeyboardUiController | `addRunningStep()` / `completeStep()` / `failStep()` | Function of the step state within `PipelineUiState.Running` | OK — pipeline-step-local |
| 21 | `inputRow` (action_row sibling) | `KeyboardLayoutModeController.kt:133` | KeyboardLayoutModeController | `setSingleRowMode(enabled)` | Function of `Pref.SingleRowMode` | OK — layout owner |
| 22 | `audioFocusButtonInRow` (single-row variant) | `KeyboardLayoutModeController.kt:138` | KeyboardLayoutModeController | `setSingleRowMode(enabled)` | Function of `Pref.SingleRowMode` | OK — layout owner |
| 23 | **`resendButton`** | **`RecordingUiController.kt:137`** | **RecordingUiController** | `applyIdleState()` ← `onStateChanged(Idle)` ← `RecordingStateController.setState(Idle)` | `RecordingState.Idle ∧ getLastAudioFileExists()` | **🔴 Owner violation 1** — see §5 |
| 24 | **`resendButton`** | **`RecordingUiController.kt:158`** | **RecordingUiController** | `applyActiveState()` ← `onStateChanged(Active)` | `RecordingState.Active` | **🔴 Owner violation 1** — see §5 |
| 25 | **`resendButton`** | **`DictateInputMethodService.java:1345,1347`** | **Service** | `onStartInputView` (idle branch after re-inflate / new editor) | `isIdle && File-Exists && Pref.ResendButton` | **🔴 Owner violation 1** — fourth mutator! |
| 26 | **`resendButton`** | **`DictateInputMethodService.java:1669`** | **Service** | `runTranscriptionViaOrchestrator()` right before `uiController.startPipeline()` | "pipeline begins" — no sealed relation, procedural | **🔴 Owner violation 1** — fifth mutator! |
| 27 | **`resendButton`** | **`DictateInputMethodService.java:1839`** | **Service** | `onShowResend()` callback from PipelineOrchestrator | Pipeline-result event ("keep audio, pipeline through") | **🔴 Owner violation 1** — sixth mutator! |

### Finding: visibility-mutation map

- **Single-owner table (buttons 1-22):** A clean SSOT distribution between `KeyboardStateManager` (container + state-dependent buttons), `KeyboardLayoutModeController` (layout-mode visibility), `InfoBarController` (self-contained infobar), `KeyboardUiController` (pipeline-step-internal), `MainButtonsController`/`EnterOverlayHandler` (touch-overlay-internal).
- **resend_btn (#23-#27): SIX mutators in THREE different classes.** That is the "race condition" mentioned in the user briefing. Details in §5.
- **Secondary weakness:** `KeyboardUiController.kt:241` calls `infoCl.visibility = GONE` instead of `infoBarController.dismiss()`. Harmless today (both set GONE), but becomes a problem as soon as `dismiss()` additionally clears state.

---

## 2. Sealed-Class State Inventory

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

**Owner / writer:** `RecordingStateController.kt:106-107` (`var state: RecordingState = Idle; private set`).
Mutation **exclusively** via `setState(newState)` (`RecordingStateController.kt:353-357`), which fires a callback hook to `Callback.onStateChanged(old, new)`.

**Reader:**
- `RecordingUiController.onStateChanged` (`RecordingUiController.kt:51-60`) — the main consumer, draws buttons + calls `stateManager.refresh()`.
- `KeyboardStateManager` via the lambda query `isRecording / isPaused` (Service: `DictateInputMethodService.java:498-499` area; concretely as `() -> recordingStateController != null && recordingStateController.getState() instanceof RecordingState.Active` etc., from the inventory L. 167-170).
- `Service.onResendClicked` (`DictateInputMethodService.java:2243`): `currentState.isRecordingOrPaused()` as a guard.
- `Service.onFinishInputView` (`DictateInputMethodService.java:757-758`): lifecycle branching.

**Allowed transitions** (via `startRecording` / `stopRecording` / `togglePause` / `cancelRecording` / Bluetooth events):

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

**Validation:** `startRecording` (L. 129) blocks re-entry if already `Active|Paused|Preparing`. `togglePause` (L. 165-179) is only active in Active/Paused, otherwise a no-op (else branch). `cancelRecording` is by contrast unconditional — it can also be called from Idle (idempotent).

**Conversion to other sealed classes:** none direct. **But:** `RecordingState ⊥ PipelineUiState` is NOT guaranteed — after `stopRecording()` the service sets `RecordingState.Idle` AND triggers the pipeline (`PipelineUiState.Preparing → Running`). So: in the transition window (use case 5, "recording-stop → send mode") **both** state machines exist in parallel. The buttons reflect that:
- `RecordingState` governs the record button itself (RecordingUiController).
- `PipelineUiState` governs the record-button content (KeyboardUiController.refreshRecordButtonFromState — overrides!).

→ **Race window**: Between `RecordingUiController.applyIdleState()` (sets `recordButton.text = getDictateButtonText()`) and `KeyboardUiController.preparePipeline()` (sets `recordButton.text = R.string.dictate_sending`) the button briefly shows "Dictate" instead of "Send…". In practice suppressed by the order in the service (`runTranscriptionViaOrchestrator` calls `preparePipeline()` synchronously after `recordingStateController.stopRecording()` — both on the main thread, so in practice a 0-frame race; a refactor reordering could break that).

### 2.2 `PipelineUiState` — `PipelineUiState.kt:13-54`

```kotlin
sealed class PipelineUiState {
    object Idle : PipelineUiState()
    object Preparing : PipelineUiState()
    data class Running(totalSteps, completedSteps, currentStepName, autoEnterActive, hasFailure) : PipelineUiState()
    data class ReprocessStaging(targetSessionId, audioDurationSeconds, editableQueue, selectedLanguage, selectedModel?, isStarting) : PipelineUiState()
}
```

**Owner / writer:** `KeyboardUiController.kt:63-64` (`override var state: PipelineUiState = Idle; private set`).
Mutation **exclusively** via `updatePipelineState(newState)` (`KeyboardUiController.kt:147-155`), hooks: `refreshRecordButtonFromState()` + `callbacks.onPipelineUiStateChanged()` + `stateManager.refresh()`.

**Reader:**
- `KeyboardUiController.refreshRecordButtonFromState` (L. 464-509) — Idle/Preparing/Running/ReprocessStaging branch.
- `KeyboardStateManager` via the lambdas `isPipelineRunning` / `isPipelineProgressVisible` / `isReprocessStaging` (service wiring to the KSM constructor).
- `LanguageController` (via `PipelineUiStateReader.state`).
- `Service.onResendLongClicked` (L. 2391: `if (uiController.isBusy()) return;`).
- `Service.onTrashClicked` (L. 2459: `instanceof ReprocessStaging` branch).

**Allowed transitions:**

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

Validation: `cancelReprocessStaging` (L. 326-330), `updateReprocessQueue` (L. 335-340), `updateReprocessLanguage` (L. 348-353), `toggleAutoEnter` (L. 292-299) and `updateRunningState` (L. 161-166) are all **type-safe** via `if (s is X)` branches. Wrong-state calls are coded as a no-op. `enterReprocessStaging` and `preparePipeline` have no explicit guard — the service guards with `isBusy()`.

**Conversion sealed↔sealed:** none. Both state machines live independently in the same process. Cross-connections happen only via service code (e.g. after `RecordingState.stopRecording` the service calls `KeyboardUiController.preparePipeline`).

### 2.3 Other state-relevant classes without a sealed class

| Variable | Type | File:Line | Owner | Note |
|---|---|---|---|---|
| `contentArea` | `enum ContentArea` | `KeyboardStateManager.kt:100` | KSM | No sealed, but semantically identical (a closed domain, three values) |
| `isSmallMode` | `Boolean` | `KeyboardStateManager.kt:102` | KSM | A plain pref mirror |
| `audioFocusEnabled` | `Boolean` | `RecordingStateController.kt:110` | RSC | A runtime mirror of Pref.AudioFocus |
| `lastAppliedSingleRow` | `Boolean?` | `KeyboardLayoutModeController.kt:95` | KLMC | A performance guard, not UI state |
| `JobState` | sealed class (one member: Running) | `JobState.kt:7-17` | ActiveJobRegistry | Memory-only, a separate domain (the job lifecycle != the UI-pipeline lifecycle) |

`JobState` and `JobKind` are JobExecutor-domain, **not** UI. They come into play via `ActiveJobRegistry.isAnyActive()` (Service L. 2361). A future extension by background-send would potentially require other job states here (see §7).

---

## 3. State-Transition Diagram (Combined)

### 3.1 Recording-lifecycle diagram (see §2.1)

### 3.2 Pipeline-UI lifecycle (see §2.2)

### 3.3 Combined main UI diagram (what the user sees)

Assumption: ContentArea = MAIN_BUTTONS, SmallMode = false (the standard view).

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

### 3.4 Orthogonal axes (overlay on the above)

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

## 4. Use-Case Verification

### Verification of the use cases listed by the user

| UC | User description | True in the code? | Code path / note |
|----|-------------------|---------------|------------------------|
| UC1 | Toggle Single-Row ↔ Two-Row in **Idle** | ✅ complete | `Service.onSingleRowModeToggled` (L. 2639-2661) → `KeyboardLayoutModeController.setSingleRowMode(next, animate=true)` + `MainButtonsController.animateEditNumbersBounce`. Pure pref mutation + constraint swap, no RecordingState/PipelineUiState involved. |
| UC2 | Toggle during active recording | ✅ — works, with a sub-effect | The path is identical (long-press edit_numbers_btn). Important: `KeyboardLayoutModeController.refresh()` is triggered automatically after `KeyboardStateManager.applyVisibility()` (KSM:168) → on a recording tick `recordingUiController.onStateChanged → stateManager.refresh → applyVisibility → layoutModeController.refresh`. **Re-parenting during an active pulse:** the PulseLayout wrapper is moved along (inventory L. 75-79). The border-glow animation runs on the record_btn foreground and is re-parent-tolerant. |
| UC3 | Toggle in send mode (pipeline=Preparing/Running) | ✅ but FRAGILE | The path is the same; however: during `PipelineUiState.Running` `KeyboardUiController.refreshRecordButtonFromState` sets `recordButton.text = "${counter}  ${timer}"` per tick (L. 478-487). A re-parent in Single-Row may trigger a layout pass mid-tick. **No owner conflict proven, but a fragile interplay.** See §6 symptom "send button hidden". |
| UC4 | Recording start | ✅ complete | Click record_btn → `MainButtonsController.recordClickListener` (L. 76) → `Callback.onRecordClicked()` → `Service` starts `recordingStateController.startRecording`. State Idle→Preparing→Active. UI update via the callback cascade. |
| UC5 | Recording-stop → send mode | ✅ complete | `record_btn` (with text "Send") → `Service.onRecordClicked` → `Service.stopRecording` → `recordingStateController.stopRecording` → `RecordingState=Idle` → Service.onRecordingCompleted → `runTranscriptionViaOrchestrator` → `uiController.preparePipeline()` + `uiController.startPipeline()`. |
| UC6 | Re-inflate (rotation, theme, language) | ✅ complete, but complex | Service `cleanupOldControllers` (not investigated in detail — known from `KeyboardStateManager.clearLayoutModeController` L. 129-131) → onCreateInputView rebuilds everything. `KeyboardLayoutModeController.init` calls `setSingleRowMode(persisted, animate=false)` (L. 100) → a correct first frame. The pipeline state is restored via `restoreReprocessStaging` and a `pipelineOrchestrator.isRunning()` reload (Service L. 985-1018). |
| UC7 | SmallMode / compact precedence | ✅ complete | `Service.onSmallModeToggled` (L. 2631-2636) → `stateManager.setSmallMode` → KSM forces ContentArea to MAIN_BUTTONS (L. 142-144) → `applyVisibility` sets mainButtonsClTyped GONE. **SmallMode has absolute precedence over all other UI axes.** |

### Use cases NOT mentioned by the user but wired in the code

| UC | Description | Code path |
|----|--------------|-----------|
| UC-extra-1 | **Start reprocess-staging (long-press resend)** | `Service.onResendLongClicked` (L. 2389-2415) — **its own UI state**, its own sealed member `PipelineUiState.ReprocessStaging`. Sets the record-button label "Audio X:YY · Send", activates the editable prompt queue + language chip. Not contained in inventory §3 "Runtime States" — there only as a role in the visibility matrix |
| UC-extra-2 | **Reprocess-staging send** | in the service under `// SEC-7-6` (presumably Service L. 2455+, not fully read but L. 2529-2533 shows `uiController.preparePipeline + uiController.startPipeline`). Transition `ReprocessStaging → Preparing → Running` |
| UC-extra-3 | **Cancel reprocess-staging** | `Service.onTrashClicked` (L. 2457-2467) — the trash button as context-sensitive: in recording = cancelRecording, in ReprocessStaging = `uiController.cancelReprocessStaging`. **Tri-state trash.** |
| UC-extra-4 | **Recording pause + auto-stop timeout** | `RecordingStateController.onKeyboardHidden` (L. 233-247) → `togglePause + startPauseTimeout(60s)` → on expiry `cancelRecording + onAutoStopTimeout` |
| UC-extra-5 | **Resend-button click (short)** | `Service.onResendClicked` (L. 2249-2300) — async DB lookup, ResendStatusDispatcher.decide, three sub-paths: Insert / Resume / NoOp. Sets the resend button **briefly disabled** (500ms cooldown) — its own orthogonal mutator (`mainButtonsController.setResendEnabled`) |
| UC-extra-6 | **Audio-focus mid-recording toggle** | `Service.onAudioFocusToggled` (L. 2664-2687) → 1. pref write 2. `recordingStateController.setAudioFocusRuntime` (L. 201-212, mutates AudioManager only in Active) 3. icon refresh. Its own sub-lifecycle in RSC. |
| UC-extra-7 | **QWERTZ toggle / emoji toggle** | `Service.toggleQwertzKeyboard` (L. 1486-1493), `toggleEmojiPicker` (L. 1437-1443) → `stateManager.setContentArea` |
| UC-extra-8 | **Pipeline failure mid-run** | `KeyboardUiController.failStep` (L. 440-456) — sets `Running.copy(hasFailure = true)` → `refreshRecordButtonFromState` draws the button red. The pipeline continues (a failed step is "completed" for progress) |
| UC-extra-9 | **InfoBar (Update / Rate / Donate / Error)** | `Service.showInfo` (L. 2139-2144) → `InfoBarController.showInfo(type)`. Completely orthogonal to recording/pipeline, but `KeyboardStateManager.applyVisibility` triggers `infoBarController.onStateChanged` (L. 163) → suppress in SmallMode/non-MAIN_BUTTONS |
| UC-extra-10 | **Auto-enter toggle during pipeline-running** | `KeyboardUiController.toggleAutoEnter` (L. 292-299) — an in-place mutation on `PipelineUiState.Running.autoEnterActive`. Bound to the record_btn click in the service during the pipeline (not fully traced) |

→ **Use-case completeness finding:** The user list in particular lacks **UC-extra-1/2/3 (the ReprocessStaging lifecycle)**, **UC-extra-5 (resend-click cooldown)** and **UC-extra-8 (pipeline-failure display)**. These must be considered equally in the refactor.

---

## 5. Ownership Table (UI-Relevant State Data)

| State datum | Writer | Reader | Rightful owner | Owner violation? |
|---|---|---|---|---|
| `RecordingState` | RecordingStateController.setState (private) | RecordingUiController, KSM (lambda), Service (3×) | **RecordingStateController** | **None.** Clean encapsulation. |
| `PipelineUiState` | KeyboardUiController.updatePipelineState (private) | KSM (3 lambdas), LanguageController, Service (3×) | **KeyboardUiController** | **None.** Clean encapsulation. |
| `contentArea` | KSM.setContentArea | KSM internal, InfoBarController.onStateChanged (via KSM) | **KSM** | None. The setter is private → via `setContentArea(area)` only. |
| `isSmallMode` | KSM.setSmallMode | KSM internal, Service (read-only via getter) | **KSM** | None. |
| `Pref.SingleRowMode` | Service.onSingleRowModeToggled | KLMC.init/refresh | **Pref + KLMC** | None. The pref is the truth, KLMC reads. |
| `Pref.AudioFocus` | Service.onAudioFocusToggled, Settings UI | RecordingStateController.startRecording, MainButtonsController.refreshAudioFocusIcon | **Pref + RSC (runtime mirror)** | None — explicitly documented in the `setAudioFocusRuntime` KDoc (L. 201-212). |
| `Pref.SmallMode` | Service.onSmallModeToggled, Service.setupKeyboard (L. 1402) | KSM.setSmallMode (mirrored) | **Pref + KSM** | None. |
| `Pref.LastFileName` (lastAudioFileExists) | Service (after every recording) | RecordingUiController.getLastAudioFileExists lambda | **Service** | None. |
| `Pref.ResendButton` (the resend-button setting) | Settings UI | RecordingUiController lambda (combined with lastAudioFileExists, Service L. 612-613), Service direct (L. 1344, 1693-1694) | **Pref** | None — the pref read is idempotent. |
| **`resendButton.visibility`** | **6 sites in 3 classes** | UI | **No one clearly** — the claimed SSOT KSM does **not** mutate it, RecordingUiController + Service do | **🔴 CRITICAL violation — see below.** |
| `pauseButton.visibility/isEnabled/alpha` | KSM.applyRecordingControlsVisibility | UI | **KSM** | None. |
| `trashButton.visibility` | KSM.applyRecordingControlsVisibility | UI | **KSM** | None. |
| `mainButtonsController.setResendEnabled` (resend isEnabled) | Service.onResendClicked (cooldown) | UI | **MainButtonsController** | None; orthogonal to visibility. |
| `pipelineProgressLl / promptsRv / promptsCl visibility` | KSM.applyPromptsVisibility | UI | **KSM** | None. |
| `infoCl visibility` | InfoBarController + KeyboardUiController.kt:241 | UI | **InfoBarController** | Weak: KeyboardUiController bypasses with `infoCl.visibility = GONE` directly — should call `infoBarController.dismiss()`. Symptom-free today. |
| `autoEnterActive` (within `Running`) | KeyboardUiController.toggleAutoEnter | record_btn renderer | **KeyboardUiController** | None. |
| `audioFocusEnabled` (runtime) | RecordingStateController.setAudioFocusRuntime + startRecording | RSC internal | **RecordingStateController** | None. |
| `recordButton.text` | RecordingUiController (applyIdleState/applyActiveState) + KeyboardUiController.refreshRecordButtonFromState + MainButtonsController.updateRecordButtonText (LanguageController hook) | UI | **Hybrid: 2 phases** — RecordingUiController in Idle/Active/Paused, KeyboardUiController in Preparing/Running/ReprocessStaging | Implicitly OK via ordering; refactor-fragile (see §2.1 race window) |
| `recordButton.isEnabled` | RecordingUiController (true in Idle/Active, false in Preparing) + KeyboardUiController.refreshRecordButtonFromState (false in Preparing, true otherwise) | UI | the same hybrid split | ditto |

### 🔴 Critical owner violation: `resendButton.visibility`

**Six mutators in three classes:**

| # | File:Line | Sets to | Trigger |
|---|-------------|-----------|---------|
| 1 | `RecordingUiController.kt:137` | `if (lastAudio) VISIBLE else GONE` | `RecordingState → Idle` |
| 2 | `RecordingUiController.kt:158` | `GONE` | `RecordingState → Active` |
| 3 | `DictateInputMethodService.java:1345` | `VISIBLE` | `onStartInputView` (idle branch) — re-inflate / new editor |
| 4 | `DictateInputMethodService.java:1347` | `GONE` | `onStartInputView` (idle branch) — no audio |
| 5 | `DictateInputMethodService.java:1669` | `GONE` | `runTranscriptionViaOrchestrator` |
| 6 | `DictateInputMethodService.java:1839` | `VISIBLE` | `onShowResend()` (pipeline result, "keep audio") |

**Implication:**
- There is **no single state** that computes the visibility deterministically.
- Instead: event-based push updates at various lifecycle points — whoever writes last wins.
- KSM (the actual SSOT) does **not touch** resendButton at all — it is missing in the inventory at L. 240 as "GONE in Recording" per the visibility matrix, but no source is given there, because in fact RecordingUiController#158 sets it.

**Correct SSOT form** (proposal, not a code patch):
- KSM gets a lambda `() -> Boolean isResendEligible` (= `lastAudioFileExists() && Pref.ResendButton && !isRecording() && !isPaused() && !isPipelineRunning() && !isReprocessStaging()`).
- The six mutation sites disappear in favour of **one** `applyResendVisibility()` call in `KSM.applyVisibility`.
- The `onShowResend()` callback from the pipeline result becomes a state update on a new boolean (e.g. a `lastAudioFileExists` cache) — KSM.refresh is called.

### Secondary weakness: `recordButton.text/isEnabled` hybrid

It works today because:
- RecordingUiController draws (Idle, Preparing, Active, Paused).
- KeyboardUiController.refreshRecordButtonFromState draws (Idle, Preparing, Running, ReprocessStaging).
- Both are triggered via `onStateChanged`; in the typical flow KeyboardUiController overrides after RecordingUiController (because the pipeline comes AFTER recording-stop).
- KSM does not call both implicitly directly; RecordingUiController.onStateChanged calls `stateManager.refresh()`, which in turn does **not** call the recordButton renderer → the only refresh source for the KeyboardUiController record button is `updatePipelineState` → `refreshRecordButtonFromState`.

Race-fragile: if a refactor (e.g. KSM calls KeyboardUiController.refreshRecordButtonFromState on every refresh) were added, a RecordingState=Active + PipelineUiState=Idle could make the UI flicker "Dictate / Send".

---

## 6. Bug-Class Mapping

| Symptom | Owner violation / SSOT gap | Root cause |
|---------|-------------------------------|-------------|
| **Send button hidden in send mode + Single-Row** | The `recordButton.text/isEnabled` hybrid + a re-parent during an active pipeline tick (`KeyboardUiController.refreshRecordButtonFromState` per tick + `KeyboardLayoutModeController.refresh` per tick) | KSM.applyVisibility → KLMC.refresh **on every tick**. If the pipeline tick falls on the re-parent, the record_btn can be temporarily mispositioned. **Secondary:** the Single-Row chain has `space_btn = MATCH_CONSTRAINT`, all others `wrap_content` — if the `record_btn` text gets long during a pipeline tick (`"Send… 0:01"` → `"Transcription  2/3  0:08"`), the chain shifts → visually "hidden". |
| **Resend button disappears on toggle** | `resendButton.visibility` with 6 mutators (§5) + the KSM.refresh cascade triggers KLMC.refresh, which does **not** recompute resendButton visibility | On a Single-Row toggle `Service.onSingleRowModeToggled → KLMC.setSingleRowMode(true)` → re-parent + constraint-set apply. The re-parented resend_btn keeps its old `visibility` value (e.g. VISIBLE in Idle), but: if KSM.refresh is not triggered in parallel (the toggle is **not** an `applyVisibility` trigger! — see Service L. 2655-2657, calls only `setSingleRowMode`), resend stays in the state it was in before the toggle. **Critical if:** between the last recording-stop (resend → VISIBLE via `applyIdleState`) and the Single-Row toggle a new recording cycle was started (resend → GONE via `applyActiveState`) and after stop+toggle the UI should go back → resend stays GONE until the next RecordingState change. |
| **Asymmetric re-parenting (fixed 2026-05-07)** | Single-target `rehome` in KLMC vs. heterogeneous origin parents | Bug fix documented in `KeyboardLayoutModeController.kt:60-74` (the `originalParents` map). Cause: `if (toSingleRow) actionRow else inputRow` blindly, without considering that `record_pulse_layout`/`resend_btn`/`backspace_btn` live natively in `action_row`. **Category:** the classic "naive bidirectional move" — the missing symmetry was an owner loss (the original parent information was not kept). Solved today via the map, but the map is fragile with new buttons (see inventory §6 risk list #2). |

### Implicit bug class: `infoCl` direct mutation in KeyboardUiController

`KeyboardUiController.kt:241` sets `views.infoCl.visibility = View.GONE` directly instead of `infoBarController.dismiss()`. Harmless today. But if InfoBarController later maintains internal bookkeeping state (e.g. a "last shown type" cache for repetition), KeyboardUiController bypasses it — which can lead to "InfoBar disappears but does not come back on the next trigger" symptoms.

### Implicit bug class: KSM.refresh is NOT fired from every state-relevant trigger

**Triggers that call `KeyboardStateManager.refresh()`:**
- `RecordingUiController.onStateChanged` → yes
- `KeyboardUiController.updatePipelineState` (if old != new) → yes
- `Service.runTranscriptionViaOrchestrator` (L. 1672) → yes, explicit
- `Service.onFinishInputView` (L. 779, idle cleanup) → yes

**Triggers that indirectly bypass `applyVisibility()`:**
- `Service.onSingleRowModeToggled` → calls ONLY `KLMC.setSingleRowMode`, NOT `stateManager.refresh()`. If the Single-Row toggle happens in a phase where there is an inconsistency between the pref and the view state (e.g. resendButton or pauseButton visible/invisible depending on the KSM lambda), this inconsistency remains.
- `Service.onAudioFocusToggled` → triggers only `mainButtonsController.refreshAudioFocusIcon`, no refresh().
- `Service.onShowResend` → sets `resendButton.setVisibility(VISIBLE)` directly, refresh() is NOT called.

→ **SSOT gap:** The cascade `applyVisibility → applyContentAreaVisibility + applyRecordingControlsVisibility + applyPromptsVisibility + KLMC.refresh` is set up, but the Single-Row toggle and the resend visibility bypass it.

---

## 7. Planned New Use Case: A+B Background-Send Persistence

### Intended extension of the sealed classes

Per the user briefing, a new sealed member is introduced:

```kotlin
// Vermutlich (noch nicht im Code):
sealed class PipelineUiState {
    // existing: Idle, Preparing, Running, ReprocessStaging
    data class Sending.Backgrounded(...) : PipelineUiState()
    data class Sending.BackgroundedReady(...) : PipelineUiState()
}
```

Semantics per the user: the pipeline send continues in the background while the user puts the keyboard into another context (app switch, IME hide). "BackgroundedReady" = the output is available, waiting for the user's attention.

### Adjustment need in the state machine

| Site | File:Line | Adjustment |
|--------|-------------|-----------|
| **Sealed class** | `PipelineUiState.kt:13-54` | Add new members. A nested sealed (`Sending.Backgrounded` / `Sending.BackgroundedReady`) is clean if `sealed class Sending : PipelineUiState()` is inserted in between. The compiler then enforces branch coverage. |
| **`refreshRecordButtonFromState`** | `KeyboardUiController.kt:464-509` | Two new branches. **Risk:** the pipeline total timer (L. 257-264) keeps living during Backgrounded → does the button show a live timer even in the "minimised" mode? Or should Backgrounded freeze the timer? A design decision. |
| **`KeyboardStateManager` lambdas** | KSM constructor + service wiring | New lambdas: `isBackgroundedSending`, `isBackgroundedReady`. Possibly: `isPipelineProgressVisible` must also exclude Backgrounded (not "pipeline running visually" but "running headless"). |
| **`applyPromptsVisibility`** | `KeyboardStateManager.kt:194-224` | The `showPrompts` computation must consider Backgrounded — presumably the prompt bar should **NOT** be shown as pipeline progress in background mode, but rather normal prompts or its own "BackgroundedReady" indicator. Branching effort. |
| **`applyRecordingControlsVisibility`** | `KeyboardStateManager.kt:183-192` | Pause / Trash today only `isActive ∨ isStaging`. In Backgrounded they most likely must be GONE (recording finished long ago); BackgroundedReady is semantically "pick up the result" → Trash could function as "discard". A design question. |
| **`onStartInputView`** | Service L. ~1280-1410 (the re-inflate path) | Today the service restores `restoreReprocessStaging` and `pipelineOrchestrator.isRunning()` (L. 985-1018). Backgrounded must be persistent across re-inflate → either an analogous `restore…` variable or DB-driven (session status). **Large effort.** |
| **`Service.onFinishInputView`** | L. 750-783 | Currently: 3 branches (recording-active, pipeline-running, idle). The pipeline-running branch (L. 765-768) does NO cleanup today (lets the pipeline keep running). Backgrounded is **exactly** this path — the UI side must decide: does the service actively set `state = Backgrounded`, or does the state stay `Running` and the "Backgrounded" aspect lies only in the lifecycle component? |
| **`onPipelineFinished`** | Service L. 1869+ | Today: `uiController.stopPipeline()` → `Idle`. If the pipeline finishes in Backgrounded, the transition `Backgrounded → BackgroundedReady` must happen instead. Side effect: the output commit must not happen (no InputConnection in Backgrounded). |
| **`onShowResend`** | Service L. 1836-1841 | Fired today at the pipeline end. In the Backgrounded path (the user is not there) → the trigger must be delayed until BackgroundedReady → a user action. |
| **`KSM.refresh` sources** | six sites | A Backgrounded state change must trigger all visibility-relevant updates. Since `updatePipelineState` already calls `stateManager.refresh()`, that should apply automatically — provided the state change goes through `updatePipelineState`. |

### Fragile sites that can break background-send

1. **The resend_btn 6-mutator chaos (§5):** `onShowResend` from the pipeline result triggers `resendButton.setVisibility(VISIBLE)` directly. In Backgrounded that should perhaps NOT happen (no UI there), or it should happen but only become visually visible once `BackgroundedReady` → `Idle` (the user comes back). **The refactor to a KSM SSOT MUST happen before background-send**, otherwise new bugs arise in that area.

2. **The `recordButton.text/isEnabled` hybrid (§5 secondary):** In Backgrounded the UI may not be visible (IME hidden), but the tick keeps running (pipeline total timer). KeyboardUiController.refreshRecordButtonFromState updates per tick → unnecessary view updates on a GONE view. **A performance risk, not a correctness bug**, but: if RecordingUiController overrides the button in parallel (e.g. a new recording cycle is started in Backgrounded — is that even allowed?), a conflict arises.

3. **The single-job lock (`ActiveJobRegistry.isAnyActive()`):** When the background job runs, the `JobExecutor` blocks new jobs (see `Service.startResumeJob` L. 2361). Question: may the user start a new recording in the Backgrounded state? Today probably blocked via `uiController.isBusy()` (L. 188) — the Backgrounded states must return `isBusy() = true`, otherwise a second send path starts running in parallel. Test: `KeyboardUiController.isBusy() = state !is Idle` is GENERIC — a new sealed member is automatically "busy". ✅ no adjustment needed.

4. **`onResendLongClicked` (UC-extra-1):** The guard is `if (uiController.isBusy()) return;` — analogous to 3, already covered.

5. **The `KeyboardLayoutModeController.refresh()` cascade (KSM:168):** On every `applyVisibility` KLMC is reapplied. In Backgrounded that does nothing (if the UI is hidden, no layout visible), but **if the view is not GONE but only invisible** (e.g. main_buttons_cl is VISIBLE but Service.onFinishInputView does not clean up), KLMC.refresh keeps running per tick. Idempotent (L. 122 lastAppliedSingleRow guard) → performance OK, **not a correctness bug**.

6. **The prompt bar as pipeline progress (KSM L. 195-213):** `isPipelineProgress = isPipelineProgressVisible() && !isReprocessStaging()`. Should Backgrounded also set `isPipelineProgressVisible()` true? If yes, the prompt bar shows pipeline steps **while the IME is hidden** (when the IME is shown again). If no, `isPipelineProgressVisible` must exclude both Backgrounded states.

7. **Recording vs pipeline race in Backgrounded:** Today the invariant `RecordingState=Idle ⇔ pipeline can start` holds (service code ordering). In Backgrounded the pipeline keeps running, RecordingState is Idle. If the user starts a **new** recording in this phase, what happens? In the code `JobExecutor` currently blocks — but UI-side RecordingUiController.applyActiveState would run in a phase where KeyboardUiController.state=Backgrounded → KeyboardUiController.refreshRecordButtonFromState overrides RecordingUiController.applyActiveState's button mutations. **Correctness-fragile.**

### Recommendation before the background-send implementation

1. **First** consolidate `resendButton.visibility` to a KSM SSOT (§5).
2. **Then** resolve the `recordButton.text/isEnabled` hybrid (a single owner — either KeyboardUiController alone, with RecordingState hooks, or a new combined renderer that reads both state machines).
3. **Only then** introduce the Backgrounded sealed member — the branch extension is mechanical, but only stable if the underlying owner distribution is clean.

---

## Appendix A — File Pointers (All Absolute Paths)

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
- `/home/lukas/WebStorm/Dictate/docs/plans/2026-05-07 - keyboard-layout-refactor/research/main-button-area-inventory.md` (inventory — supplemented by this document)
