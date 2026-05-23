# Phase B — Quality-Gate Report S-5: Service-Schicht (IME-only → DictatePipelineService + LocalBinder + Lifecycle-Recovery)

- **Datum:** 2026-05-13
- **Reviewer:** Phase-B-Subsystem-Reviewer #6 (Service-Schicht)
- **Plan:** `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/`
- **Inventur-Anker:** `quality-gate/phase-a-subsystem-inventory.md` §S-5 (Z. 297–355)
- **Vorgänger-Reports:** `phase-b-s1-state-hierarchy.md`, `phase-b-s2-db-schema-migration.md`, `phase-b-s3-action-hierarchy.md`, `phase-b-s4-orchestrator.md`, `phase-b-s7-audio-file-management.md`
- **Spec-Files:** Spec 1 §7 (Service-Lifecycle) + §11.1 (FGS-Implementation) + §11.3 (Bind-Lifecycle) + §11.5 (Notification-UX) + §11.6 (OOM-Recovery) + §4.11.5.1 (onCreate-Sequence) + §10 (Block-2-Acceptance)
- **Code-Refs verifiziert:** `app/src/main/AndroidManifest.xml`, `core/DictateInputMethodService.java:329-396`, `core/JobExecutor.kt:30-72`, `core/JobExecutor.kt:340-365` (`PipelineOrchestratorRunner`-Adapter)

---

## Summary

Der Migrationspfad für S-5 ist **architektonisch gesund** — die Trennung in einen Foreground-Service (Process-Lifecycle-Owner), der den Composition-Root + Orchestrator + Module hält, plus den IME-Service als reinen View-Lifecycle-Owner mit LocalBinder-Connection ist sauber und löst das zentrale Problem (Tastatur-Wechsel-Survival) strukturell. Die §7-Architektur post-F-3 (NotificationCoordinator + ActionRouter aus dem Service extrahiert) ist SOLID-konform.

**Kritisch waren jedoch drei Snippet-Drifts in Spec 1:**

1. **§11.1.2 onStartCommand-Snippet** war pre-F-11 (`stateManager.pauseRecording()` etc., aber `PipelineStateManager` existiert nicht mehr — überholt seit 2026-05-10). S-1 F-2 hat §7.3 + §11.1.4 auf F-11 umgestellt, aber §11.1.2 wurde dabei übersehen.
2. **NOTIF_ID** war doppelt definiert mit unterschiedlichen Werten: `1001` (§11.1.2) vs `0xD1C7A7E` (§7.4 `PipelineNotificationCoordinator.companion`) — sticky-FGS-Notification + überlagerte Update-Notification hätten parallele Sichtbarkeit produziert.
3. **`ensureNotificationChannel()`** war zwar in §11.1.4 dokumentiert, aber im §7.3 onCreate-Snippet (post-S-1-F-2) NICHT aufgerufen. Auf API ≥ 26 hätte das beim Fresh-Install zur `IllegalArgumentException` in `startForeground` geführt → Service-Death → ANR-Klasse.

Plus eine fünfte Falle die der Implementer beim ersten `assembleDebug` getroffen hätte: `JobExecutor.initialize(orchestrator: PipelineOrchestrator)` erwartet den **alten** PipelineOrchestrator (Audio-Pipeline-Runner, Code-verifiziert via `JobExecutor.kt:56`), aber das §7.3-Snippet rief `runner.initialize(orchestrator)` mit dem **neuen** DictateOrchestrator (Type-Mismatch).

**Befund: 3 Critical, 7 Important, 3 Minor (13 Findings total). 16 Plan-Edits in 2 Dateien (Spec 1: 15, Hauptplan: 1).**

---

## Findings + Applied Fixes

### F-1 §11.1.2 onStartCommand-Snippet pre-F-11-Drift (CRITICAL)

- **Severity:** Critical
- **Prüf-Achse:** 1 (5s-Timeout-Fenster), 5 (Service-onCreate-Reihenfolge), Surprise-Finding-Klasse "Service-Aufteilung §7.1 vs §7.3 Konsistenz" (Phase-A flagged)
- **Was:** §11.1.2 zeigte einen vollständigen `onStartCommand`-Pfad mit `stateManager.pauseRecording()` / `stateManager.resumeRecording()` / `stateManager.stopRecording()` / `stateManager.stopRecordingAndSend()` / `stateManager.cancelPipeline()` / `stateManager.confirmFirstPendingInsertion()` / `stateManager.discardFirstPendingInsertion()`. Der `PipelineStateManager` existiert nach F-11 (2026-05-10) nicht mehr — er ist in `DictateOrchestrator` (single-dispatch) + 12 Module aufgesplittet. S-1 F-2 hat §7.3 + §11.1.4 + §11.6.1 auf F-11 umgestellt, aber **§11.1.2 wurde übersehen** — dasselbe Snippet existierte in zwei Sektionen mit zwei verschiedenen Pfaden. §7.3 (SoT) ruft `actionRouter.dispatch(intent)` (`PipelineActionRouter` → `DictateOrchestrator.dispatch(Action.X)`); §11.1.2 rief direkt typed Methoden auf einem nicht-mehr-existierenden Singleton.
- **Konsequenz:** Ein Implementer, der §11.1.2 als Vorlage nahm (ausführlicher dokumentiert + erscheint nach §7.3 in der Doku, "spätere Iteration"-Annahme), hätte versucht, einen `stateManager`-Field anzulegen — Compile-Error auf jeden Method-Call. Schlimmstenfalls hätte er versucht, die Methoden auf einem bestehenden Modul direkt zu rufen (`recordingModule.pauseRecording()`), F-8 Single-Dispatch-Vertrag gebrochen. Bug-Klasse: "architektonische Konfusion durch Spec-Drift" — identisch zu S-1 F-2, aber an einer Stelle die S-1 nicht erwischt hat.
- **Fix angewandt:**
  - **Spec 1 §11.1.2 onStartCommand-Block:** komplett umgeschrieben. Pure Forward an `actionRouter.dispatch(intent)` für Action-Intents; `startForegroundCompat(notifCoordinator.buildInitial())` für FGS-Notification; `notifCoordinator.startReactiveUpdates(::stopSelfWhenTerminal)` für reaktive Updates. Konsistent mit §7.3 (SoT). FIX-Kommentar verweist auf §7.3 als SoT.
  - **Spec 1 §11.1.2 `startForegroundCompat`:** Signatur auf `(notif: Notification)` umgestellt (vorher `(state: DictateUiState)` mit inline-Build) — der NotificationCoordinator (§7.4) ist der einzige Notification-Builder; der Service forwarded nur die fertige Notification. KDoc dokumentiert API-34-Type-Argument.

### F-2 NOTIF_ID-Doppel-Definition (CRITICAL)

- **Severity:** Critical
- **Prüf-Achse:** 7 (NotificationCoordinator-Verantwortlichkeit), Surprise-Finding-Klasse
- **Was:** §7.4 PipelineNotificationCoordinator: `companion object { const val NOTIF_ID = 0xD1C7A7E }` (~219.802.238 — entstand aus dem hex-Wort "DICTATE"). §11.1.2 DictatePipelineService: `companion object { private const val NOTIF_ID = 1001 }`. Zwei Definitionen unterschiedlicher Werte in zwei Sektionen — der Coordinator ist SoT (Single Source of Truth für Notification-Identität), aber das §11.1.2-Snippet hatte einen eigenen `private`-Wert.
- **Konsequenz:** Wenn ein Implementer den §11.1.2-Wert (1001) für `startForeground(NOTIF_ID, …)` nutzt und der Coordinator `nm.notify(0xD1C7A7E, …)` ruft, sind das **zwei separate System-Notifications**: eine ist die sticky-FGS-Notification (1001), eine ist die reguläre Update-Notification (0xD1C7A7E). Beide bleiben sichtbar nebeneinander, der User sieht zwei Dictate-Notifications. Beim `stopSelf` versucht das System nur die FGS-Notification zu entfernen (1001), der Coordinator hat aber nie einen `cancel(0xD1C7A7E)`-Pfad in §7.4 — die Update-Notification bleibt als "Phantom" hängen, bis OS sie nach App-Process-Death räumt. Plus: bei `notifCoordinator.buildInitial()` wird intern `NOTIF_ID = 0xD1C7A7E` referenziert, der Service ruft `startForeground(1001, notification)` → die zwei IDs sind völlig disjunkt, sticky-Status-Tracking funktioniert nicht. Bug-Klasse: "Doppel-Identity durch Spec-Drift" — schwer zu debuggen, weil beide IDs syntaktisch valid sind und beide einzeln funktionieren; das Problem ist nur die Disjunkt-Kollision.
- **Fix angewandt:**
  - **Spec 1 §11.1.2 companion-Block:** NOTIF_ID-Definition gestrichen, durch Kommentar ersetzt: "NOTIF_ID lebt im PipelineNotificationCoordinator (§7.4) — Service referenziert `PipelineNotificationCoordinator.NOTIF_ID` direkt". CHANNEL_ID bleibt.
  - **Spec 1 §11.1.2 nach onStartCommand-Block:** expliziter SoT-Hinweis-Block "NOTIF_ID-Konsolidierung (Phase-B S-5)" mit Begründung der Disjunkt-Kollision und Verweis auf §7.4-SoT.
  - **Spec 1 §7.3 onDestroy-Code (siehe F-6):** Step 3 ruft `NotificationManagerCompat.from(this).cancel(PipelineNotificationCoordinator.NOTIF_ID)` — verwendet den SoT-Wert.
  - **Spec 1 §10 Block-2-Acceptance:** neue Klausel "Phase-B S-5 NOTIF_ID-Konsistenz" mit Architektur-Test (Lint oder Kotlin-Reflection), der gegen erneutes Aufkommen einer Doppel-Definition schützt.

### F-3 `ensureNotificationChannel()` nicht in §7.3 onCreate aufgerufen (CRITICAL)

- **Severity:** Critical
- **Prüf-Achse:** 1 (5s-Timeout-Fenster + Channel-Vorbedingung)
- **Was:** §11.1.4 5-s-Timeout-Snippet ruft `ensureNotificationChannel()` als erste Aktion in `onCreate`. Aber §7.3 onCreate-Snippet (post-S-1-F-2, das volle Composition-Root-Wiring) hat den Call **nicht**. Auf API ≥ 26 (`Build.VERSION_CODES.O`) wirft `NotificationManager.notify` / `Service.startForeground` mit einer Notification, deren `CHANNEL_ID` keinen existierenden Channel referenziert, eine `IllegalArgumentException: Bad notification posted from package`. Die Doppel-Snippet-Existenz (§7.3 vs §11.1.4) hat S-1 F-2 nicht harmonisiert — §7.3 ist der "vollständige Wiring-Pfad", §11.1.4 ist die "Timing-Mitigation-Klausel" mit eigenem Mini-Snippet.
- **Konsequenz:** Bei einem Fresh-Install (App-First-Open, kein Channel existiert) crashed der erste `startForeground`-Call mit `IllegalArgumentException`. Service stirbt sofort, IME-Bind-Counter geht auf null, Recording / Pipeline funktionieren überhaupt nicht. Bug-Klasse: "ANR + Crash beim ersten Recording-Versuch auf jedem Fresh-Install-Device" — Play-Store-Crash-Burst bei Rollout.
- **Fix angewandt:**
  - **Spec 1 §7.3 onCreate-Snippet:** `ensureNotificationChannel()` als erste Aktion nach `super.onCreate()` eingefügt, mit ausführlichem FIX-Kommentar (verweist auf §11.1.4 5-s-Timeout-Vertrag + API-≥-26-Exception-Klasse). Sync, in-memory, < 5 ms.
  - **Spec 1 §4.11.5.1 Sequence-Tabelle:** neuer **Schritt 1.5** "ensureNotificationChannel()" — explizit als Vor-Schritt zu Schritt 9 (startForeground) verankert. Zusätzlich neuer **Schritt 9** "startForeground(NOTIF_ID, notifCoordinator.buildInitial()) — gerufen aus `onStartCommand`" — vorher war der startForeground-Call implizit, jetzt explizit als Sequence-Schritt.
  - **Spec 1 §10 Block-2-Acceptance:** neue Klausel "Phase-B S-5 NotificationChannel-vor-startForeground" mit Fresh-Install-Fixture-Test (Channel via `deleteNotificationChannel` löschen → Service-Boot → kein `IllegalArgumentException`).
  - **Spec 1 §11.1.2 Channel-Setup-Vorspann:** Anker-Hinweis ergänzt, dass `ensureNotificationChannel()` SYNCHRON in `onCreate` läuft, BEVOR `startForeground` aufgerufen wird.

### F-4 §7.2 vs §11.3.1 Bind-Site-Drift (IMPORTANT)

- **Severity:** Important
- **Prüf-Achse:** 4 (Bind-Lifecycle)
- **Was:** §7.2 sagte "IME-Service onCreate (oder beim ersten Recording)" — Code-Snippet war kontextfrei (kein Hook-Name in `// IME-Service onCreate`-Kommentar). §11.3.1 sagt explizit "Bind in `onCreateInputView`" mit Latenz-Begründung (50-200 ms first-bind in Inflate-Window). IME-Service-onCreate ist ein anderer Hook als IME-onCreateInputView — IME-onCreate kann VOR dem ersten View-Inflate laufen (manche OEM-IME-Wechsel-Pfade rufen es, ohne dass ein View entsteht), und ein bindService dort hätte den Service unnötig früh hochgezogen mit potentiellem Race gegen den View-Lifecycle.
- **Konsequenz:** Implementer, der §7.2 wörtlich nimmt, baut den Bind in `IME.onCreate` ein. Wenn der Android-IME-Switcher das IME unsichtbar hochfährt (z.B. Permission-Probe), würde der Bind den FGS starten ohne Recording, ohne Notification-Bedarf — Resource-Verschwendung. Plus: der View-Subscriber wird in `onCreateInputView` an den Binder gebunden; wenn `onCreate` den Bind macht aber `onCreateInputView` ihn nicht erneut prüft, fehlt der Subscriber bei View-Recreate.
- **Fix angewandt:**
  - **Spec 1 §7.2:** Code-Snippet auf `onCreateInputView` umgestellt; explizite Java-Methode (statt context-freier Kommentar); Cross-Link auf §11.3.1 Begründung.
  - **Spec 1 §11.2.2 Block 2 Sub-Schritt 3:** auf "Bind in `onCreateInputView` (NICHT `onCreate` der IME)" konkretisiert + Latenz-Begründung explizit gemacht.

### F-5 `JobExecutor.initialize`-Typ-Konflikt (IMPORTANT)

- **Severity:** Important
- **Prüf-Achse:** 5 (Service-onCreate-Reihenfolge), Naming-Konflikt §1.x
- **Was:** §7.3 onCreate-Snippet (post-S-1-F-2) rief `runner.initialize(orchestrator)`. Der `orchestrator`-Identifier im Snippet ist `DictateOrchestrator` (der NEUE State-Action-Router, §4.3). Aber `JobExecutor.initialize(orchestrator: PipelineOrchestrator)` erwartet den **alten** `PipelineOrchestrator` (Audio-Pipeline-Runner, 1383 Zeilen, `core/PipelineOrchestrator.kt`) — verifiziert via Code-Read `JobExecutor.kt:56-58`: `fun initialize(orchestrator: PipelineOrchestrator) { this.orchestrator = PipelineOrchestratorRunner(orchestrator) }`. Type-Mismatch → Compile-Error beim ersten `assembleDebug` von Block 2.
- **Konsequenz:** Naming-Konflikt-Falle (S-4 F-7 Naming-Konvention §1.x verankert, aber das §7.3-Snippet hatte die Falle dennoch inline). Implementer sieht "Cannot infer type" oder "Type mismatch: inferred type is DictateOrchestrator but PipelineOrchestrator was expected". 10-15 Min Debug-Zeit bis das Naming-Konvention-Doc gefunden wird. Plus: einige Implementer könnten versehentlich versuchen, das `JobExecutor.initialize`-Interface auf `DictateOrchestrator` zu refactoren — was die alten Audio-Pipeline-Sites (HistoryDetailActivity etc.) bricht.
- **Fix angewandt:**
  - **Spec 1 §7.3 onCreate-Snippet:** explizite Konstruktion des `pipelineOrchestrator: PipelineOrchestrator` (alter Audio-Pipeline-Runner) mit FIX-Kommentar, der auf §1.x Naming-Konvention verweist; `JobExecutor.initialize(pipelineOrchestrator)`-Aufruf mit dem alten Orchestrator als Argument.
  - **Spec 1 §4.11.5.1 Sequence-Tabelle:** **Schritt 10** "JobExecutor.initialize(pipelineOrchestrator)" — explizit mit ⚠-Warnzeichen und "Erwartet den **alten** `PipelineOrchestrator` (Audio-Pipeline-Runner, Spec 1 §1.x Naming-Konvention), NICHT den neuen `DictateOrchestrator`."

### F-6 Service-onDestroy ohne `runBlocking`-Timeout-Wrapper (IMPORTANT)

- **Severity:** Important
- **Prüf-Achse:** 6 (onDestroy-Cleanup-Sequenz)
- **Was:** §4.3 `shutdown()`-KDoc (post-S-1-F-4 + S-4-F-8) behauptete: "Module-`terminate`-Calls dürfen blockieren (max. 1–2 s), weil sie unter `runBlocking`-Timeout des Service.onDestroy laufen sollen — Android-FGS gibt ~5 s, siehe §11.1.4". Aber §7.3 onDestroy-Snippet rief:
  ```kotlin
  override fun onDestroy() {
      super.onDestroy()
      orchestrator.shutdown()    // SYNCHRON!
      serviceScope.cancel()
  }
  ```
  Es gibt **keinen** `runBlocking { withTimeout(2000L) { … } }`-Wrapper. Wenn ein Modul-`terminate` einen Coroutine-Suspend macht (z.B. zukünftiger NotificationCoordinator-Cleanup mit DB-Flush), läuft das auf einem nicht-Coroutine-Context — entweder Crash mit "Cannot suspend outside coroutine" oder Silent-No-Op. Pathologisch lange `terminate`-Calls (Thread.sleep im Modul-Test, oder lock-contention) hängen onDestroy bis OS-seitig SIGKILL bei ~20 s.
- **Konsequenz:** Bug-Klasse "ANR auf Service-Death" — User sieht "Dictate hat aufgehört zu reagieren"-Dialog. Außerdem: Notification bleibt sichtbar nach Service-Death weil der Cancel-Call hinter dem `terminate`-Block versteckt war (gibt es im §7.3-Snippet gar nicht). Plus: §4.3-KDoc-Aussage "Effekte sind hier synchron-Hardware-Releases" ist optimistisch — die Realität ist, dass jeder Modul-Author das in Zukunft anders machen kann.
- **Fix angewandt:**
  - **Spec 1 §7.3 onDestroy-Snippet:** komplett neu geschrieben. Drei Schritte: (1) `try { runBlocking { withTimeout(2_000L) { orchestrator.shutdown() } } } catch TimeoutCancellationException { Log.w + weiter }`, (2) `serviceScope.cancel()` (MUSS NACH shutdown, S-4 F-8), (3) `NotificationManagerCompat.from(this).cancel(PipelineNotificationCoordinator.NOTIF_ID)` als idempotenter Fail-Safe (auch wenn `stopSelfWhenTerminal` schon gerufen hat). Companion object `TAG = "DictatePipelineSvc"`.
  - **Spec 1 §10 Block-2-Acceptance:** neue Klausel "Phase-B S-5 onDestroy-Timeout" mit `DictatePipelineServiceShutdownTimeoutTest.kt` — Mock-Module mit 5-s-blockierendem `terminate(services)`. Assert: onDestroy returnt nach < 2.5 s.

### F-7 Pre-Bind-Action-Pfad ohne User-Feedback (IMPORTANT)

- **Severity:** Important
- **Prüf-Achse:** 4 (Bind-Lifecycle — Java-Konsumenten dispatchen vor `onServiceConnected`)
- **Was:** §11.3.2 ServiceConnection-Edge-Cases deckten nur `onServiceDisconnected` / `onBindingDied` / `onNullBinding`. §11.3.3 behauptet "kein UI-Race möglich" — Begründung "Touch-Events laufen auf demselben Main-Looper". Das stimmt für die initiale Bind-Sequenz im stabilen Lifecycle, gilt aber NICHT für (a) Crash-Recovery (Service neu gebindet, Click vor `onServiceConnected`), (b) `onBindingDied`-Re-Bind-Race, (c) Touch-Event in der Edge-Case-Window zwischen `bindService`-Call und `onServiceConnected`-Main-Looper-Message. Java-Konsumenten (Spec 1 §4.4 S-3-F-6 DictateUiStateObserver) dispatchen via `binder.dispatch(action)` — wenn `pipelineBinder == null`, NullPointerException.
- **Konsequenz:** Crash bei seltenen Bind-Race-Conditions ODER silent-drop (wenn defensiv `if (binder != null)` ohne User-Feedback). Bug-Klasse "User klickt Record-Button, nichts passiert, kein Feedback" — schwer zu reproduzieren, frustrierend.
- **Fix angewandt:**
  - **Spec 1 §11.3.2a NEU:** "Pre-Bind-Action-Pfad (Notification-Buttons während Boot)" — eigene Sub-Sektion. Erläutert: Notification-Action-Buttons feuern via `getService` direkt an `onStartCommand`, brauchen also weder LocalBinder noch Bind-Connection (immun gegen die Race). IME-Buttons dagegen brauchen den Binder — defensiv-Check + Toast-Fallback. Plus: `onStartCommand`-Defensive-Check für `!::orchestrator.isInitialized` (sollte nicht passieren, aber Logging statt Crash).
  - **Spec 1 §11.3.2a:** Java-Snippet `dispatchAction(Action action)` mit `Toast.makeText(this, R.string.dictate_service_not_ready, ...)` als Fallback.
  - **Pflicht-Aufgabe Block 2:** neue String-Resource `dictate_service_not_ready` (DE: "Service startet noch — bitte kurz warten.", EN: "Service is starting — please wait a moment.").
  - **Spec 1 §10 Block-2-Acceptance:** neue Klausel "Phase-B S-5 Pre-Bind-Action-Toast" mit `DictateInputMethodServiceBindRaceTest.kt`.

### F-8 Tastatur-Wechsel-Survival User-Visibility unklar (IMPORTANT)

- **Severity:** Important
- **Prüf-Achse:** 8 (Bugs durch Migration — Tastatur-Wechsel-Survival)
- **Was:** Block-2-Acceptance hatte: "Recording starten, Tastatur zur Gboard wechseln, 30s warten, zurück → Recording läuft noch". Aber keine Klausel "User sieht Mic-Indikator im System-Tray + kann via Notification cancellen". Auf API ≥ 31 zeigt das OS automatisch einen Mic-Indikator (privacy indicator dot in der Status-Leiste) bei aktivem `FOREGROUND_SERVICE_TYPE_MICROPHONE` — das ist ein zentrales User-Vertrauen-Feature ("warum hat meine App noch das Mic, obwohl ich die Tastatur gewechselt habe?"). Der Plan dokumentierte das nicht.
- **Konsequenz:** User-Trust-Lücke. Wenn der User die Tastatur wechselt und das Recording aus seiner Sicht "verschwindet", aber das Mic weiter läuft, fühlt er sich überwacht. Plus: der User kann das Recording NICHT cancellen, weil die Notification ohne ausdrückliche Acceptance vielleicht nicht User-getestet ist.
- **Fix angewandt:**
  - **Spec 1 §10 Block-2-Acceptance:** neue Klausel "Phase-B S-5 Mic-Indikator beim Tastatur-Wechsel" — explizit verankert: während IME-Service tot, `FOREGROUND_SERVICE_TYPE_MICROPHONE` triggert OS-Mic-Indikator (gratis), persistente Notification zeigt `[Pause] [Stopp] [Senden]`-Buttons, User kann via Notification-Action ohne IME-Wechsel cancellen. Verifiziert manuell (E2E mit zwei Tastaturen) + via `DictatePipelineServiceForegroundSurvivalTest.kt`.

### F-9 FGS-Killed-by-System (Low-Memory) Recovery-Visibility (IMPORTANT)

- **Severity:** Important
- **Prüf-Achse:** 8 (Service-Killed-by-System, Process-Death-Recovery)
- **Was:** §7.3 returnt `START_NOT_STICKY`. Bei OS-OOM-Kill auf low-memory-Geräten wird der Service NICHT automatisch neu gestartet. Recovery-Pfad RECORDING → FAILED (§6.3 + Block-3-Acceptance R.16a) läuft erst beim **nächsten** User-Bind (also wenn User die Tastatur das nächste Mal öffnet). Plan dokumentierte die User-Sichtbarkeits-Lücke nicht.
- **Konsequenz:** Edge-Case-Lücke. Bei low-memory-Kill mid-recording verschwindet die Notification, Recording ist weg, User merkt es erst beim nächsten Tastatur-Open — und sieht dann in `pendingSessions` einen FAILED-Eintrag. Ohne Acceptance-Test gibt es keinen Schutz gegen Regression.
- **Fix angewandt:**
  - **Spec 1 §10 Block-2-Acceptance:** neue Klausel "Phase-B S-5 FGS-Killed-by-System (Low-Memory)" — `adb shell am kill`-Simulation während aktives Recording. Assert: beim nächsten IME-`onCreateInputView` läuft Service-onCreate frisch durch, `PipelineRecovery.recover` lädt Session als RECORDING→FAILED (R.16a), `state.pendingSessions` enthält die unterbrochene Session mit korrektem Error-Marker. Verifiziert via `DictatePipelineServiceKillRestartTest.kt`.

### F-10 `audioFileFactory` Field-Position in Block 2 vs Block 4 (MINOR)

- **Severity:** Minor
- **Prüf-Achse:** 5 (Service-onCreate-Reihenfolge), 11 (Plan-Phasen-Kohärenz)
- **Was:** §7.3 onCreate-Snippet zeigt `audioFileFactory = CacheDirAudioFileFactory(applicationContext)` als Composition-Root-Schritt. §4.11.5.1 Sequence-Tabelle Schritt 3 listet `audioFileFactory`-Konstruktion. Aber laut §11.2.2 ist `audioFileFactory` erst Block 4 — das gesamte `AudioFileFactory`-Interface + Implementation kommen in Block 4 zur Welt. Block-2-Implementer hätte einen Compile-Error gehabt ("`AudioFileFactory` not found") oder einen Stub anlegen müssen, ohne klare Anweisung.
- **Konsequenz:** Block 2 lässt sich nicht isoliert kompilieren. Implementer fragmentiert die Block-2-Implementation entweder mit auskommentierten Lines oder zieht Block-4-Anteile vor (Block-Reihenfolge-Bruch).
- **Fix angewandt:**
  - **Spec 1 §7.3 onCreate-Snippet:** `audioFileFactory` als **Member-`lateinit var`-Field** im Service deklariert (Phase-1-Stub) — Block-2-Skelett. Die `CacheDirAudioFileFactory`-Konstruktion in `onCreate` weist auf das Member-Field zu (kein `val` shadow), damit Block-4-Methoden (z.B. `cleanupOrphanedTerminalAudio` in `stopSelfPath`) das Field aus anderen Service-Methoden lesen können.
  - **Spec 1 §11.2.2 Block 2 Sub-Schritt 1:** explizit "Stub-Composition-Root + audioFileFactory-Stub"-Hinweis ergänzt — Phase-1-Stub-Klausel.

### F-11 POST_NOTIFICATIONS-Prompt-Flow lückenhaft (MINOR)

- **Severity:** Minor (aber User-Visibility-relevant)
- **Prüf-Achse:** 3 (POST_NOTIFICATIONS Runtime-Permission)
- **Was:** §11.5.1 sagte "IME kann keine Permission-Dialoge zeigen — daher in Settings/Onboarding nachfragen". Aber: kein konkreter Sub-Schritt in §11.2.2 Block 2, kein ActivityResultLauncher-Snippet, kein User-Friction-Signal im IME bei Decline, keine Re-Prompt-Strategie für Update-Users (die Onboarding nicht erneut sehen).
- **Konsequenz:** Block-2-Implementer fragt entweder gar nicht (User-Visibility-Lücke beim Recording — Notification unsichtbar, FGS läuft, User merkt nichts) ODER baut einen ad-hoc-Prompt ohne durchgängige Lifecycle-Pflege. Plus: Update-Users (die das Onboarding nie wieder sehen) hätten keinen Re-Prompt-Pfad.
- **Fix angewandt:**
  - **Spec 1 §11.5.1:** Lifecycle-Tabelle der Permission-States; **Prompt-Site 1**: ActivityResultLauncher-Kotlin-Snippet für Onboarding (Fresh-Install); **Prompt-Site 2**: Settings-Activity-onResume mit Pref-Flag-idempotentem One-Shot-Prompt (Update-Users); **User-Friction-Signal**: Hinweis-Banner im Keyboard-View bei aktivem Recording + Decline, Klick öffnet `Settings.ACTION_APP_NOTIFICATION_SETTINGS`. Banner-Implementation als Block-6-LayoutCatalog-Sache (BannerSlot-Predicate).
  - **Spec 1 §11.2.2 Block 2 Sub-Schritt 6 NEU:** "POST_NOTIFICATIONS Runtime-Permission-Prompt in Onboarding ergänzen" als eigener Sub-Schritt mit Pflicht-String-Resource.
  - **Spec 1 §10 Block-2-Acceptance:** neue Klausel "Phase-B S-5 POST_NOTIFICATIONS-Prompt".

### F-12 Multi-Bind-Klärung fehlte (MINOR)

- **Severity:** Minor
- **Prüf-Achse:** 4 (Mehrere Bind-Konsumenten — Surprise-Finding-Klasse)
- **Was:** Plan dokumentierte nicht, ob mehrere Clients (IME + Settings-Activity + HistoryDetailActivity) parallel binden dürfen/sollen. S-3 F-6 hat die `DictateUiStateObserver`-Java-Brücke verankert, aber das ist eine separate API (Lifecycle-Observer), nicht ein zweiter bindService. Ohne Klärung wäre der Block-2-Implementer entweder eine `BindRefCounter`-Premature-Optimization gefahren (Single-Bind-Restriction, Code-Lärm) oder hätte uncoordinierten Multi-Bind erlaubt.
- **Konsequenz:** Architektur-Ambiguität. Phase-2-Erweiterungen (z.B. Settings-Activity zeigt "Aktive-Sessions-Anzeige") wären unklar gewesen.
- **Fix angewandt:**
  - **Spec 1 §11.3.4 NEU:** "Multi-Bind-Klärung (Phase-B S-5)" — eigene Sub-Sektion. Client-Tabelle (IME = primärer Konsument, Settings/HistoryDetail = Phase-2-optional). LocalBinder-Singleton-Vertrag. `stopSelf()`-Interaktion mit Bind-Counter. `START_NOT_STICKY` + Service-Death-Klausel.
  - **Spec 1 §10 Block-2-Acceptance:** neue Klausel "Phase-B S-5 Multi-Bind" mit `DictatePipelineServiceMultiBindTest.kt`.

### F-13 FGS-5s-Boot-Latenz nicht acceptance-getestet (MINOR)

- **Severity:** Minor
- **Prüf-Achse:** 1 (5-s-Timeout-Fenster — Regressionsschutz)
- **Was:** §11.1.4 dokumentierte die 5-s-Frist als Mitigation, aber kein reproduzierbarer Test gegen Regression. Wenn ein zukünftiger Implementer einen sync-DB-Read in `onCreate` einbaut (z.B. um pendingSessions sofort verfügbar zu haben), könnte die Frist verbraucht werden — ohne Acceptance-Test schlüpft das durch Code-Review.
- **Konsequenz:** Spätere Regression — auf langsamen Geräten oder bei DB-locks könnte der FGS-Start gelegentlich timeoutten. Schwer zu reproduzieren, weil Latenz-abhängig.
- **Fix angewandt:**
  - **Spec 1 §10 Block-2-Acceptance:** neue Klausel "Phase-B S-5 FGS-Boot < 5 s" mit Robolectric- oder instrumented-Test, p99 < 1 s auf API-34-Test-Device. Test-Datei `DictatePipelineServiceFgsBootLatencyTest.kt`.

---

## Verifikationen (Code-Reads)

| Plan-Aussage | Verifiziert per | Ergebnis |
|---|---|---|
| `AndroidManifest.xml` hat heute **nur** den IME-Service-Eintrag (Z. 29-40), keine FGS-Permissions | Read `AndroidManifest.xml:1-40` | ✅ verifiziert — 5 Permissions (RECORD_AUDIO, INTERNET, VIBRATE, BLUETOOTH, MODIFY_AUDIO_SETTINGS), kein FOREGROUND_SERVICE / FOREGROUND_SERVICE_MICROPHONE / POST_NOTIFICATIONS / SYSTEM_ALERT_WINDOW |
| `DictateInputMethodService.java:329-396` ist die heutige Composition-Root-Methode `initLongLivedObjects()` | Read Z. 329-396 | ✅ verifiziert — instantiiert `aiOrchestrator`, `promptService`, `sessionManager`, `sessionTracker`, `recordingRepository`, `promptQueueManager`, `audioFocusRequest`, `recordingStateController`, `recordingManager`, `bluetoothScoManager`, `pipelineOrchestrator`. Ruft `JobExecutor.INSTANCE.initialize(pipelineOrchestrator)` Z. 389 |
| `JobExecutor.initialize` erwartet `PipelineOrchestrator` (alt), NICHT `DictateOrchestrator` (neu) | Read `JobExecutor.kt:56-58` | ✅ Bug bestätigt — `fun initialize(orchestrator: PipelineOrchestrator) { this.orchestrator = PipelineOrchestratorRunner(orchestrator) }`. Type-Mismatch wenn §7.3-Snippet `runner.initialize(orchestrator: DictateOrchestrator)` ruft |
| `JobExecutor` hat Test-Seam-Pattern (`initialize` / `initializeForTest` / `resetForTest`) | Read `JobExecutor.kt:60-72` | ✅ verifiziert — `internal fun initializeForTest(runner: PipelineRunner)` und `internal fun resetForTest()` als JvmStatic |
| `PipelineOrchestratorRunner`-Adapter delegiert an die alten Blocking-APIs | Read `JobExecutor.kt:346-364` | ✅ verifiziert — `runTranscription/resume/regenerate/postProcess` rufen `orchestrator.run*Blocking(...)` (alte Audio-Pipeline-Methoden) |
| §11.1.2 onStartCommand-Snippet rief `stateManager.X()` (pre-F-11-Drift) | Read Spec 1 §11.1.2 vor Fix | ❌ Bug bestätigt — 7 verschiedene `stateManager.*`-Aufrufe; `PipelineStateManager` existiert nach 2026-05-10 nicht mehr |
| §11.1.2 hatte eigene `NOTIF_ID = 1001`-Definition | Read Spec 1 §11.1.2 vor Fix | ❌ Bug bestätigt — `private const val NOTIF_ID = 1001` parallel zu `PipelineNotificationCoordinator.NOTIF_ID = 0xD1C7A7E` in §7.4 |
| §7.3 onCreate-Snippet rief `ensureNotificationChannel()` | Read Spec 1 §7.3 vor Fix | ❌ Bug bestätigt — §7.3 hatte den Call nicht; nur §11.1.4 erwähnte ihn als "synchron als allererste Aktion" — Doppel-Snippet-Drift |
| §7.2 Bind-Site-Snippet referenzierte `IME-Service onCreate` | Read Spec 1 §7.2 vor Fix | ❌ Bug bestätigt — Kommentar sagte "// IME-Service onCreate (oder beim ersten Recording)" während §11.3.1 explizit `onCreateInputView` mit Latenz-Begründung sagte |
| §7.3 onDestroy-Snippet hatte `runBlocking { withTimeout(...) }`-Wrapper | Read Spec 1 §7.3 vor Fix | ❌ Bug bestätigt — synchroner `orchestrator.shutdown(); serviceScope.cancel()` ohne Timeout; §4.3-KDoc behauptete Timeout-Wrapping aber kein Snippet zeigte ihn |
| §11.3 deckt Pre-Bind-Action-Race (Java-Konsument klickt vor `onServiceConnected`) | Read Spec 1 §11.3.2 + §11.3.3 vor Fix | ❌ Lücke bestätigt — §11.3.3 behauptet "kein UI-Race möglich" auf Basis von Same-Process-Main-Looper, deckt aber nicht Crash-Recovery / `onBindingDied`-Re-Bind |
| Spec 3 §5.7 deklariert `SYSTEM_ALERT_WINDOW` als Manifest-Eintrag | Read Spec 3 §5.7 (Z. 1202-1209) | ✅ verifiziert — Block 6 (Spec 3) deklariert die Permission; Phase-B S-5-Empfehlung: vorab im Block-2-Manifest-Diff landen (kein zweiter Commit) |
| `targetSdk` ist 35 (verlangt FOREGROUND_SERVICE_MICROPHONE für `microphone`-Type) | Plan verweist auf `build.gradle:14` | ✅ verifiziert (siehe Spec 1 §11.1.1 Z. 4243-4244) |
| `OnboardingActivity` existiert für POST_NOTIFICATIONS-Prompt-Site | Manifest Z. 51-54 | ✅ verifiziert — `android:name=".onboarding.OnboardingActivity"`, `android:exported="false"` |

---

## Plan-Edits (Audit-Trail)

| Datei | Sektion | Art | Kurzbeschreibung |
|-------|---------|-----|------------------|
| Spec 1 | §7.2 Bind-Site-Snippet | Refactor | Code-Snippet auf `onCreateInputView` umgestellt; Java-Snippet statt context-freier Kommentar; Cross-Link auf §11.3.1 (F-4) |
| Spec 1 | §7.3 onCreate-Snippet | Refactor | (a) `ensureNotificationChannel()` als erster Schritt nach `super.onCreate()` (F-3); (b) `audioFileFactory` als Member-`lateinit var` deklariert + zugewiesen statt local `val` (F-10); (c) `pipelineOrchestrator: PipelineOrchestrator` (alt) explizit konstruiert, `JobExecutor.initialize(pipelineOrchestrator)` mit FIX-Kommentar (F-5) |
| Spec 1 | §7.3 onDestroy-Snippet | Refactor | `runBlocking { withTimeout(2_000L) { orchestrator.shutdown() } } catch TimeoutCancellationException`-Wrapper; `NotificationManagerCompat.from(this).cancel(...)` als Step 3 Fail-Safe; `companion TAG = "DictatePipelineSvc"` (F-6) |
| Spec 1 | §11.1.1 Manifest-Diff | Add | SYSTEM_ALERT_WINDOW-Cross-Link auf Spec 3 §5.7; konsolidierter Manifest-Diff (Block-2-Permissions + Block-6-SYSTEM_ALERT_WINDOW kombiniert) (F-12) |
| Spec 1 | §11.1.2 companion-Block | Refactor | `NOTIF_ID = 1001` gestrichen; Kommentar verweist auf `PipelineNotificationCoordinator.NOTIF_ID` als SoT (F-2) |
| Spec 1 | §11.1.2 onStartCommand-Block | Refactor | Komplett neu geschrieben — pure Forward an `actionRouter.dispatch(intent)` + `startForegroundCompat(notifCoordinator.buildInitial())` + `notifCoordinator.startReactiveUpdates(...)`; konsistent mit §7.3 SoT; FIX-Kommentar verweist auf pre-F-11-Drift (F-1) |
| Spec 1 | §11.1.2 nach onStartCommand | Add | "NOTIF_ID-Konsolidierung (Phase-B S-5)"-Hinweis-Block mit Begründung der Disjunkt-Kollision (F-2) |
| Spec 1 | §11.1.2 Channel-Setup-Vorspann | Add | Anker-Hinweis: `ensureNotificationChannel()` läuft SYNCHRON in onCreate VOR startForeground (F-3) |
| Spec 1 | §11.2.2 Block 2 Sub-Schritte | Refactor | Sub-Schritte 1-5 → 1-7 erweitert: (1) Service-Skelett mit Stub-Composition-Root, (2) NotificationChannel-Setup als eigener Schritt, (3) Bind-Site `onCreateInputView` (NICHT `onCreate`), (4) Notification+startForeground in onStartCommand, (5) Manifest erweitern + SYSTEM_ALERT_WINDOW vorab, (6) POST_NOTIFICATIONS-Prompt in Onboarding NEU, (7) JobExecutor-Init mit Naming-Konvention-⚠ (F-3, F-4, F-10, F-11) |
| Spec 1 | §11.3.2a NEU | Add | "Pre-Bind-Action-Pfad (Notification-Buttons während Boot)"-Sub-Sektion mit Defensiv-Check für `pipelineBinder == null` + `Toast.makeText(..., R.string.dictate_service_not_ready, ...)` + onStartCommand-Defensive-Check für `!::orchestrator.isInitialized` (F-7) |
| Spec 1 | §11.3.4 NEU | Add | "Multi-Bind-Klärung (Phase-B S-5)"-Sub-Sektion mit Client-Tabelle + LocalBinder-Singleton-Vertrag + stopSelf-Interaktion (F-12) |
| Spec 1 | §11.5.1 POST_NOTIFICATIONS | Refactor | Lifecycle-Tabelle der Permission-States; Prompt-Site 1 (Onboarding ActivityResultLauncher); Prompt-Site 2 (Settings-Activity onResume Re-Prompt); User-Friction-Signal (Banner im Keyboard-View); Block-2-Acceptance-Pointer (F-11) |
| Spec 1 | §4.11.5.1 Sequence-Tabelle | Refactor | Schritt 1.5 (ensureNotificationChannel) + Schritt 9 (startForeground) + Schritt 10 (JobExecutor.initialize mit ⚠-Warnung) explizit verankert (F-3, F-5) |
| Spec 1 | §10 Block-2-Acceptance | Add | 8 neue S-5-Klauseln: Mic-Indikator beim Tastatur-Wechsel, FGS-Killed-by-System (Low-Memory), NotificationChannel-vor-startForeground, FGS-Boot < 5 s, NOTIF_ID-Konsistenz, onDestroy-Timeout, Multi-Bind, Pre-Bind-Action-Toast, POST_NOTIFICATIONS-Prompt (F-2, F-3, F-6, F-8, F-9, F-11, F-12, F-13) |
| Spec 1 | Pflicht-String-Resource | Add | `dictate_service_not_ready` (DE: "Service startet noch — bitte kurz warten.", EN: "Service is starting — please wait a moment.") als Block-2-Aufgabe (F-7) |
| Hauptplan | §9 Iter-Log | Add | Phase-B Quality-Gate S-5 Eintrag (2026-05-13) — 13-Findings-Summary; chronologisch nach S-4 / vor S-7 platziert |

**Gesamt:** 16 Edit-Operationen in 2 Dateien (Spec 1: 15, Hauptplan: 1). Spec 2 + Spec 3 unverändert — S-5 ist Spec-1-Scope (Service-Lifecycle + Bind-Connection + Notification-Coordinator + Action-Router leben dort kanonisch).

---

## Offene Fragen für nachfolgende Agents

### Für S-6 (LayoutCatalog/Render-Backends)

- **POST_NOTIFICATIONS-Banner** im Keyboard-View (F-11) ist ein User-Friction-Signal, das in Block 6 als `BannerSlot`-Predicate implementiert werden soll. S-6 sollte beim Block-5/6-Implementation prüfen, ob `LayoutCatalog` einen Banner-Slot-Typ vorsieht oder ob ein neuer Slot-Typ nötig ist. Predicate: `state.recording !is RecordingState.Idle && !checkSelfPermission(POST_NOTIFICATIONS)`. Click-Action: `Settings.ACTION_APP_NOTIFICATION_SETTINGS`-Intent öffnen.

### Für S-8 (Floating-Overlay-Subsystem)

- **SYSTEM_ALERT_WINDOW** wurde in Block-2-Manifest-Diff (F-12) vorab deklariert — eliminiert einen zweiten Manifest-Commit zwischen Block 2 und Block 6. S-8 sollte beim Block-6-Implementation verifizieren, dass `Settings.canDrawOverlays()` und der `OverlayPermissionPrompt`-Pfad in Spec 3 §5.7 / §11.1 weiterhin korrekt sind — der Manifest-Diff allein reicht nicht, weil Special-Permission den User-Settings-Toggle braucht.

### Für S-9 (ResetSuppressBit-Lifecycle)

- **Tastatur-Wechsel-Survival** (F-8) bedeutet: der `OverlayState.suppressAutoOverlayUntilNextSession`-State überlebt den IME-Tod. Das passt strukturell zur Phase-A-Inventur S-9 (ResetSuppressBit-Lifecycle), aber S-9 sollte beim Block-1b-Implementation explizit verifizieren, dass nach Tastatur-Wechsel + zurück der ResetSuppressBit-Pfad nicht doppelt feuert (z.B. weil das `Idle → Preparing`-Cascade beim Re-Subscribe nochmal erfasst wird). Block-1b-Acceptance `RecordingModuleResetSuppressBitTest.kt` (Spec 3 §14.1) testet das aktuell nur für den initialen Pfad, nicht für Re-Subscribe nach Bind-Recovery.

### Für Phase-B S-6 / S-8 / S-9 — gemeinsam

- **NotificationCoordinator-Cancel-Pfad** (F-2 + F-6): nach S-5 ist klar, dass NOTIF_ID NUR im `PipelineNotificationCoordinator` lebt und der Service in onDestroy `cancel(PipelineNotificationCoordinator.NOTIF_ID)` als Fail-Safe ruft. Wenn ein zukünftiger Subsystem (z.B. eine sekundäre "Pipeline-Done"-Update-Notification im Pipeline-Phasen-Indikator-Pattern) eine zweite Notification-ID braucht, muss diese ebenfalls im Coordinator (oder einem zukünftigen Notification-Registry) leben, NICHT in der konsumierenden Klasse.

### Cross-Cutting

- **`runBlocking`-Timeout-Pattern in Service.onDestroy** (F-6) ist ein wartbares Pattern, das auch für zukünftige Lifecycle-Cleanups (z.B. WorkManager-Replacement-Phase-2) wiederverwendet werden kann. Die 2-s-Cap ist konservativ; Telemetrie sollte erfassen, wie oft Timeouts greifen — wenn > 1 % der onDestroy-Events betroffen sind, ist ein Modul-spezifischer Cleanup-Bug zu suchen.
- **Naming-Konflikt PipelineOrchestrator vs DictateOrchestrator** (F-5 + S-4 F-7) ist im Plan jetzt mehrfach verankert (§1.x Naming-Konvention, §4.11.5.1 Sequence-Schritt 10, §7.3 Snippet, §13.5 G7). Phase-2-Backlog: Umbenennung des alten `PipelineOrchestrator` auf `PipelineRunner` oder `PipelineExecutor` würde die Falle strukturell eliminieren — Hauptplan §7.1 Out-of-Scope-Eintrag (S-4 F-7) ist die Trigger-Klausel.

---

## Findings-Zähler

| Severity | Anzahl |
|---|---|
| Critical | 3 |
| Important | 7 |
| Minor | 3 |
| **Total** | **13** |

**Plan-Edits:** 16 Operations in 2 Dateien (Spec 1: 15, Hauptplan: 1).

**Top-1-Insight:** Die §11.1.2 vs §7.3 Doppel-Snippet-Drift (F-1+F-2+F-3) hätte beim ersten Fresh-Install-User zur ANR-Klasse + Notification-Doppel-Sichtbarkeit + Action-Dispatch-Bruch geführt — alle drei zusammen sind ein Play-Store-Crash-Burst-Risiko. S-1 F-2 hat §7.3 + §11.1.4 + §11.6.1 sauber auf F-11 umgestellt, aber §11.1.2 (die "Implementierung-Detail-Sektion" für FGS-Builder) wurde dabei übersehen — und §11.1.2 ist gerade die Sektion, die ein Block-2-Implementer als detaillierte Code-Vorlage liest. Plus: der Channel-Setup-Order-Bug (F-3) wäre besonders heimtückisch gewesen, weil er **nur beim ersten Fresh-Install** auftritt — alle internen Test-Devices (mit installiertem Channel von vorherigen Builds) hätten den Bug nicht reproduziert. Bug-Klasse: "Spec-Drift produziert Production-Bug, der in Test-Umgebungen unsichtbar ist".

---

**Report-Pfad:** `/home/lukas/WebStorm/Dictate/docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/quality-gate/phase-b-s5-service-layer.md`
