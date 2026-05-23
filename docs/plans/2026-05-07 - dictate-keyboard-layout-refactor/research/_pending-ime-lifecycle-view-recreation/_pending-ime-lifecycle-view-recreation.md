# IME-Lifecycle & View-Recreation — Tiefenrecherche

**Erstellt:** 2026-05-07
**Plan:** `docs/plans/2026-05-07 - keyboard-layout-refactor/keyboard-layout-refactor.md`
**Status:** Recherche (Pending — wartet auf Designentscheidung Variante A+B-Kombi)
**Scope:** Was lebt wann, wie überleben Coroutinen / Background-Jobs eine View-Hidden-Phase oder einen Service-Death im Dictate-IME? Welche Lifecycle-Garantien macht Android, und wie sieht der heutige Zustand im Repo aus?
**Methodik:** AOSP-Doku (developer.android.com, android.googlesource.com) + vollständiger Walkthrough von `DictateInputMethodService.java` und seinen Mitarbeitern (`JobExecutor`, `PipelineOrchestrator`, `RecordingStateController`, `ActiveJobRegistry`).

> **Pending-Marker:** Dieses Dokument ist **Recherche, kein Spec.** Es klärt die Faktenbasis, gibt Empfehlungen zur Scope-Wahl, beschreibt aber nicht "so wird Variante A+B implementiert". Der Spec-Schritt folgt im Block-Plan, sobald Plan-Author die Coroutine-/WorkManager-Strategie endgültig festlegt.

---

## 0. TL;DR

1. **Service-Lifecycle ≠ View-Lifecycle.** Der Service kann minutenlang ohne sichtbares Input-View laufen (App-Wechsel, Background). Das View kann mehrfach pro Service-Leben neu inflated werden (Rotation, Theme, Sprachwechsel).
2. **`onCreateInputView()` wird nicht nur einmal aufgerufen.** Die Doku-Aussage "called once, when the input area is first displayed" ist irreführend — bei Konfigurationsänderungen ruft das Framework `onInitializeInterface()` + `onCreateInputView()` erneut auf. Dictate handhabt das bereits explizit (`cleanupOldControllers()` + Re-Inflate, [DictateInputMethodService.java:401](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)).
3. **Service-Death-Modi:** IME-Switch (User wählt andere Tastatur), Process-Tod (OOM), Force-Stop, Update der App. In allen Fällen: `onDestroy()` *kann* gerufen werden, *muss* aber nicht (OOM!). Persistierung außerhalb des Service-Speichers ist die einzige Garantie.
4. **Heute existiert im IME keine echte Coroutine.** Lifecycle-bound Coroutinen leben nur in Activities (`HistoryDetailActivity`, ActiveJobRegistryObserver). Der IME-Service nutzt klassische `ExecutorService`-Threads (`JobExecutor`, `PipelineOrchestrator`, `dbExecutor`). State wird über `mainHandler.post()` zurück auf den Main-Thread gepostet.
5. **WorkManager ist nicht im Projekt.** `androidx.work` ist nicht in `gradle/libs.versions.toml` enthalten — eine A+B-Kombi-Variante mit WorkManager-Persistenz erfordert eine neue Dependency + Initialisierung in `DictateApplication`.
6. **Was den View-Hidden-Phase überlebt (heute):** alles was in `onCreate`-initialisiert wird (Service-Member, `JobExecutor`-Singleton, `ActiveJobRegistry`-Singleton, Room-DB). Was zerstört wird: View-Member (Buttons, Controllers, Listener-Registrierungen).
7. **Was Service-Death NICHT überlebt:** alle `ExecutorService`-Threads in `JobExecutor` und `PipelineOrchestrator` sterben mit dem Prozess. State, der noch nicht in Room geschrieben ist, ist verloren. Das ist der eigentliche Anlass für Variante A+B.

---

## A. AOSP-Lifecycle-Garantien

### A.1 Quellen

- **Reference (`InputMethodService`):**
  https://developer.android.com/reference/android/inputmethodservice/InputMethodService
- **Guide ("Creating an Input Method"):**
  https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method
- **AOSP-Quelle:**
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/inputmethodservice/InputMethodService.java

> **Hinweis:** `developer.android.com/reference/...` rendert die Klassen-Doku als JS-SPA — direktes Fetchen liefert oft nur Navigation. Die belastbaren Zitate stammen aus AOSP-Source und der Guide-Seite. Die Aussagen unten sind ggf. mit AOSP-Source verifizierbar; ein Spot-Check in `frameworks/base/.../InputMethodService.java` (master/HEAD) ist beim Spec-Schritt sinnvoll.

### A.2 Callbacks und Reihenfolge

Die zentralen Lifecycle-Callbacks der `InputMethodService`:

| Callback | Phase | Wann |
|----------|-------|------|
| `onCreate()` | Service | Einmal pro Service-Instanz, ganz am Anfang |
| `onInitializeInterface()` | Service | Einmal nach `onCreate()`, **erneut** bei jeder Configuration Change |
| `onBindInput()` | Client | Wenn ein Client (App) sich mit der IME verbindet — neue InputConnection |
| `onUnbindInput()` | Client | Wenn der Client-Bind endet |
| `onStartInput(EditorInfo, restarting)` | Editor | Wenn Cursor in ein neues EditText geht — VOR View-Show |
| `onCreateInputView()` | View | Wenn das Input-View aufgebaut werden muss — initial **und** nach Config-Change |
| `onCreateCandidatesView()` | View | Optional, wenn Candidates-Strip gebraucht wird (Dictate nutzt das nicht) |
| `onStartInputView(EditorInfo, restarting)` | View | View ist aufgebaut, wird gleich gezeigt — IMMER nach `onStartInput` |
| `onWindowShown()` | Window | Window wird sichtbar (Animation in) |
| `onWindowHidden()` | Window | Window verschwindet (Animation out) |
| `onFinishInputView(finishingInput)` | View | View wird verborgen (App-Switch, Back, Editor-Wechsel) |
| `onFinishInput()` | Editor | Editor-Session endet |
| `onConfigurationChanged(Configuration)` | Service | Bei Rotation, Theme, Sprache (Default-Verhalten s. unten) |
| `onDestroy()` | Service | Service wird zerstört — **nicht garantiert** bei Process-Death |

**AOSP-Zitat (Guide, "Creating an Input Method"):**
> "When the IME is displayed for the first time, the system calls the `onCreateInputView()` callback."
>
> "When an input field receives focus and your IME starts, the system calls `onStartInputView()`."
>
> "Release large memory allocations immediately after the input method window is hidden, so that applications have sufficient memory to run. Use a delayed message to release resources if the IME is hidden for a few seconds."
> — https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method

**AOSP-Zitat (Reference, `onStartInputView`):**
> "Called when the input view is being shown and input has started on a new editor. This will always be called after `onStartInput`, allowing you to do your general setup there and just view-specific setup here."

**AOSP-Zitat (Reference, `onFinishInputView`):**
> "Called when the input view is being hidden from the user. This will be called either prior to hiding the window, or prior to switching to another target for editing."

**AOSP-Zitat (Reference, Configuration-Change-Handling):**
> "When a configuration change does happen, `onInitializeInterface()` is guaranteed to be called the next time prior to any of the other input or UI creation callbacks. The following will be called immediately depending if appropriate for current state: `onStartInput` if input is active, and `onCreateInputView` and `onStartInputView` and related appropriate functions if the UI is displayed."

### A.3 Reihenfolge bei typischen User-Flows

```
┌─────────────────────────────────────────────────────────────────────┐
│  Service-Lifetime                                                   │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │ onCreate()                                                     │ │
│  │ onInitializeInterface()                                        │ │
│  │                                                                │ │
│  │ ┌─── User tappt EditText (App A) ───────────────────────────┐  │ │
│  │ │ onBindInput()                                             │  │ │
│  │ │ onStartInput(editorA, restarting=false)                   │  │ │
│  │ │ onCreateInputView()           ← View build                │  │ │
│  │ │ onCreateCandidatesView() (optional)                       │  │ │
│  │ │ onStartInputView(editorA, restarting=false)               │  │ │
│  │ │ onWindowShown()                                           │  │ │
│  │ │   .....   User tippt   .....                              │  │ │
│  │ │ onFinishInputView(finishingInput=true)                    │  │ │
│  │ │ onFinishInput()                                           │  │ │
│  │ │ onWindowHidden()                                          │  │ │
│  │ │ onUnbindInput()                                           │  │ │
│  │ └───────────────────────────────────────────────────────────┘  │ │
│  │                                                                │ │
│  │ ┌─── User wechselt EditText INNERHALB derselben App ───────┐   │ │
│  │ │ onFinishInputView(finishingInput=false)                  │   │ │
│  │ │ onStartInput(editorB, restarting=true)                   │   │ │
│  │ │ onStartInputView(editorB, restarting=true)               │   │ │
│  │ │ (Window bleibt sichtbar, View wird NICHT neu gebaut)     │   │ │
│  │ └──────────────────────────────────────────────────────────┘   │ │
│  │                                                                │ │
│  │ ┌─── Rotation (Configuration-Change) ──────────────────────┐   │ │
│  │ │ onConfigurationChanged(newConfig)                        │   │ │
│  │ │ onInitializeInterface()                                  │   │ │
│  │ │ onCreateInputView()           ← View NEU gebaut          │   │ │
│  │ │ onStartInputView(editor, restarting=true)                │   │ │
│  │ │ (Service lebt weiter, alle Service-Member bleiben)       │   │ │
│  │ └──────────────────────────────────────────────────────────┘   │ │
│  │                                                                │ │
│  │ ┌─── User wechselt App (Recents, Home) ────────────────────┐   │ │
│  │ │ onFinishInputView(finishingInput=true)                   │   │ │
│  │ │ onWindowHidden()                                         │   │ │
│  │ │ (Service lebt — Background ist offen)                    │   │ │
│  │ └──────────────────────────────────────────────────────────┘   │ │
│  │                                                                │ │
│  │ onDestroy()       ← Service-Tod (oder Process-Death OHNE)      │ │
│  └────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

### A.4 Was überlebt was

| Ereignis | Service-Member | View-Member | Background-Threads | Persistenter State |
|----------|:-------------:|:-----------:|:------------------:|:------------------:|
| `onFinishInputView()` (App-Switch) | bleiben | bleiben (View bleibt im Speicher, nur unsichtbar) | bleiben | bleibt |
| `onConfigurationChanged()` (Rotation) | bleiben | **neu gebaut** durch erneutes `onCreateInputView()` | bleiben | bleibt |
| IME-Switch (User → andere Tastatur) | **`onDestroy()` wird gerufen** | gone | sterben mit Service | bleibt |
| Process-Death (OOM, Force-Stop) | gone | gone | gone (Threads sterben mit Process) | **nur DB / SharedPrefs / Files** |
| App-Update | wie Process-Death | gone | gone | bleibt (Files, DB) |

**Wichtige Nuance — `onDestroy()` ist nicht garantiert:**
Bei Process-Death (OOM-Killer, manuelles Force-Stop, Crash) wird `onDestroy()` **nicht** aufgerufen. Das Framework garantiert nur, dass `onDestroy()` *vor* einem geordneten Service-Stop läuft. Cleanup-Code, der nur in `onDestroy()` läuft, ist deshalb keine Persistenz-Strategie — er ist Best-Effort-Cleanup im Happy-Path.

**Konfigurationsänderungen — Default vs. `android:configChanges`:**
Im Manifest gibt es **kein** `android:configChanges`-Attribut auf dem `<service>` ([AndroidManifest.xml:30-38](app/src/main/AndroidManifest.xml)). Das heißt: das Framework wendet das Default-Verhalten an — `onConfigurationChanged()` wird gerufen, der Service stirbt **nicht**, aber das InputView wird im nächsten Show-Zyklus durch `onCreateInputView()` neu aufgebaut. Dictate verlässt sich darauf bewusst (View-Recreation als First-Class-Pfad).

---

## B. Konkrete Lifecycle-Nutzung in Dictate

### B.1 Service-Klasse

**Pfad:** [`app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) (2697 Zeilen)
**Manifest:** [`app/src/main/AndroidManifest.xml:30-44`](app/src/main/AndroidManifest.xml) — Standard-IME-Deklaration, kein `configChanges`-Override.

### B.2 Überschriebene Lifecycle-Callbacks

| Callback | Datei:Zeile | Verantwortlichkeit |
|----------|-------------|---------------------|
| `onCreate()` | [DictateInputMethodService.java:323-327](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) | Long-lived-Init via `initLongLivedObjects()` |
| `onCreateInputView()` | [DictateInputMethodService.java:400-746](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) | View-Inflate + alle View-gebundenen Controller bauen + Callbacks rewiren + State restore |
| `onStartInputView(info, restarting)` | [DictateInputMethodService.java:1307-1416](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) | Theme/Color, Prompts neu laden, BluetoothSCO-Receiver registrieren, BT-Wakeup, Pause-Timeout cancel, Auto-Recording-Trigger |
| `onFinishInputView(finishingInput)` | [DictateInputMethodService.java:749-783](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) | 3-State-Logik: (A) Recording aktiv → Pause+Timeout, (B) Pipeline läuft → nur Panels schließen, (C) Idle → Full-Cleanup |
| `onUpdateSelection(...)` | [DictateInputMethodService.java:1419-1427](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) | Re-Render der Prompt-Adapter-Buttons je nach Selection |
| `onDestroy()` | [DictateInputMethodService.java:785-822](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) | Cleanup: pipelineOrchestrator.shutdown(), BT-Receiver, Room-Observer, languageController.dispose(), Prefs-Listener, recordingStateController.onDestroy() |

**Nicht überschrieben** (Dictate verlässt sich auf Default):
`onInitializeInterface`, `onBindInput`, `onUnbindInput`, `onStartInput`, `onCreateCandidatesView`, `onWindowShown`, `onWindowHidden`, `onConfigurationChanged`, `onEvaluateFullscreenMode`, `onEvaluateInputViewShown`, `onComputeInsets`.

### B.3 Wo werden Buttons / Layouts initialisiert?

**Hypothese aus Plan:** in `onCreateInputView()`. **Verifiziert:** ja.

- `findViewById`-Pass: [DictateInputMethodService.java:422-498](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)
- View-gebundene Controller-Konstruktion: [DictateInputMethodService.java:500-619](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)
  - `InfoBarController` (500)
  - `KeyboardStateManager` (530) — SSOT für Visibility
  - `KeyboardUiController` (544)
  - `MainButtonsController` (559) — registriert alle Click-Listener
  - `KeyboardLayoutModeController` (588) — Two-Row/Single-Row-Switching
  - `LanguageController` (634)
  - `RecordingUiController` (607)
- `cleanupOldControllers()` (Vorlauf): [DictateInputMethodService.java:833-904](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) — explizites Disposal des alten View-Controller-Sets vor dem Neuaufbau (deckt den Re-Inflate-Pfad ab).
- `rewireCallbacks()` (Bridge): [DictateInputMethodService.java:910-…](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) — verbindet die long-lived Service-Member mit dem frischen View-Controller-Set.

### B.4 Wo werden Coroutine-Scopes erzeugt?

**Im IME-Service: nirgends.** Suche `CoroutineScope|SupervisorJob|GlobalScope|MainScope|lifecycleScope` über `app/src/main/java/`:

- `app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistryObserver.kt:5,35` — `lifecycleScope` (von `LifecycleOwner`, also Activity/Fragment-Kontext, **nicht IME**).

Das heißt: der IME nutzt heute keine Kotlin-Coroutinen für Background-Arbeit. Die einzigen Coroutine-Berührungspunkte:

1. `ActiveJobRegistry` exposed einen `StateFlow<Map<String, JobState>>` ([ActiveJobRegistry.kt:31](app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt)).
2. `ActiveJobRegistryObserver` collected diesen Flow — aber **gebunden an einen `LifecycleOwner`**, also nur in Activities (HistoryActivity etc.), nicht im IME.

**Wer cancelt wann (heute, ohne Coroutine):**

- `JobExecutor` ([JobExecutor.kt:33](app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt)) hält einen `singleThreadExecutor`. Cancel via cooperative `CancellationToken` + `Thread.interrupt()` ([JobExecutor.kt:184-187](app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt)). **Kein Cancel bei View-Hidden** — der Job läuft weiter.
- `PipelineOrchestrator` ([PipelineOrchestrator.kt:145](app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt)) hält selbst einen `ExecutorService`. `cancel()` ([PipelineOrchestrator.kt:780](app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt)) macht `shutdownNow()` und ersetzt den Executor durch einen frischen — wird in `onFinishInputView()` State (C) gerufen ([DictateInputMethodService.java:771](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)), aber **nicht** in State (A) und (B), wo die Pipeline weiterleben darf.
- `RecordingStateController` ([RecordingStateController.kt:233](app/src/main/java/net/devemperor/dictate/core/RecordingStateController.kt)) reagiert auf View-Hidden (`onKeyboardHidden`) mit Auto-Pause + 60s-Timeout, **ohne** den Recording-Thread zu killen.
- `dbExecutor` ([DictateInputMethodService.java:144](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)) — Service-Member, kein expliziter Shutdown. Stirbt mit dem Service-Prozess.
- `mainHandler` ([DictateInputMethodService.java:331](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)) — postet von Worker-Threads zurück auf den Main-Thread. `removeCallbacks(reloadPromptsRunnable)` in `onDestroy()` ([Z. 789](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)), sonst keine systematische Cancellation.

### B.5 Gibt es schon Background-Job-Logik?

- **WorkManager:** **Nicht vorhanden.** `androidx.work` fehlt in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) und in [`app/build.gradle`](app/build.gradle). Eine Variante mit WorkManager-Persistenz erfordert eine neue Dependency.
- **`JobExecutor` + `ActiveJobRegistry`** ist die heutige Background-Job-Abstraktion: process-wide Singleton, einzige aktive Session, cooperative Cancel, `StateFlow` für UI-Beobachter ([JobExecutor.kt](app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt) und [ActiveJobRegistry.kt](app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt)).
- **`PipelineOrchestrator`** ([PipelineOrchestrator.kt](app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt)) — die eigentliche Pipeline (Transcription → Steps → Insertion). Hält eigenen Executor, `*Blocking`-Methoden werden vom `JobExecutor`-Thread aufgerufen.
- **`SessionManager` + `SessionTracker`** — DB-Persistenz der Pipeline-Session inkl. Status-Übergänge ([SessionManager.kt](app/src/main/java/net/devemperor/dictate/core/SessionManager.kt), [SessionTracker.kt](app/src/main/java/net/devemperor/dictate/core/SessionTracker.kt)). Schreibt nach jedem Step → Crash-Recovery via DB-Status ist möglich.

**Zwischenfazit:** Die "Singleton-Process-Job"-Architektur (JobExecutor + ActiveJobRegistry + StateFlow) ist eine bewusste Vor-Coroutine-Variante. Sie ist tragfähig für den View-Hidden-Pfad (Service lebt weiter), versagt aber bei echtem Process-Death — alles, was nicht in Room steht, ist weg.

---

## C. Coroutine-Scope-Strategie für Background-Send (für die geplante Variante A+B)

### C.1 Service-scoped vs. View-scoped

**View-scoped (z.B. `viewLifecycleOwner.lifecycleScope` aus Fragment-Welt):** Der Scope wird gecancelt, sobald das View weg ist. Im IME wäre das Pendant ein Scope, der bei `onFinishInputView()` oder spätestens `onCreateInputView()`-Re-Inflate gecancelt wird. Konsequenz: ein laufender Background-Send würde abbrechen, sobald der User die App wechselt — genau das, was Variante A vermeiden will.

**Service-scoped (eigener `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, gecancelt in `onDestroy()`):** Der Job lebt so lange wie der Service. App-Switch / Rotation tangieren ihn nicht; nur IME-Switch und Process-Death killen ihn. **Das ist die richtige Granularität für Variante A** ("Service-lokale Coroutine").

**Pseudo-Pattern für Variante A** (nicht-implementiert, nur Skizze für Review):

```
class DictateInputMethodService : InputMethodService() {
    // SupervisorJob: ein Child-Failure killt nicht die Geschwister
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() { super.onCreate(); /* ... */ }

    override fun onDestroy() {
        serviceScope.cancel()  // wirft CancellationException in alle laufenden Coroutinen
        super.onDestroy()
    }

    fun startBackgroundSend(req: ...) = serviceScope.launch(Dispatchers.IO) {
        // bleibt am Leben über View-Hidden, stirbt mit Service
    }
}
```

### C.2 Was passiert mit der Coroutine bei View-Hidden, wenn der Scope view-gebunden war?

**Antwort:** Sie wird gecancelt — `kotlinx.coroutines.CancellationException` wird gewirft, alle suspendierenden Operationen brechen ab, `withContext`/`launch`-Children werden gecancelt. Die OkHttp-Calls in den AI-Runnern brechen entweder durch Cancel-Check oder durch IO-Interrupt.

Genau dieses Verhalten ist im heutigen `PipelineOrchestrator.cancel()` ([Z. 780-791](app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt)) per `executor.shutdownNow()` simuliert — die Threads bekommen `Thread.interrupt()`, OkHttp wirft `InterruptedIOException` ([Z. 307](app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt)). Bei einer view-scoped Coroutine wäre das Verhalten identisch — und genau deswegen ist View-Scope für den Send-Pfad falsch.

### C.3 Empfohlene Praxis für "Job läuft auch wenn View weg ist, aber nur solange Service lebt"

1. `serviceScope` in `onCreate()` initialisieren.
2. In `onDestroy()` `serviceScope.cancel()` aufrufen.
3. Long-running Operationen mit `serviceScope.launch(Dispatchers.IO) { ... }` starten — **nicht** mit `lifecycleScope` oder `viewLifecycleOwner.lifecycleScope` (gibt es im IME ohnehin nicht ohne `androidx.lifecycle.LifecycleService`-Bridge).
4. **Achtung Process-Death:** Reine Service-Scopes lösen das Problem "Process stirbt während Send" nicht. Dafür braucht es Variante B (WorkManager + DB-Persistenz).
5. **Lifecycle-Coroutine-Bridge (optional):** Wenn man `lifecycleScope` im IME-Service nutzen will, ist `androidx.lifecycle.LifecycleService` als Basisklasse nötig — das ist aber inkompatibel mit `InputMethodService` (kein Mehrfach-Erben in Java). Praktisch: Custom-Scope wie oben.

### C.4 StateFlow / SharedFlow — wo gehört der Owner hin?

**Anforderung:** UI im IME-View konsumiert den Flow, View kann zerstört werden, Flow-Owner darf das nicht zerstören.

| Lebensdauer | Owner | Beispiel |
|-------------|-------|----------|
| Process-wide | Singleton-Object | `ActiveJobRegistry.state` ([ActiveJobRegistry.kt:31](app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt)) — heute schon so umgesetzt |
| Service-Lifetime | Service-Member | gut für IME-spezifischen UI-State, der über View-Recreate hinaus persistieren soll, aber nicht über Service-Death (z.B. "letzter eingeblendeter InfoBar-Text") |
| View-Lifetime | View-Controller-Member | gut für View-interne Animationen, die mit dem View sterben dürfen |

**Konsumieren von Flows aus dem IME-View:** Der View hat keinen `LifecycleOwner` (anders als Activities/Fragments). Wer aus `onCreateInputView()` heraus einen Flow collected, muss explizit den Job in `cleanupOldControllers()` cancellen. Pseudo:

```
// Im IME-Service
private var viewCollectorJob: Job? = null

override fun onCreateInputView(): View {
    cleanupOldControllers()  // canceled u.a. viewCollectorJob
    // ...
    viewCollectorJob = serviceScope.launch {
        ActiveJobRegistry.state.collect { snapshot -> renderJobBadge(snapshot) }
    }
    return root
}

private fun cleanupOldControllers() {
    viewCollectorJob?.cancel(); viewCollectorJob = null
    // ...
}
```

Das ist konsistent mit dem heutigen Pattern, in dem View-gebundene Listener (`servicePipelineCallback`, `inputLanguagesListener`, `audioFocusListener`) explizit in `cleanupOldControllers()` deregistriert werden ([Z. 856-885](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)). Ein Coroutine-Job-Cancel reiht sich symmetrisch ein.

---

## D. WorkManager im IME-Kontext

### D.1 Aktueller Status

- Dependency `androidx.work` ist **nicht** im Projekt ([`gradle/libs.versions.toml`](gradle/libs.versions.toml), [`app/build.gradle`](app/build.gradle)). Variante B erfordert das Hinzufügen.
- Es existiert kein `WorkManager`-spezifischer Kontextcode (Initialisierer, Configuration-Provider, Worker-Klassen).

### D.2 Limitierungen WorkManager im IME-Service

- **WorkManager läuft im selben Prozess wie die App / der IME-Service.** Bei IME-Switch wird der IME-Prozess beendet — laufende Worker werden gecancelt und vom Framework gemäß ihrer `WorkManager`-Constraints + Retry-Policy neu gescheduled (das ist genau der gewünschte Effekt für Variante B).
- **Konstruktor-Init:** WorkManager nutzt seit `androidx.work:2.6.0` `androidx.startup.Initializer` per Default. In einem IME-Projekt ohne Custom-Initializer reicht es, die Dependency zu adden — Default-Init via Manifest-Provider greift. Custom-Configuration (Logging, Custom-WorkerFactory für DI) wäre über `Configuration.Provider` an `DictateApplication` gehängt.
- **Aufruf aus IME-Service:** `WorkManager.getInstance(context).enqueueUniqueWork(...)` ist aus jedem `Context` heraus zulässig — der IME-Service ist ein Context. Keine bekannte IME-spezifische Sperre.
- **Quotas (App Standby Buckets, Doze):** Auf modernen Android-Versionen können Worker durch Doze-Mode pausiert werden, wenn das Device idle ist und der Worker nicht `expedited` ist. Für einen "Send the last transcription"-Job ist `expedited = true` mit `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` der typische Pfad.

### D.3 WorkManager-Job → View-Status-Propagation

**Empfohlenes Pattern:**

```
WorkManager-Worker            DB (Room)             Repository (Flow)        View (Collector)
──────────────────            ─────────            ─────────────────         ────────────────
   doWork() {                                       fun observe(): Flow
     updateProgress(...)                            = dao.observeFlow()
     dao.update(...)    ─→   sessions(status)  ─→
     ...                                                                 ─→  collect { render }
   }
```

- Der Worker schreibt **DB-Updates** (Room mit `@Query Flow<...>`).
- Repositories exposed `Flow<...>` aus den DAO-Queries.
- Der IME-View collected den Flow im `serviceScope` (oder einem View-Collector-Job, der in `cleanupOldControllers()` gecancelt wird).
- Vorteile: Worker bleibt UI-agnostisch, mehrere Konsumenten möglich (IME-View, HistoryActivity), Crash-Recovery automatisch (DB überlebt Process-Death).

**Alternativen, die in dieser Architektur schlecht passen:**

- `WorkInfo.observe(LifecycleOwner)` — braucht LifecycleOwner; im IME-View nicht idiomatisch ohne `LifecycleService`-Hack.
- `LiveData<WorkInfo>` ohne `LifecycleOwner` — `observeForever()` ist möglich, aber explizit `removeObserver()` zwingend, sonst Leak. DB-Flow ist sauberer.

### D.4 A+B-Kombi-Skizze (Empfehlung für Plan-Author)

| Pfad | Technik | Owner |
|------|---------|-------|
| Recording aktiv → User wechselt App | `RecordingStateController.onKeyboardHidden()` (heute schon) — Auto-Pause + 60s-Timeout, weil Mic-Stream ohne Visualisierung sinnfrei | Service |
| Send (Pipeline) läuft → User wechselt App | Variante A: `serviceScope.launch` — bleibt Service-lebenslang aktiv | Service |
| Send (Pipeline) läuft → Process-Death-Risiko | Variante B: dieselbe Operation als `WorkManager.enqueueUniqueWork(workName=sessionId, REPLACE)` — bei Process-Death wird der Worker neu gescheduled. State liegt in der `sessions`-Tabelle | WorkManager + Room |
| View kommt zurück (Service lebt) | View collected `dao.observeSession(sessionId): Flow` — sieht aktuellen Status | View-Collector-Job in serviceScope |
| View kommt zurück (Service tot, Process-Death) | View collected denselben Flow → sieht aktuellen DB-Status (entweder `RUNNING` durch Worker oder `FAILED`/`CANCELLED` mit Recovery-Affordance) | View-Collector-Job in neuem serviceScope |

**Single-Source-of-Truth bleibt die DB.** Variante A (Coroutine) sorgt nur dafür, dass der Happy-Path im selben Prozess weiterläuft. Variante B (WorkManager) sorgt dafür, dass nach Process-Death das System einen neuen Versuch macht. Beide schreiben über denselben `SessionManager`/`SessionTracker`-Pfad in dieselbe `sessions`-Tabelle, sodass die View-Collector-Logik nicht zwischen "lokal" und "wiedergeholt" unterscheiden muss.

---

## E. User-Flow-Mapping (Ist-Zustand vs. Soll-Zustand)

### Flow A — Recording läuft, User wechselt App

**JETZT** ([DictateInputMethodService.java:756-762](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) + [RecordingStateController.kt:233-247](app/src/main/java/net/devemperor/dictate/core/RecordingStateController.kt)):
- `onFinishInputView()` State (A): `recordingStateController.onKeyboardHidden()` → `togglePause()` + `startPauseTimeout()` (60 s default).
- BluetoothSCO wird released, AudioFocus abandoned, Keep-Screen-Awake ausgeschaltet.
- ContentArea wechselt auf `MAIN_BUTTONS` (Cleanup).
- Service lebt weiter, Recording-Thread (im RecordingManager) läuft im Pause-State.

**SOLL** (laut Plan-Variante A+B): unverändert — Recording bleibt pausiert, Auto-Stop nach 60 s ist gewollt. Recording ohne Mic-Visualisierung im Background ist eine UX-Falle (User vergisst, dass es noch läuft).

### Flow B — Send läuft, User wechselt App

**JETZT** ([DictateInputMethodService.java:764-768](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)):
- `onFinishInputView()` State (B): `pipelineOrchestrator.isRunning() == true` → nur ContentArea auf `MAIN_BUTTONS` setzen, Pipeline läuft auf eigenem Thread weiter.
- Keine Cancellation, keine Persistenz-Mutation.
- Ergebnis (Insertion) erfolgt via `getCurrentInputConnection()` — falls der User in der Zwischenzeit die App gewechselt hat, ist die InputConnection ggf. **nicht mehr gültig** (anderer EditorInfo). Insertion kann silently fehlschlagen oder im falschen Editor landen.
- Beim Process-Death (selten, aber möglich): Pipeline-Thread stirbt, `sessions`-Eintrag bleibt in `RUNNING`-Status hängen → "Stale-Running"-Bug.

**SOLL** (Plan):
- Send weiterhin Background-tauglich (heute schon korrekt für den Service-lebt-Pfad).
- Variante A (serviceScope) macht das semantisch sauberer — keine eigenen Executor-Lifecycles mehr, einheitlicher Cancel-Pfad.
- Variante B (WorkManager) deckt Process-Death ab: nach Restart sieht der View über DB-Flow, dass die Session wieder anläuft (oder gemäß Retry-Policy in `FAILED` gegangen ist).
- InputConnection-Capture: muss schon **vor** dem `onFinishInputView()` festgehalten werden (heute teilweise via `InsertionSource` + Fallback-Strategien in `ResendInsertStrategy`/`ResendStatusDispatcher` umgesetzt — Detail-Audit beim Spec-Schritt nötig).

### Flow C — User kommt nach 30 s zurück (Service lebt noch)

**JETZT:**
- `onCreateInputView()` läuft erneut **nicht** (View war im Speicher, nur Window verborgen).
- Genauer: bei reinem App-Switch ohne Config-Change wird beim nächsten EditText-Tap `onStartInput()` + `onStartInputView()` gerufen. Das View-Objekt bleibt gleich, falls Framework es nicht zwischenzeitlich verworfen hat. (In der Praxis: das Framework verwirft das InputView nach längerer Inaktivität; bei kurzer Pause meist nicht.)
- Falls View neu inflated: Plan-spezifischer State (laufender Pipeline-Status, RecordingState) wird in `restoreUiState()` ([DictateInputMethodService.java:733-734](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) + Helper) wiederhergestellt aus Service-Member.
- Falls View nicht neu inflated: `onStartInputView()` ([Z. 1307](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)) re-applied Theme, lädt Prompts, registriert BT-Receiver.
- User sieht: laufenden Pipeline-Status (Progress-Steps), oder ggf. das fertige Ergebnis im EditText, falls die Pipeline abgeschlossen hat während die View weg war.

**SOLL:**
- Verhalten bleibt korrekt; nach Variante A+B: zusätzlich klarer Recovery-Pfad, falls Pipeline während Hidden in `FAILED`/`CANCELLED` ging — Notification/InfoBar-Affordance ist wünschenswert (aktuell teilweise via `InfoBarController.showInfo(...)`).

### Flow D — User kommt nach 5 min zurück (Service evtl. tot)

**JETZT:**
- Wenn Service tot ist (IME-Switch, OOM, Force-Stop, Update): neuer Service-Prozess startet bei nächstem EditText-Tap.
- `onCreate()` → `initLongLivedObjects()` → frische Singletons, `JobExecutor` ist wieder `EMPTY`, `ActiveJobRegistry.state.value` ist leer Map.
- `onCreateInputView()` baut frische View.
- DB enthält die Session aus dem alten Prozess — aber im Status, in dem sie zum Zeitpunkt des Process-Death war (z.B. `RUNNING`, weil das Terminal-Update nicht mehr durchkam).
- User sieht: keine laufende Pipeline-UI (`ActiveJobRegistry` ist leer), kein Progress-Strip. Die Session in der DB ist effektiv zombiehaft — ohne Wiederbelebungs-Logik kein Recovery.
- HistoryActivity zeigt die Session ggf. als `RUNNING` oder mit einem Stale-Marker (Detail in [SessionManager.kt](app/src/main/java/net/devemperor/dictate/core/SessionManager.kt) — nicht im Scope dieser Recherche).

**SOLL** (mit Variante A+B):
- Process-Tod während laufendem Send → WorkManager hat den Job persistent gequeued (Variante B). Der nächste IME-Start (oder ein anderer App-Trigger) löst den Worker, der die Pipeline aus dem `sessions`-Zustand resumed (vorausgesetzt, die Pipeline ist resume-fähig — das ist sie heute teilweise, siehe `JobRequest.Resume` und `PipelineOrchestrator.resumePipelineBlocking`).
- Beim nächsten View-Bau collected der View den Repository-Flow → sieht entweder den laufenden Worker-Status oder den finalisierten Erfolgs-/Fehlerzustand.
- Stale-Running-Detection: beim Service-`onCreate()` einen Cleanup-Pass über die `sessions`-Tabelle — alle `RUNNING` aus früherem Prozess als `INTERRUPTED` markieren oder per Worker resumen. Dieser Pass ist heute nicht implementiert und wäre Teil der Spec.

---

## F. Was diese Recherche an Plan-Aussagen härtet oder hinterfragt

| Plan-Aussage | Status |
|--------------|--------|
| "IME `onCreateInputView`: inflate Layout → konstruiere Controllers" ([keyboard-layout-refactor.md:52](docs/plans/2026-05-07%20-%20keyboard-layout-refactor/keyboard-layout-refactor.md)) | **Verifiziert.** Heute genau so umgesetzt, [DictateInputMethodService.java:401-746](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java). |
| "IME `onFinishInputView` / `onDestroy`: clearLayoutModeController, lifecycle-cleanup" ([keyboard-layout-refactor.md:53](docs/plans/2026-05-07%20-%20keyboard-layout-refactor/keyboard-layout-refactor.md)) | **Teilweise.** `onFinishInputView` macht heute *nicht* `clearLayoutModeController` — der Layout-Controller bleibt am View hängen, bis der nächste `cleanupOldControllers()` ihn nullt. Der Plan-Vorschlag ist eine Sauberkeits-Verschärfung, nicht der Ist-Stand. |
| "View-Recreate ist First-Class-Pfad" (Plan-Tenor) | **Verifiziert.** `cleanupOldControllers()` ([Z. 833](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)) + `rewireCallbacks()` ([Z. 910](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)) + `restoreUiState()` ist explizit auf das Re-Inflate-Szenario ausgelegt. |
| Variante A "Service-lokale Coroutine" | **Greenfield.** Heute keine Coroutinen im IME — Variante A führt das Coroutine-Pattern in den IME ein. SupervisorJob + `serviceScope.cancel()` in `onDestroy()` ist die Standard-Form. |
| Variante B "WorkManager + DB-Persistenz" | **Greenfield.** Dependency fehlt; muss in `gradle/libs.versions.toml` ergänzt werden. DB-Persistenz ist via Room+SessionManager schon vorhanden, der Worker-Wrapper und der Resume-Trigger sind neu zu implementieren. |
| "DB-Persistenz" als Variante-B-Bestandteil | **Halb-greenfield.** Pipeline-Sessions werden bereits persistiert ([SessionManager.kt](app/src/main/java/net/devemperor/dictate/core/SessionManager.kt)); was fehlt: ein "Stale-Running-Cleanup"-Pass und ein "Resume-on-Service-Start"-Trigger. |

---

## G. Offene Fragen für den Spec-Schritt

1. **Stale-Running-Recovery-Strategie:** Beim IME-`onCreate()` über die `sessions`-Tabelle iterieren — alle `RUNNING` aus früheren Prozessen entweder als `INTERRUPTED` markieren oder über `JobExecutor.start(JobRequest.Resume(...))` reanimieren? Letzteres ist nur sinnvoll, wenn Variante B aktiv ist.
2. **Worker-Granularität:** Ein Worker pro Session vs. ein "Drain-Queue"-Worker, der alle pending Sessions abarbeitet? Letzteres ist robuster bei mehrfachen Sends in Folge.
3. **Expedited Worker:** `setExpedited(...)` für die Send-Operation? Praktisch ja, aber das Quota-Verhalten ist Hersteller-spezifisch — Tests auf realer Hardware sind nötig.
4. **InputConnection nach Service-Death:** kann nicht wieder hergestellt werden. Bei Process-Death während Send muss das Ergebnis entweder in die Clipboard, in die History (vorhanden) oder als Notification surfacen — Plan-Author entscheidet, was die User-Affordance ist.
5. **Coroutine-Migration vs. Co-Existenz:** Variante A ersetzt nicht zwingend den `JobExecutor`-Singleton. Ein hybrider Pfad ("Coroutine als neue Schicht über `JobExecutor.start`") hält die Cancellation-Semantik einheitlich. Migration komplett zu Coroutinen ist eigenes Block-Theme.
6. **Process-Wide-Singletons (`JobExecutor`, `ActiveJobRegistry`) bei Process-Death:** beide werden mit dem Process neu instantiiert. Ein "Reattach"-Pfad nach Process-Restart fehlt heute komplett — Variante A+B muss das explizit modellieren.

---

## H. Glossar (Kurzreferenz)

| Begriff | Bedeutung |
|---------|-----------|
| **IME** | Input Method Editor — eine Tastatur-App. |
| **Service** | Hier: die `InputMethodService`-Subklasse, also der Prozess, der die Tastatur stellt. |
| **Input-View** | Das eigentliche Tastatur-Layout (in Dictate: `activity_dictate_keyboard_view.xml` inflated in `onCreateInputView`). |
| **Window** | Das Android-Window, in das das Input-View gepackt wird. Sichtbarkeit via `onWindowShown`/`onWindowHidden`. |
| **Editor / Client** | Die App, die ein Textfeld zur Eingabe bereitstellt. Verbindung über `InputConnection`. |
| **Service-Death** | Der IME-Prozess wird beendet (User-Action oder System-Action). |
| **Process-Death** | Untermenge von Service-Death: Process wird ohne `onDestroy()`-Aufruf hart terminiert (OOM, Force-Stop). |
| **Configuration-Change** | Rotation, Theme, Sprache, Density — Default löst Re-Inflate des InputViews aus, Service lebt weiter. |
| **`serviceScope`** | Vorgeschlagener `CoroutineScope` mit Lebensdauer = Service-Lebensdauer. |

---

## Referenzen

**AOSP / Android Developer Docs:**
- `InputMethodService` Reference: https://developer.android.com/reference/android/inputmethodservice/InputMethodService
- "Creating an Input Method" Guide: https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method
- AOSP Source (android14-release): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/inputmethodservice/InputMethodService.java
- WorkManager Overview: https://developer.android.com/topic/libraries/architecture/workmanager
- WorkManager Expedited Work: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#expedited

**Repo-Pointer (alle Pfade absolut, ab Repo-Root):**

| Datei | Zeilen | Zweck |
|-------|--------|-------|
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 1-2697 | Haupt-IME-Service (Java) |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 323-327 | `onCreate()` |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 329-396 | `initLongLivedObjects()` (long-lived Service-Member) |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 400-746 | `onCreateInputView()` |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 749-783 | `onFinishInputView()` mit 3-State-Logik |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 785-822 | `onDestroy()` |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 833-904 | `cleanupOldControllers()` |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 1307-1416 | `onStartInputView()` |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 1419-1427 | `onUpdateSelection()` |
| `app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt` | 1-364 | Process-wide Job-Singleton + `JobRequest`-Sealed-Class |
| `app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt` | 1-66 | StateFlow-basierte Job-Registry |
| `app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistryObserver.kt` | 1-49 | LifecycleOwner-Bridge (für Activities) |
| `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt` | 145, 780-815 | Eigener Executor + cancel/shutdown |
| `app/src/main/java/net/devemperor/dictate/core/RecordingStateController.kt` | 233-256 | `onKeyboardHidden()` / `onKeyboardShown()` |
| `app/src/main/AndroidManifest.xml` | 30-44 | IME-Service-Deklaration (kein `configChanges`-Override) |
| `gradle/libs.versions.toml` | 1-49 | Dependencies (WorkManager fehlt) |
| `app/build.gradle` | 1-100 | App-Modul-Konfig |
| `docs/plans/2026-05-07 - keyboard-layout-refactor/keyboard-layout-refactor.md` | 50-55 | Plan-Aussagen zum IME-Lifecycle (verifiziert in B.) |
