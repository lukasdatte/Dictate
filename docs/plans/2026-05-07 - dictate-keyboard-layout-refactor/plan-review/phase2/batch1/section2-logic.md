# Phase 2 / Batch 1 / Section 2 — Logic Review

**Section:** Service-Layer + Persistence + Lifecycle (Spec 1, §6 – §9 + §11)
**Spec file:** `/home/lukas/WebStorm/Docs/docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.md`
**Reviewer focus:** Logic, Clean Code, Code Integration (Edge Cases, Lifecycle, Concurrency, Migration order)
**Sister review:** Structure-Reviewer covers DRY/SOLID/Architecture in parallel.

The Spec is consistently detailed and most of the architecture iterations (F-1…F-11) close real gaps. The findings below are the **remaining** logic-level holes that the spec does not yet answer. Severity reflects the cost of the gap surfacing late, not the volume of writing needed to fix it.

---

## Findings

### Issue L-1: IME-Service-Death während aktiver Pipeline — keine spezifizierte State-Recovery, nur "wird klein"
- **Category:** [LOGIC]
- **Severity:** Critical
- **Location:** Spec 1 §7 + §8 + §11.3 (kein dedizierter "IME-Service-Death-Pfad")
- **Description:** Der Plan adressiert den Tastatur-**Wechsel** sehr ausführlich (Foreground-Service überlebt, Recording läuft weiter, Reconnect via `bindService`). Was nirgendwo modelliert ist: der Pfad **„IME-Service stirbt während aktiver Pipeline, aber kommt NICHT mehr zurück"** (User wechselt für die nächste halbe Stunde dauerhaft auf Gboard). Wenn die Pipeline anschließend `Pipeline-Done` erreicht, hat der Service **keine `InputConnection`**:
  - Der `PendingIntent.ACTION_INSERT` (§7.5) ruft `stateManager.confirmInsertion(id)` — die Methode soll Text in den Input-Field schreiben, aber `getCurrentInputConnection()` lebt im IME-Service, nicht im Pipeline-Service.
  - §4.7 listet `inputConnectionProvider: () -> android.view.inputmethod.InputConnection?` als injizierte Dependency in `ModuleServices`. Die Quelle dieses Providers ist **nicht spezifiziert**: füllt der Pipeline-Service den Provider mit `null` wenn der IME nicht gebunden ist? Wirft die Insert-Effect-Implementation in dem Fall? Re-tried sie? Bufferd sie den Text bis zum nächsten Bind?
  - §11.5.3 verneint explizit, dass eine IME sich per Notification-Click selbst sichtbar machen kann — d.h. der User muss erst eine andere App öffnen, dort Gboard schließen, Dictate-Tastatur aktivieren, Cursor in ein Eingabefeld setzen, und dann auf "Einfügen" klicken. Während dieser Zeit lebt eine `RECORDED/COMPLETED`-Session in der DB ohne klaren Owner.
- **Example scenario:** User startet Aufnahme im WhatsApp-Eingabefeld → wechselt zu Gboard für ein 6-stelliges Bank-TAN-Feld → Aufnahme & Pipeline laufen 12s im FGS-Service durch → `Pipeline-Done` Notification erscheint → User klickt "Einfügen" — aber die Notification hat keinen Weg, herauszufinden, in welchem InputField der ursprüngliche Cursor saß (eine fremde App ist im Vordergrund). Das Spec-Acceptance "manueller Restart aus DB" deckt nur den OOM-Death-Fall, nicht diesen.
- **Suggestion:** §7.6 + §11.6 ergänzen um einen expliziten "No-IC"-Branch in der Insert-Effect-Logik:
  1. Wenn `inputConnectionProvider() == null` → Notification-Action "Einfügen" ist disabled bzw. macht nur "in die Zwischenablage kopieren + Toast".
  2. State-Achse `lastResultNeedsManualPaste: Boolean` ergänzen, damit das UI bei nächsten IME-Show das pending-Result als "Bereit zum Einfügen" anbietet.
  3. Spec 1 §11.3.1 sollte "IME-onDestroy ohne onCreate-Re-Init" als eigenen Test-Pfad listen — heute steht er nur im Manual-Test des Acceptance-Kriteriums "Force-Stop".

---

### Issue L-2: Race zwischen Reducer-Output und parallel laufendem Hardware-Callback
- **Category:** [LOGIC]
- **Severity:** Critical
- **Location:** Spec 1 §4.3 (`DictateOrchestrator.dispatch`) + §15.2 (`RecordingModule.reduce`) + Code: `RecordingStateController.kt:271-321` (heutiger Hardware-Callback-Pfad)
- **Description:** Der Reducer ist als pure function spezifiziert, aber das Hardware-Subsystem (`RecordingManager`, `BluetoothScoManager`) emittiert weiterhin asynchrone Callbacks (`onRecordingStarted`, `onScoConnected`, `onScoFailed`, `onAmplitudeUpdate`). Diese Callbacks müssen via `services.emitAction(...)` in den Orchestrator zurückgespielt werden (siehe `ModuleServices.emitAction` §4.7). Was im Spec **nicht** geklärt ist:
  1. **Re-entrant dispatch:** `runEffect` wird synchron innerhalb von `dispatch` ausgeführt (§4.3 Schritt 4). Wenn der EffectHandler synchron `services.emitAction(action2)` aufruft (weil Hardware sofort geantwortet hat), startet `dispatch(action2)` **mitten in Schritt 5/6 von dispatch(action1)** — Cross-Module-Cascade von action1 ist da noch nicht gelaufen, der Store-Snapshot in action2 enthält den state nach action1's Reducer aber NICHT nach den Cascade-Reducern. Reihenfolge der Cascade-Actions hängt davon ab, ob der Hardware-Callback synchron oder asynchron antwortet → nicht-deterministisch.
  2. **Threading:** Heute laufen `RecordingManager`-Callbacks auf dem MediaRecorder-Thread (kein `Handler.post`). `BluetoothScoManager`-Callbacks laufen via BroadcastReceiver auf Main. Ein `runEffect` auf MediaRecorder-Thread → `services.emitAction` → `dispatch` mutiert `MutableStateFlow` aus zwei Threads gleichzeitig.
  3. **Hardware-State-Drift:** `ctx.audio.useBluetoothMic` wird im Reducer aus dem Store gelesen, aber `proceedStartRecording` (heute Z. 325) schaut auf `bluetoothScoManager.isScoStarted` (Hardware-Wahrheit, nicht State-Mirror). Wenn der Reducer entscheidet "use BT" und der Effect dann SCO aufbaut, kann die Realität dazwischen abreißen (User schaltet BT aus, BT-Stack stirbt, etc.). Welche Klasse spiegelt `isScoStarted` synchron in den `audio.bluetoothSco.phase`? Wer ruft die Action `Action.AudioAction.OnScoConnected/Disconnected/Failed`?
- **Example scenario:** User klickt Record → Action.StartRecording → Reducer emittiert `Effect.AllocateMediaRecorder` → EffectHandler startet `recordingManager.start(...)` synchron, was sofort `onRecordingStarted` callbacked → Effect-Handler ruft `services.emitAction(Action.MediaRecorderReady)` SYNCHRON innerhalb von `runEffect` → Re-entrant dispatch beginnt mit Store-Snapshot, der noch nicht von der Cross-Module-Cascade von `StartRecording` durchlaufen ist. `AudioModule.onCrossModuleStateChange(prev=Idle, next=Preparing)` hat möglicherweise `RequestAudioFocus`-Action gefeuert — die wird jetzt erst NACH `MediaRecorderReady` verarbeitet, obwohl semantisch davor erwartet.
- **Suggestion:**
  - In §4.3 explizit dokumentieren, dass `runEffect` Effekte **niemals synchron in den Orchestrator zurückrufen** dürfen. Effects, die einen Action emittieren wollen, MÜSSEN `scope.launch { emitAction(...) }` oder `Handler.post` nutzen — d.h. die Action landet als nächste Main-Looper-Message.
  - `dispatch()` als `synchronized` oder `Mutex.withLock` markieren oder explizit auf den Main-Looper confinen (`require(Looper.myLooper() == Looper.getMainLooper())`).
  - `AudioModule` (oder ein neues `BluetoothScoModule`) muss BroadcastReceiver-Callbacks in `Action.AudioAction.OnScoXxx` umwandeln. §15.1 Tabelle nennt das nicht — `bluetoothSco.phase` ist als Feld vom AudioModule da, aber der Callback-Adapter ist nicht spezifiziert.
  - `ReducerContext.recordingAudioFile = servicesFactory.get().recordingHardware.currentAudioFile()` (§4.3 buildContext, GLOBAL 1.1.7) ist tatsächlich kein heavy-IO (memory-Field, siehe `RecordingManager.kt:46`), aber **macht den Reducer un-pure**: zwei dispatches mit gleichem prior-State + gleicher Action können verschiedene `nextState/effects` produzieren, weil das Hardware-Feld extern mutiert wird. → `recordingAudioFile` sollte als State-Achse gespiegelt werden (z.B. in `RecordingState.Active(audioFile: File)` oder `recording.audioFile`), Reducer liest aus dem State, nicht aus Hardware.

---

### Issue L-3: View-Recreation (Configuration-Change) — DictateUiState rehydration nicht spezifiziert
- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 1 §8 + §11.3 + Code: `DictateInputMethodService.java:824-904` (heutiger `cleanupOldControllers` + `restoreUiState`)
- **Description:** Die heutige Codebase hat **drei** dedizierte view-recreate-Mechaniken (siehe `cleanupOldControllers` Z. 833-904, `rewireCallbacks` Z. 910+, `restoreUiState` Z. 973+):
  - `restoreAutoEnter: Boolean?` — Bridge zwischen alter und neuer KeyboardUiController-Instanz
  - `restoreReprocessStaging: PipelineUiState.ReprocessStaging?` — Bridge zur Wiederherstellung der Staging-Variante
  - Detach von BroadcastReceiver, SP-Listener, RoomInvalidationTracker, language-controller, layoutModeController
  
  Spec 1 §8 listet "View-Lifecycle bleibt im IME-Service", aber **keine dieser Bridges** ist im Refactor-Plan spezifiziert. Die Annahme "alle State liegt im StateFlow → View liest reaktiv → kein Bridge nötig" stimmt für die **State-Achsen**, aber nicht für transient view-bound Dinge:
  1. `KeyboardUiController.viewScope` (oder Pendant) MUSS bei jedem `onCreateInputView` neu erstellt und beim alten View gecanceld werden — Spec 1 erwähnt `viewScope` einmal in §5 als "neuer CoroutineScope auf IME-Side", aber wo genau das Cancel vor dem nächsten Inflate passiert, ist nicht festgelegt.
  2. Heute werden `audioFocusListener`, `inputLanguagesListener`, `bluetoothScoManager.unregisterReceiver()` explizit deregistriert (Z. 875-903). Im Refactor wandert Audio + Language + BT zum Pipeline-Service — gut. Aber: `OverlayBackend` (§4.7 `inputConnectionProvider`) und `KeyboardLayoutManager` haben view-bezogene Subscriber, die bei view-recreate abgerissen + neu aufgebaut werden müssen. Wer ist dafür verantwortlich? Spec 2 (KeyboardLayoutManager) klärt das vielleicht, aber Spec 1 §8 sollte zumindest aussprechen, dass `viewScope.cancel() + neuer viewScope + neuer state.collect()` der Vertrag ist und zeigen, an welcher IME-Lifecycle-Stelle das passiert (`onCreateInputView` vor inflate? `onFinishInputView`? `onDestroy`?).
- **Example scenario:** User dreht das Gerät während aktiver Pipeline → IME-View wird zerstört, `onCreateInputView` läuft erneut → wenn der alte `state.collect`-Subscriber im alten viewScope nicht canceled wurde, leakt er und führt View-Mutationen auf einer disposed View aus (View!=null, aber detached) → Crash (`IllegalArgumentException: View is not attached to a window manager` bei MotionScene-Apply, oder leise Mutation auf orphaned View, dann GC).
- **Suggestion:** Eine explizite Sektion §8.x "View-Recreate-Vertrag" mit:
  - `viewScope = CoroutineScope(SupervisorJob() + Main.immediate)` wird in `onCreateInputView` (vor Subscriber-Wiring) erzeugt.
  - In `cleanupOldControllers` (oder dem Refactor-Pendant): `viewScope.cancel()`. Das cancelt automatisch alle `state.collect`-Subscriber, OverlayBackend-Drag-Handler, Animation-Loops.
  - Übersicht-Tabelle: welche heutigen Detach-Calls (Z. 855-903) entfallen weil StateFlow-Subscriber automatisch canceln, welche bleiben (z.B. `WindowManager.removeView` vom OverlayBackend) und wo sie hin migriert werden.
  - Tests: Robolectric-Test "rotation while pipeline running" — keine View-Mutation auf altem View, neuer View bekommt korrekte State-Emission inkl. ReprocessStaging-Variante.

---

### Issue L-4: PipelineSessionRepo-Recovery erkennt hängende Recording-Sessions, aber löscht keine Dangling-Audio-Files atomar
- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 1 §6.3 + §11.6.2 (`recoverFromDb`)
- **Description:** §11.6.2 beschreibt zwei separate Code-Pfade:
  ```kotlin
  val orphanedRecorded = db.sessionDao().getByStatus("RECORDED")
      .filter { it.audioFilePath != null && File(it.audioFilePath).exists() }
      .map { it.toPendingSession() }
  // ... separately ...
  val ghostSessions = db.sessionDao().getByStatus("RECORDED")
      .filter { it.audioFilePath != null && !File(it.audioFilePath).exists() }
  ghostSessions.forEach {
      db.sessionDao().updateStatus(it.id, SessionStatus.FAILED.name)
      db.sessionDao().updateError(...)
  }
  ```
  Probleme:
  1. **Doppelter DB-Read** (`getByStatus("RECORDED")` zweimal). Logikproblem: zwischen den beiden Reads kann eine Session den Status wechseln, wenn ein anderer Schreiber parallel läuft. Heute kein paralleler Schreiber, aber im Service-Refactor durchaus möglich.
  2. **Recording-Status fehlt:** §6.3 listet "RECORDING, TRANSCRIBING, RECORDED, TRANSCRIBED" als stuck-Statuses. §11.6.2 reduziert auf RECORDED. Was passiert mit Sessions in `RECORDING` (Service starb mitten in der Aufnahme — Audio-File teil-geschrieben aber unvollständig) oder `TRANSCRIBING` (Service starb während Whisper-Call — Audio existiert, Text fehlt)? Werden die als pending-insertion angezeigt, obwohl es noch keinen Text gibt? Werden sie als FAILED markiert?
  3. **Audio-File ohne DB-Eintrag:** umgekehrte Richtung fehlt. Wenn der App-Cache durch Android cleared wird, bleiben in der DB Einträge mit nicht-existenten Files. Wenn die DB durch ein Backup/Restore inkonsistent wird, bleiben Files ohne DB-Einträge. Beide Fälle sind nicht modelliert.
  4. **Race recover vs. neuer Recording-Start:** §11.6.1 startet `recoverFromDb` async im IO-Scope NACH `startForegroundCompat`. Wenn der User Mikrofon klickt BEVOR `recoverFromDb` fertig ist, landet eine neue Session im Store, und die Recovery-Coroutine schreibt anschließend ihre `pendingSessions = pending + orphanedRecorded` per `_state.update { it.copy(pendingSessions = ...) }` — was die in der Zwischenzeit aufgebaute Recording-Session-Liste **überschreibt** (kein merge). Heute kein Problem, weil pendingSessions = neue State-Achse — aber die Recovery muss `update { it.copy(pendingSessions = it.pendingSessions + recovered) }` schreiben oder sicherstellen, dass keine Mutationen in der Zwischenzeit stattfinden.
- **Example scenario:** User startet Aufnahme → Service crasht (OOM) während `RECORDING`-Zustand → Recording-File `audio_123.m4a` ist halb geschrieben → Service-Restart + `recoverFromDb` → Session ist `RECORDING` → fällt durch alle Filter (`getByStatus("COMPLETED")` bei `findPendingInsertion` + `getByStatus("RECORDED")` bei orphaned/ghost) → bleibt für immer im Status `RECORDING` in der DB, wird nie aufgeräumt, der User sieht sie nicht mal in der History.
- **Suggestion:**
  - §6.3 in Code-Form bringen, mit allen Status-Branches:
    ```kotlin
    suspend fun recoverFromDb() = withContext(Dispatchers.IO) {
        val stuck = sessionDao.getSessionsByStatuses(
            listOf(RECORDING, TRANSCRIBING, RECORDED, TRANSCRIBED, COMPLETED)
        )
        val (alive, dead) = stuck.partition { 
            it.status == COMPLETED ||
            (it.audioFilePath != null && File(it.audioFilePath).exists())
        }
        // 1. Alles, was eindeutig kaputt ist (keine Audio + nicht COMPLETED): FAILED
        dead.forEach { sessionDao.markFailed(it.id, "audio file vanished") }
        // 2. RECORDING/TRANSCRIBING ohne Pipeline → FAILED (nicht recoverable, wir starten nicht erneut)
        alive.filter { it.status == RECORDING || it.status == TRANSCRIBING }
             .forEach { sessionDao.markFailed(it.id, "process died mid-pipeline") }
        // 3. Echte pending: COMPLETED ohne inserted_at, plus RECORDED mit existierendem File
        val pending = sessionDao.findPendingInsertion() + 
                      alive.filter { it.status == RECORDED }
        store.update { it.copy(pendingSessions = (it.pendingSessions + pending.toPersistentList())) }
    }
    ```
  - Acceptance-Test "process killed during RECORDING/TRANSCRIBING" als eigenen Punkt in §10.

---

### Issue L-5: Concurrency 2 Pipelines parallel — Auto-Enter / Chain-Pipeline ist nicht gegen Race spezifiziert
- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 1 §4.3 (dispatch) + §15.1 PipelineModule + LivePromptModule (`Pipeline-Done → ChainNext`)
- **Description:** Die LivePromptModule-Cross-Observation `Pipeline-Done → ChainNext` impliziert: am Ende von Pipeline-A wird Action `StartPipeline(B)` gefeuert. Wenn der User parallel **noch ein** Recording startet (bevor Pipeline-A done war), gibt es zwei Pipelines, die parallel um `PipelineRunner.submit(...)` konkurrieren. §4.10 sagt "Alle Mutationen laufen NUR über `dispatch`" — aber:
  1. **`PipelineRunner` ist nicht reentrant spezifiziert.** §4.9 deklariert nur `submit / submitReprocess / cancelActive / isRunning`. Was passiert, wenn `submit` beim laufenden Job gerufen wird? Queue? Reject? Ersetzen? Heute (`JobExecutor.kt`) gibt es `ActiveJobRegistry` — das Verhalten ist real, aber nicht in der Spec als Vertrag aufgeschrieben.
  2. **PipelineUiState ist sealed mit 4 States**, kein Multi-Slot. D.h. `state.pipeline` kann nur ENTWEDER `Running(A)` ODER `Running(B)` halten. Wenn B startet während A läuft, geht A's State verloren — aber A's Job läuft noch und wird einen `Pipeline-Done`-Action emittieren → der Reducer sieht `state.pipeline = Running(B)` und bekommt ein Done-Signal, das semantisch zu A gehört. Welche sessionId wird dem `inserted_at` zugeordnet? Welche Cross-Module-Cascade läuft (Done von A würde LivePromptModule.ChainNext triggern, aber LivePrompt war für B nicht aktiv, etc.)?
  3. **DB-Write-Konkurrenz:** `Pipeline-Start` (§6.2) macht `UPDATE sessions SET status=TRANSCRIBING WHERE id=...`. Wenn A und B beide TRANSCRIBING sind und beide `Pipeline-Done`-Hook abfeuern, gibt es zwei parallele `UPDATE sessions SET status=TRANSCRIBED, result_text=...` mit unterschiedlichen IDs — kein Konflikt im SQL, aber im Store-StateFlow (Single-Slot `state.pipeline`) kollidieren die Reducer-Outputs.
- **Example scenario:** User dictiert kurze Notiz (Pipeline-A startet, 8s erwartete Dauer), klickt Auto-Enter ON → User klickt Record erneut nach 1s, weil ihm noch was eingefallen ist (Pipeline-B startet) → Pipeline-A done nach 8s, Pipeline-B noch in Progress (nach 1s erst) → Pipeline-A's Done-Signal wird auf `state.pipeline = Running(B)` projiziert → Auto-Enter-Insertion-Logik (LivePromptModule) feuert für B's Text obwohl A's Text gemeint war. Worst case: Text-Vermischung im Output-Field.
- **Suggestion:**
  - §4.10 oder §15.PipelineModule explizit dokumentieren: **`PipelineUiState` repräsentiert nur den UI-fokussierten Job; mehrere parallele Background-Jobs sind möglich, aber jeder hat seine eigene `sessionId`.** Done-Action enthält `sessionId`, der Reducer matched den Done gegen den aktiven Slot — wenn die ID nicht matched, wird der Done als Background-Insertion behandelt (DB-Schreibung + Notification, aber kein UI-State-Wechsel).
  - Alternative: **Verbieten** mehrerer paralleler Pipelines durch Reducer-Pre-Check. Action.StartRecording im Zustand `pipeline=Running(_)` wird ignoriert oder wirft eine User-sichtbare Toast-Meldung "Vorherige Pipeline noch nicht fertig".
  - Acceptance-Test "Auto-Enter + zweites Recording während laufender Pipeline" zu §10 hinzufügen.

---

### Issue L-6: Migration-Reihenfolge §11.2.2 Block-1 ist nicht gleichzeitig kompilierbar
- **Category:** [INTEGRATION]
- **Severity:** Important
- **Location:** Spec 1 §11.2.2 (Block-1, Schritte 3 + 4) — bezieht GLOBAL 1.1.6
- **Description:** §11.2.2 listet Block-1-Schritte als "1 → 2 → 3 → 4 → 5 → 6 → 7". Schritt 3 sagt: *"RecordingStateController-Inhalt einkopieren — Body wandert in PipelineStateManager-Methoden"* + *"Existierende `RecordingStateController.Callback`-Empfänger werden auf `state.collect`-Subscriber umgebaut"*. Schritt 4 sagt: *"KeyboardUiController-State-Teil migrieren"* + *"Public-API-Methoden bekommen Wrapper-Forwarding `→ stateManager.preparePipeline()`"*. Logikproblem:
  1. Wenn Schritt 3 die `RecordingStateController.Callback`-Empfänger (im IME-Service: `onStateChanged`, `onTimerTick`, `onAmplitudeUpdate`, `onScoConnected`, etc., siehe `DictateInputMethodService.java:914-958`) auf `state.collect` umbaut, dann muss der `state.collect` BEREITS funktionieren — d.h. `PipelineStateManager` muss die Recording-Achse korrekt mutieren bevor die alten Callbacks gelöscht werden. Code in einem Zwischenzustand: PipelineStateManager existiert, aber die Hardware-Adapter (RecordingManager, BluetoothScoManager) feuern noch in `RecordingStateController`-Callbacks, NICHT in PipelineStateManager-Actions.
  2. Schritt 4 (KeyboardUiController) ist nicht **unabhängig** von Schritt 3: heute ruft `KeyboardUiController.refreshRecordButtonFromState` auf View-Properties (`recordButton.text/isEnabled`) und liest den `RecordingStateController.state` synchron (§9.5 — "Hybrid"). Während Schritt 3 läuft, ist `RecordingStateController.state` noch live — der Wrapper `stateManager.preparePipeline()` würde `_state.value.recording` lesen, aber das ist noch nicht der gleiche Truth-Source wie `RecordingStateController.state`, weil die Hardware-Callbacks noch nicht umgeleitet sind.
  3. Schritt 5 ("resend_btn-Mutationen entfernen") ersetzt 6 Mutations-Sites (§13.1 Tabelle) durch ein State-Subscriber im IME — aber dazu muss `state.lastAudioExists` korrekt gepflegt werden, und das fließt in §13.4.1 als "neue State-Achse" — wer setzt sie wann? §11.2.2 nennt Schritt 5 "Transitional", aber `lastAudioExists` ist nicht in Schritt 1-4 gespiegelt.
- **Example scenario:** Entwickler arbeitet Block 1 sequentiell ab. Nach Schritt 3 (RecordingStateController-Migration) compiliert die App, aber bei Recording-Start schreibt `RecordingManager.onRecordingStarted` in den `RecordingStateController`-Callback, der dann die Action auf `PipelineStateManager` dispatched — funktioniert. Aber `KeyboardUiController.state` ist noch lokal, der `KeyboardLayoutManager` (Spec 2) liest noch das alte Field. → Tests in CI brechen mit "RecordButton.text falsch", weil zwei Truth-Sources existieren.
- **Suggestion:**
  - §11.2.2 in **kompilier-grüne Etappen** umstrukturieren. Vorschlag: Block 1 wird in 1a/1b/1c geteilt, jedes mit eigenem Smoke-Test:
    - **1a (DictateUiState + Manager-Skelett):** `DictateUiState`-Klasse + `PipelineStateManager` ohne Action-Body angelegt, Manager wird vom IME-Service konstruiert aber nicht genutzt. CI-Test: App startet, Recording funktioniert wie vorher. Kein Verhaltens-Change.
    - **1b (Recording-Achse Migration):** RecordingStateController-Body wandert in Manager. RecordingStateController existiert weiter als **Adapter** (delegiert an Manager). Hardware-Callbacks gehen weiter durch RecordingStateController. CI-Test: alle Tests aus `RecordingStateControllerTest` laufen unverändert grün, weil RecordingStateController weiter exportiert.
    - **1c (Pipeline-Achse + KeyboardUiController):** Analog für KeyboardUiController.
    - **1d (resend_btn Mutations + Quick-Wins):** State-Subscriber im IME-Service, Mutations-Sites löschen.
    - **1e (RecordingStateController + KeyboardUiController-Adapter löschen):** Wenn keine Konsumenten mehr, Klassen löschen.
  - Pro Etappe: kompiliert + Tests grün. Kein "Halb-State", in dem zwei Truth-Sources gleichzeitig existieren.
  - Adressiert direkt GLOBAL 1.1.6 (Block-1-Aufwand unterschätzt).

---

### Issue L-7: LayoutModule + AudioModule — mögliche inkonsistente State bei gleichzeitigem Layout-Mode-Wechsel + AudioFocus-Wechsel
- **Category:** [LOGIC]
- **Severity:** Nice-to-have (eskaliert zu Important wenn nicht im Reducer-Vertrag adressiert)
- **Location:** Spec 1 §4.3 dispatch-Sequenz + §15.1 (LayoutModule + AudioModule); GLOBAL 1.1.4
- **Description:** Single-Dispatch (F-8) garantiert: nur EINE Action wird zur Zeit verarbeitet. Aber: zwei Actions, die in schneller Folge dispatched werden, sind sequentiell — bei `dispatch(LayoutAction.ToggleSmallMode)` + `dispatch(AudioAction.RequestFocus)` aus zwei verschiedenen Quellen (Layout-Toggle vom UI, AudioFocus-Trigger vom System) ist das ok, sofern:
  1. Beide Reducer NICHT aufeinander angewiesene Pre-Conditions prüfen (z.B. AudioModule darf nicht Layout-Achsen lesen, LayoutModule darf nicht Audio-Achsen lesen). Heute scheint das gegeben (siehe §15 Tabelle: Cross-Observer LayoutModule = "nein").
  2. **Cross-Module-Cascade ist atomar in Bezug auf den nächsten dispatch.** §4.3 Schritt 6 dispatched cascade-Actions REKURSIV — d.h. zwischen action1 und ihrer cascade kann KEINE neue action2 verarbeitet werden, weil dispatch single-threaded ist. Gut. Aber: §4.10 sagt "Direkt-Mutationen verboten" — wenn aber `runEffect` einen async Hardware-Call startet (`audioManager.requestAudioFocus(...)`), kommt dessen Callback (`OnAudioFocusChange`) **nach** der Cascade von action1 zurück, und ein parallel laufender Layout-Toggle könnte zwischendrin landen. Reihenfolge: action1.reduce → action1.cascade → layout-toggle.reduce → layout-toggle.cascade → audioFocus-callback.reduce. Das ist OK — solange der Reducer der audioFocus-callback-Action den dann aktuellen state liest (atomar via store.snapshot). Aber wenn der EffectHandler von action1 sich gemerkt hat "ich starte den Recording, sobald ich Audio-Focus habe" und das in einer State-Variable hält, ist das ein **un-spezifizierter, side-Effect-State außerhalb des Stores**.
  3. Der Spec listet `audioFocusGranted: Boolean` (system-status) als Sub-State (§3.AudioState), aber spezifiziert nicht, wer die Action `Action.AudioAction.OnAudioFocusGained/Lost` feuert (vermutlich AudioFocusSubsystem über services.emitAction) und wie der Reducer auf gleichzeitige Recording-Active reagiert (Pause? Cancel? Continue?). §15.1 Tabelle nennt "AudioFocus-Loss → Recording.Pause" als Cross-Observer — gut, aber wo ist der Code dafür?
- **Example scenario:** User klickt während aktiver Aufnahme den SmallMode-Toggle (Layout-Mode-Wechsel: Two-Row → Single-Row → re-inflate evtl.) → gleichzeitig kommt ein Telefonanruf rein → AudioFocus-Loss-Callback → Reducer "Recording.Pause" → Pause-Effect → MediaRecorder.pause. Reihenfolge der Reducer-Aufrufe ist single-thread, also OK. **Aber:** Layout-Toggle führt im RenderBackend (Spec 2) zu `MotionScene.transitionToState`, was UI-Async ist. Wenn der Render-Backend mid-Animation den Pause-State sieht, gibt's möglicherweise inkonsistente UI (Pulse läuft weiter, weil Animation noch nicht fertig, aber recordButton.text ist schon "Resume"). Das ist Spec 2 Domain — Spec 1 Logic-Reviewer kann das nur flaggen, aber tatsächlich liegt das Risiko in der Cross-Spec-Übergabe.
- **Suggestion:**
  - §15 Tabelle ergänzen um Spalte "Effect-Async-Footprint": welche Module emittieren Effects, deren Hardware-Callback NACH der Cascade landet? AudioModule (audioFocus-callback) und RecordingModule (mediaRecorder-callback) sind die hot Kandidaten.
  - In den Acceptance-Tests §10 einen Test "interruption während layout-toggle" oder "concurrent layout + audio" aufnehmen.
  - GLOBAL 1.1.4 (LayoutModule SRP) bleibt unter "Nice-to-have", weil das aktuelle SRP-Design vom strukturellen Standpunkt clean ist (LayoutModule kennt nur Layout-Achsen) — die Crosstalk-Konsequenz liegt im **Render-Backend** (Spec 2), nicht im Reducer.

---

### Issue L-8: Coroutine-Scopes — Service-Scope vs. View-Scope vs. Module-Scope, kein klarer Cancel-Vertrag
- **Category:** [LOGIC]
- **Severity:** Important
- **Location:** Spec 1 §4.7 (`ModuleServices.scope`) + §7.3 (`serviceScope`) + §5 (`viewScope`)
- **Description:** Spec 1 listet drei Coroutine-Scopes mit unterschiedlichem Lifetime:
  - `serviceScope = CoroutineScope(SupervisorJob() + Main.immediate)` (§7.3) — Service-Lifetime, gecanceld in `onDestroy`.
  - `viewScope` (§5 IME-Side) — View-Lifetime, gecanceld bei view-recreate.
  - `ModuleServices.scope` (§4.7) — wird vom Service injiziert; vermutlich `serviceScope`, aber nicht explizit gesagt.
  
  Probleme:
  1. **Module emittieren Effects, die Coroutines starten.** RecordingModule.runEffect(AllocateMediaRecorder) startet `recordingManager.start(...)` (synchron, schnell), aber `Effect.StartTimer` startet einen Timer-Loop, der typischerweise im scope läuft. Wenn der Service stirbt, wird `serviceScope.cancel()` gerufen → Timer stoppt. Gut. Aber: was, wenn das Modul eine Coroutine startet, die `services.emitAction(...)` in einer Schleife ruft (z.B. AmplitudeStream)? Wenn `serviceScope.cancel()` geschieht, wird das Future evtl. mid-emission gekappt → ein partial-emitted `Action.RecordingAction.OnAmplitude` landet vielleicht noch im Store, vielleicht nicht. Der Store ist nach `cancel` noch da, aber niemand subscribed. → Akzeptiert, solange Subscriber via viewScope-Cancel sauber detached.
  2. **Cancel-Reihenfolge bei Service-Shutdown:** §7.3 `onDestroy` ruft `stateManager.shutdown()` THEN `serviceScope.cancel()`. Aber `stateManager.shutdown()` (= `DictateOrchestrator.shutdown()` §4.3) ruft nur `prefMirror.detach()`. Es ruft NICHT `runEffect(ReleaseMediaRecorder)`, NICHT `cancelActive` auf PipelineRunner, NICHT `bluetoothScoManager.release()`. → Wenn der Service stirbt während aktiver Pipeline, läuft die Pipeline-Coroutine noch, der MediaRecorder wird NICHT released, BluetoothSco bleibt connected. §13.5 G6 ("MediaRecorder-Leak bei Process-Death") nennt das "akzeptiert", aber **Service-onDestroy ist nicht Process-Death** — es kann ein normaler `stopSelf` sein, oder ein vom System getriggerter Service-Stop.
  3. **viewScope-Cancel und in-flight LocalBinder-Calls:** Wenn der IME-Service onDestroy ruft (Tastatur-Wechsel), wird viewScope gecanceld. Ein in-flight `dispatch(action)` über den LocalBinder läuft im Service-Scope, nicht im viewScope — d.h. die Action wird verarbeitet, der Reducer läuft, der State wird mutiert, aber der State-collect-Subscriber im viewScope ist schon canceled → keiner sieht das State-Update. Das ist OK, weil bei nächstem onCreateInputView ein neuer Subscriber den aktuellen state.value sieht (StateFlow replays den letzten Wert). Aber das sollte explizit dokumentiert sein.
- **Example scenario:** Service stirbt durch Android-System-Driven-Stop (FGS-Restriction-Verletzung, sehr selten, aber möglich) → `onDestroy` → `prefMirror.detach()` (sync) → `serviceScope.cancel()` → laufender Recording-Job + MediaRecorder werden nicht released → MediaRecorder hält Mikrofon-Lock → andere Apps können Mikro nicht nutzen, bis das System die Native-Resource gc't.
- **Suggestion:**
  - `DictateOrchestrator.shutdown()` (§4.3) erweitern um eine **terminale Cleanup-Sequenz**: für jedes Modul `runEffect(<terminate-Effect>)` rufen, wo der Effect die Hardware released. Z.B. `RecordingModule` hat `Effect.ReleaseMediaRecorder`, `AudioModule` hat `Effect.ReleaseAudioFocus`, `BluetoothSco`-Subsystem `Effect.ReleaseSco`. Reihenfolge wichtig: erst Recording (MediaRecorder kann blocken auf Pause), dann AudioFocus, dann BT.
  - `onDestroy`-Pseudocode in §7.3 ergänzen:
    ```kotlin
    override fun onDestroy() {
        super.onDestroy()
        runBlocking { stateManager.shutdownAndRelease(timeout = 1.seconds) }   // synchron releases
        serviceScope.cancel()                                                   // dann cancel
    }
    ```
    Mit Timeout, falls Hardware-Release blockiert.
  - Tabelle "Cancel-Order pro Scope" in §7 oder §11.3 ergänzen, mit Zeile pro Subsystem (was wird zuerst released, was hängt von wem ab).

---

### Issue L-9: Migration-Plan listet "RecordingManager, BluetoothScoManager, JobExecutor, LanguageController" als Halb-State-Risiko, aber kein Cleanup-Pfad spezifiziert
- **Category:** [LOGIC]
- **Severity:** Nice-to-have
- **Location:** Spec 1 §9 + §11.7 (Tests, die brechen / Tests, die nötig sind)
- **Description:** §11.7.1 listet exakt vier Test-Klassen, die brechen (RecordingStateControllerTest, MultiCallbackForwardingTest, JobExecutorTest, ActiveJobRegistryTest, LanguageControllerTest — letzte drei "unverändert"). Aber: GLOBAL 1.1.6 frägt nach **allen** existierenden Controllern — nicht nur die in §9 erwähnten. Schnellsuche im Codebase zeigt zusätzlich:
  - `LanguageController.kt` — wird im Refactor nirgendwo erwähnt, soll aber laut §15 vom `LanguageModule` ersetzt werden
  - `BluetoothScoManager.kt` — bleibt erhalten, aber als Subsystem hinter `BluetoothScoSubsystem`-Adapter. Wer baut den Adapter? Wann?
  - `RecordingManager.kt` — bleibt erhalten als `RecordingHardwareSubsystem` (§4.7)
  - `KeyboardStateManager.kt` — laut §9.3 wird `applyVisibility` weg, contentArea + isSmallMode wandern in Store. Aber die **Klasse selbst** wird nicht explizit gelöscht. §13.5 G2 sagt "der explizite `setSmallMode`-Call wird redundant und entfällt", aber löscht die Klasse?
  - `RecordingUiController.kt` — laut §9.5 enthalten heute Recording-State-Mutationen + Visibility-Mutationen. §13.4.1 listet alle Mutations als "wandert in LayoutCatalog" — was passiert mit der Klasse selbst?
  
  Ohne expliziten "Klasse X wird in Block Y gelöscht"-Plan bleibt der Code lange in einem "Halb-State", in dem alte Klassen + neue Module nebeneinander existieren. Das ist kein Logik-Bug, aber ein **operativer** Logik-Schmerz: jedes Refactor-Step muss prüfen, dass die alten Klassen keine versteckten Abonnenten mehr haben.
- **Suggestion:**
  - §9 ergänzen um eine "Lösch-Tabelle":
    | Heutige Klasse | Final gelöscht in Block | Übergangsweise als Adapter? |
    |---|---|---|
    | RecordingStateController | Block 1 (nach Migration) | nein, direkt gelöscht (Tests umgeschrieben) |
    | KeyboardUiController | Block 1 | partial: state-Teil wandert, View-Teil bleibt bis Spec 2 |
    | RecordingUiController | Block 5 (LayoutCatalog) | bleibt bis dahin, sub-set seiner Methoden wandern |
    | KeyboardStateManager | Block 5 (LayoutCatalog) | bleibt bis dahin |
    | LanguageController | Block ??? (nicht spezifiziert) | unklar |
    | RecordingManager | nie gelöscht (Subsystem-Adaptee) | wird hinter `RecordingHardwareSubsystem`-Interface gewrapped |
    | BluetoothScoManager | nie gelöscht | Subsystem-Adaptee |
    | JobExecutor | nie gelöscht | implementiert `PipelineRunner`-Interface |
  - Pro Block ein "End-of-Block-Cleanup-Check": grep nach den Namen der gelöschten Klassen, dürfen nur in der gelöschten Datei vorkommen.

---

### Issue L-10: §6.2 Checkpoint-Hooks — keine Idempotenz, keine Atomarität, keine Failure-Strategie
- **Category:** [ROBUSTNESS]
- **Severity:** Important
- **Location:** Spec 1 §6.2 (Checkpoint-Hooks-Tabelle)
- **Description:** Die Tabelle listet 6 State-Transitions mit korrespondierenden DB-Schreibungen (`INSERT sessions ...`, `UPDATE sessions SET status=...`). Was nicht spezifiziert ist:
  1. **Idempotenz:** wenn der gleiche State-Update zweimal kommt (z.B. wegen StateFlow-Replay nach view-recreate), wird `INSERT sessions` zweimal gefeuert → `SQLiteConstraintException` wegen primary-key-Konflikt? Oder `INSERT OR IGNORE`? Nicht festgelegt. **Heute** (`SessionDao.kt`) ist die Konvention vermutlich `@Insert(onConflict = REPLACE)` — sollte aber explizit Vertrag sein.
  2. **Atomarität:** die Hooks laufen aus dem Reducer (pure) heraus über einen SideEffect (§4 / §15.PipelineModule). EffectHandler ruft `services.sessionRepo.markCompleted(...)` async im IO-Scope. Wenn der Service mid-Schreibung crashed, ist der State im Store schon weiter, die DB hängt zurück → next recover sieht inkonsistenten Status. Logik-Frage: ist DB-Write **vor** State-Mutation (write-ahead) oder **nach** (write-behind)? §6.2 nennt beides nicht.
  3. **Failure-Pfad:** wenn `markCompleted` fehlschlägt (DB voll, IO-Error), feuert der EffectHandler heute keine Failure-Action zurück — der State bleibt auf "TRANSCRIBED" im Store, in der DB hängt er auf "TRANSCRIBING". Der Failure ist unsichtbar bis zum nächsten Service-Start.
  4. **Cleanup-Policy "INSERTED > 7d"** (§6.1): wann wird `dao.deleteInsertedOlderThan(now - 7d)` gerufen? §11.2.2 Block 3, Schritt 7 sagt "einmal vor `stopSelf()`". Aber `stopSelf` wird vom Coordinator getriggert, sobald `state.isAllTerminal()` (§7.7). Race: Cleanup-Coroutine läuft async, parallel triggert ein neuer Recording-Start eine Pipeline. Wenn `deleteInsertedOlderThan` mit der DB-Tabelle arbeitet, während eine neue Session reingeschrieben wird, gibt's keinen Konflikt (nur unterschiedliche Rows), aber wenn der Cleanup VERSEHENTLICH eine just-jetzt-erst-COMPLETED Session löscht (clock-skew), ist Datenverlust möglich.
- **Example scenario:** Pipeline schließt bei Sekunde 0 → Reducer setzt `pipeline = Idle, pendingSessions += new` → Effect `markCompleted(sessionId, ...)` startet IO → DB-Disk voll → `markCompleted` wirft → kein Catch-Handler → Coroutine stirbt leise (CoroutineExceptionHandler im scope ist nicht spezifiziert) → DB hängt auf TRANSCRIBING → next Service-Start sieht stuck-Session → markFailed wegen "process died mid-pipeline" (siehe Issue L-4 Vorschlag) → User verliert das Transkript, ohne je die Notification gesehen zu haben.
- **Suggestion:**
  - §6.2 erweitern um:
    - Idempotenz-Garantie: `@Insert(onConflict = REPLACE)` oder explizites `INSERT OR IGNORE` + separater UPDATE.
    - Reihenfolge-Vertrag: **State-First** (Reducer mutiert Store, dann Effect schreibt DB). Begründung: User sieht das State-Update sofort; DB ist Recovery-Truth, nicht Live-Truth. Wenn DB-Write fehlschlägt, dispatchen wir `Action.PersistenceError(sessionId, reason)` zurück, der Reducer kann im pendingSessions-Slot ein `failed`-Marker setzen.
    - `CoroutineExceptionHandler` im `serviceScope` (§7.3 onCreate) ergänzen, der DB-Failures als Action zurück in den Orchestrator füttert.
  - §6.1 Cleanup-Policy: Cleanup-Job bekommt einen `cutoff = now - 7d - safetyBuffer(1h)` damit Clock-Skew + Test-Time-Travel den Datenverlust nicht treffen.

---

### Issue L-11: §6.3 recoverFromDb mit `getSessionsByStatuses` — Methode nicht in §4.9 PipelineSessionRepo definiert
- **Category:** [CLEAN]
- **Severity:** Nice-to-have
- **Location:** Spec 1 §6.3 vs. §4.9
- **Description:** §6.3 ruft `db.sessionDao().getSessionsByStatuses(listOf(...))`, §11.6.2 ruft `db.sessionDao().getByStatus("RECORDED")`. Beide Methoden sind:
  - direkt auf `sessionDao()` (= Room-DAO), nicht auf dem Interface `PipelineSessionRepo` (§4.9, das `loadPending / markInserted / markFailed / pendingFlow` listet)
  - nicht in §6.1 SessionDao-Erweiterung als neue Queries gelistet (§6.1 listet nur `markInserted, findPendingInsertion, deleteInsertedOlderThan`)
  - nicht in §11.7.3 Test-Fakes ("FakeSessionDao") als Mock-Methode genannt
  
  D.h. `getSessionsByStatuses` und `getByStatus` sind im Spec implizit angenommen, aber nirgendwo formal ergänzt. Logik-Konsequenz: der Recovery-Pfad nutzt einen API-Surface, der nicht durch das Interface (DIP, F-2) abstrahiert ist. Tests können das nicht stubben ohne Hack.
- **Suggestion:** §4.9 `PipelineSessionRepo` ergänzen um:
  ```kotlin
  suspend fun loadByStatuses(statuses: List<SessionStatus>): List<SessionEntity>
  ```
  und `findPendingInsertion / loadPending` strikt unterscheiden (loadPending = nur COMPLETED + inserted_at IS NULL, loadByStatuses = generischer). §6.1 SessionDao um die Query ergänzen. §11.7.3 FakeSessionDao um die Mock-Implementation.

---

### Issue L-12: §11.5.1 POST_NOTIFICATIONS-Permission-Strategie ist unter-spezifiziert
- **Category:** [ROBUSTNESS]
- **Severity:** Nice-to-have
- **Location:** Spec 1 §11.5.1
- **Description:** §11.5.1 sagt "IME-Services dürfen aus UX-Gründen keine `requestPermissions`-Dialoge zeigen". Settings-Activity prompted. Aber: was passiert auf API 33+, wenn User die Permission abgelehnt hat?
  - Foreground-Service-Notification: §11.5.1 sagt "Service-Start funktioniert dennoch", aber das stimmt nur teilweise. Auf Android 13+ wirft `startForeground` keine Exception, aber die Notification ist **unsichtbar** für den User. Das heißt: `state.recording = Active` aber User sieht **keinen Indikator**, dass die Aufnahme läuft. Wenn die Tastatur dann zur HOVER-Modus wechselt, sieht der User auch nichts (Overlay-Permission separat). Worst case: User glaubt, Aufnahme ist gestoppt, sie läuft aber im Hintergrund weiter — Privacy-Sensibel.
  - §11.5.1 ist nur 5 Zeilen lang, und spezifiziert keinen Re-Prompt-Pfad oder Fallback-UI.
- **Suggestion:** §11.5.1 erweitern um:
  - Beim Service-Start: wenn `notificationManager.areNotificationsEnabled() == false` und Recording aktiv → Action `Action.SystemAction.NotificationsDisabledWarning` dispatchen → ein Sub-State `interruption.notificationsDisabled = true` → IME-View zeigt einen Warning-Banner ("Hintergrund-Aufnahme ohne Benachrichtigung — bitte Berechtigung erteilen").
  - Onboarding (§11.5.1 erwähnt OnboardingActivity) sollte beim ersten Start die Permission anfragen.

---

## Summary Table

| # | Category | Severity | Issue | Description |
|---|----------|----------|-------|-------------|
| L-1 | [LOGIC] | Critical | IME-Service-Death während Pipeline | Insert-Action ohne InputConnection-Pfad ist nicht modelliert; FGS-Recovery deckt nur OOM-Death |
| L-2 | [LOGIC] | Critical | Reducer-Pure / Hardware-Callback-Race | Re-entrant dispatch + Hardware-Callbacks aus anderen Threads + buildContext liest Hardware-Field → kein deterministischer Reducer |
| L-3 | [LOGIC] | Important | View-Recreate-Vertrag fehlt | viewScope-Cancel + State-Subscriber-Reattach + Hardware-Listener-Cleanup nicht in Spec 1 §8 spezifiziert |
| L-4 | [LOGIC] | Important | Recovery deckt RECORDING/TRANSCRIBING nicht | Stuck-Sessions in Mid-Pipeline-Status fallen durch, doppelter DB-Read mit Race-Window |
| L-5 | [LOGIC] | Important | 2 parallele Pipelines (Auto-Enter) | PipelineUiState als Single-Slot reicht nicht für überlappende Sessions, Done-Action-Routing nicht spezifiziert |
| L-6 | [INTEGRATION] | Important | Block-1-Migration in §11.2.2 nicht kompilier-grün | Schritte 3+4 erzeugen Doppel-Truth-Source, CI bricht zwischen den Schritten — adressiert GLOBAL 1.1.6 |
| L-7 | [LOGIC] | Nice-to-have | LayoutModule + AudioModule Cross-Effect | GLOBAL 1.1.4: Single-Dispatch reicht, aber async Hardware-Callbacks → Render-Backend-Frame-Inkonsistenz möglich |
| L-8 | [LOGIC] | Important | Coroutine-Scope-Cancel-Reihenfolge | shutdown() released keine Hardware, MediaRecorder/AudioFocus/BT bleiben hängen bei System-Driven-Stop |
| L-9 | [CLEAN] | Nice-to-have | Migrations-Lösch-Tabelle fehlt | LanguageController, KeyboardStateManager, RecordingUiController-Lifecycle nicht eindeutig |
| L-10 | [ROBUSTNESS] | Important | Checkpoint-Hooks ohne Idempotenz/Atomarität/Failure | DB-Write-Reihenfolge, Failure-Action, Cleanup-Race nicht im Spec |
| L-11 | [CLEAN] | Nice-to-have | recoverFromDb nutzt Methoden außerhalb des Repo-Interface | getSessionsByStatuses / getByStatus nicht in §4.9 PipelineSessionRepo |
| L-12 | [ROBUSTNESS] | Nice-to-have | POST_NOTIFICATIONS-Verweigerung führt zu unsichtbarer Aufnahme | Privacy-Risiko, kein Fallback-UI spezifiziert |

---

## Bezug zu GLOBAL_ISSUES (Section 2)

| GLOBAL | Logic-Review-Verdikt | Findings |
|---|---|---|
| **1.1.6** Block-1-Aufwand unterschätzt | bestätigt | L-6 (Schritt-Reihenfolge nicht kompilier-grün), L-9 (Lösch-Tabelle fehlt) |
| **1.1.7** buildContext synchroner Hardware-Call | teil-bestätigt | L-2.3: `recordingHardware.currentAudioFile()` ist memory-Field (kein heavy-IO), aber **macht Reducer un-pure**. Sollte als State-Achse gespiegelt werden. |
| **1.1.4** LayoutModule SRP | nicht in Spec 1 verankert | L-7: Spec-1-seitig OK (single-dispatch sequential), Crosstalk-Risiko liegt in Spec 2 (Render-Backend-Frame-Konsistenz). Logic-Reviewer akzeptiert das aktuelle Design. |

---

## Notes

- Die F-1 bis F-11 Iterationen haben strukturelle Schwächen sehr gut adressiert. Die in dieser Review aufgeführten Logic-Issues sind **darunterliegend** — d.h. sie bleiben bestehen, wenn man die Klassen-Aufteilung als gegeben hinnimmt.
- L-1, L-2, L-5 sind die teuersten Findings: spät auftretend, schwer zu reproduzieren (Race-bedingt oder requires real device + Tastatur-Wechsel), und mit Datenverlust-Konsequenz.
- L-6 ist der kostengünstigste Fix mit dem höchsten Hebel: §11.2.2 in 1a/1b/1c-Etappen umstrukturieren, jede mit grünen Tests. Adressiert direkt GLOBAL 1.1.6.
- L-3, L-8 sind lifecycle-spezifisch und benötigen einen explizit dokumentierten Cancel-/Cleanup-Vertrag, den der heutige Code (`cleanupOldControllers`) bereits implizit hat — der Refactor sollte ihn nicht versehentlich verlieren.
