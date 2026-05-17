# IME-Lifecycle & View-Recreation — Deep-Dive Research

**Created:** 2026-05-07
**Plan:** `docs/plans/2026-05-07 - keyboard-layout-refactor/keyboard-layout-refactor.md`
**Status:** Research (Pending — awaiting the design decision on the variant A+B combination)
**Scope:** What lives when, how do coroutines / background jobs survive a view-hidden phase or a service death in the Dictate IME? Which lifecycle guarantees does Android make, and what is the state in the repo today?
**Methodology:** AOSP docs (developer.android.com, android.googlesource.com) + a complete walkthrough of `DictateInputMethodService.java` and its collaborators (`JobExecutor`, `PipelineOrchestrator`, `RecordingStateController`, `ActiveJobRegistry`).

> **Pending marker:** This document is **research, not a spec.** It clarifies the factual basis and gives recommendations on the scope choice, but it does not describe "this is how variant A+B is implemented". The spec step follows in the block plan once the plan author finally settles the coroutine/WorkManager strategy.

---

## 0. TL;DR

1. **Service lifecycle ≠ view lifecycle.** The service can run for minutes without a visible input view (app switch, background). The view can be re-inflated multiple times per service life (rotation, theme, language switch).
2. **`onCreateInputView()` is not called only once.** The documentation statement "called once, when the input area is first displayed" is misleading — on configuration changes the framework calls `onInitializeInterface()` + `onCreateInputView()` again. Dictate already handles this explicitly (`cleanupOldControllers()` + re-inflate, [DictateInputMethodService.java:401](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)).
3. **Service-death modes:** IME switch (user picks another keyboard), process death (OOM), force-stop, app update. In all cases: `onDestroy()` *can* be called, but *need not* be (OOM!). Persistence outside the service memory is the only guarantee.
4. **There is no real coroutine in the IME today.** Lifecycle-bound coroutines live only in activities (`HistoryDetailActivity`, ActiveJobRegistryObserver). The IME service uses classic `ExecutorService` threads (`JobExecutor`, `PipelineOrchestrator`, `dbExecutor`). State is posted back to the main thread via `mainHandler.post()`.
5. **WorkManager is not in the project.** `androidx.work` is not included in `gradle/libs.versions.toml` — an A+B combination variant with WorkManager persistence requires a new dependency + initialisation in `DictateApplication`.
6. **What survives the view-hidden phase (today):** everything that is initialised in `onCreate` (service members, the `JobExecutor` singleton, the `ActiveJobRegistry` singleton, the Room DB). What is destroyed: view members (buttons, controllers, listener registrations).
7. **What does NOT survive service death:** all `ExecutorService` threads in `JobExecutor` and `PipelineOrchestrator` die with the process. State not yet written to Room is lost. This is the actual motivation for variant A+B.

---

## A. AOSP Lifecycle Guarantees

### A.1 Sources

- **Reference (`InputMethodService`):**
  https://developer.android.com/reference/android/inputmethodservice/InputMethodService
- **Guide ("Creating an Input Method"):**
  https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method
- **AOSP source:**
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/inputmethodservice/InputMethodService.java

> **Note:** `developer.android.com/reference/...` renders the class docs as a JS SPA — fetching it directly often returns only navigation. The reliable quotes come from the AOSP source and the guide page. The statements below are verifiable against the AOSP source if needed; a spot-check in `frameworks/base/.../InputMethodService.java` (master/HEAD) is sensible at the spec step.

### A.2 Callbacks and order

The central lifecycle callbacks of `InputMethodService`:

| Callback | Phase | When |
|----------|-------|------|
| `onCreate()` | Service | Once per service instance, at the very start |
| `onInitializeInterface()` | Service | Once after `onCreate()`, **again** on every configuration change |
| `onBindInput()` | Client | When a client (app) binds to the IME — new InputConnection |
| `onUnbindInput()` | Client | When the client bind ends |
| `onStartInput(EditorInfo, restarting)` | Editor | When the cursor enters a new EditText — BEFORE view show |
| `onCreateInputView()` | View | When the input view must be built — initially **and** after a config change |
| `onCreateCandidatesView()` | View | Optional, when a candidates strip is needed (Dictate does not use this) |
| `onStartInputView(EditorInfo, restarting)` | View | The view is built, about to be shown — ALWAYS after `onStartInput` |
| `onWindowShown()` | Window | The window becomes visible (animation in) |
| `onWindowHidden()` | Window | The window disappears (animation out) |
| `onFinishInputView(finishingInput)` | View | The view is hidden (app switch, back, editor change) |
| `onFinishInput()` | Editor | The editor session ends |
| `onConfigurationChanged(Configuration)` | Service | On rotation, theme, language (default behaviour, see below) |
| `onDestroy()` | Service | The service is destroyed — **not guaranteed** on process death |

**AOSP quote (Guide, "Creating an Input Method"):**
> "When the IME is displayed for the first time, the system calls the `onCreateInputView()` callback."
>
> "When an input field receives focus and your IME starts, the system calls `onStartInputView()`."
>
> "Release large memory allocations immediately after the input method window is hidden, so that applications have sufficient memory to run. Use a delayed message to release resources if the IME is hidden for a few seconds."
> — https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method

**AOSP quote (Reference, `onStartInputView`):**
> "Called when the input view is being shown and input has started on a new editor. This will always be called after `onStartInput`, allowing you to do your general setup there and just view-specific setup here."

**AOSP quote (Reference, `onFinishInputView`):**
> "Called when the input view is being hidden from the user. This will be called either prior to hiding the window, or prior to switching to another target for editing."

**AOSP quote (Reference, configuration-change handling):**
> "When a configuration change does happen, `onInitializeInterface()` is guaranteed to be called the next time prior to any of the other input or UI creation callbacks. The following will be called immediately depending if appropriate for current state: `onStartInput` if input is active, and `onCreateInputView` and `onStartInputView` and related appropriate functions if the UI is displayed."

### A.3 Order in typical user flows

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

### A.4 What survives what

| Event | Service members | View members | Background threads | Persistent state |
|----------|:-------------:|:-----------:|:------------------:|:------------------:|
| `onFinishInputView()` (app switch) | survive | survive (view stays in memory, just invisible) | survive | survives |
| `onConfigurationChanged()` (rotation) | survive | **rebuilt** via a renewed `onCreateInputView()` | survive | survives |
| IME switch (user → other keyboard) | **`onDestroy()` is called** | gone | die with the service | survives |
| Process death (OOM, force-stop) | gone | gone | gone (threads die with the process) | **only DB / SharedPrefs / files** |
| App update | like process death | gone | gone | survives (files, DB) |

**Important nuance — `onDestroy()` is not guaranteed:**
On process death (OOM killer, manual force-stop, crash) `onDestroy()` is **not** called. The framework only guarantees that `onDestroy()` runs *before* an orderly service stop. Cleanup code that runs only in `onDestroy()` is therefore not a persistence strategy — it is best-effort cleanup in the happy path.

**Configuration changes — default vs. `android:configChanges`:**
The manifest has **no** `android:configChanges` attribute on the `<service>` ([AndroidManifest.xml:30-38](app/src/main/AndroidManifest.xml)). That means: the framework applies the default behaviour — `onConfigurationChanged()` is called, the service does **not** die, but the input view is rebuilt in the next show cycle via `onCreateInputView()`. Dictate deliberately relies on this (view recreation as a first-class path).

---

## B. Concrete Lifecycle Usage in Dictate

### B.1 Service class

**Path:** [`app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) (2697 lines)
**Manifest:** [`app/src/main/AndroidManifest.xml:30-44`](app/src/main/AndroidManifest.xml) — standard IME declaration, no `configChanges` override.

### B.2 Overridden lifecycle callbacks

| Callback | File:Line | Responsibility |
|----------|-------------|---------------------|
| `onCreate()` | [DictateInputMethodService.java:323-327](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) | Long-lived init via `initLongLivedObjects()` |
| `onCreateInputView()` | [DictateInputMethodService.java:400-746](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) | View inflate + build all view-bound controllers + re-wire callbacks + state restore |
| `onStartInputView(info, restarting)` | [DictateInputMethodService.java:1307-1416](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) | Theme/colour, reload prompts, register BluetoothSCO receiver, BT wakeup, cancel pause timeout, auto-recording trigger |
| `onFinishInputView(finishingInput)` | [DictateInputMethodService.java:749-783](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) | 3-state logic: (A) recording active → pause+timeout, (B) pipeline running → close panels only, (C) idle → full cleanup |
| `onUpdateSelection(...)` | [DictateInputMethodService.java:1419-1427](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) | Re-render the prompt-adapter buttons depending on the selection |
| `onDestroy()` | [DictateInputMethodService.java:785-822](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) | Cleanup: pipelineOrchestrator.shutdown(), BT receiver, Room observer, languageController.dispose(), prefs listener, recordingStateController.onDestroy() |

**Not overridden** (Dictate relies on the default):
`onInitializeInterface`, `onBindInput`, `onUnbindInput`, `onStartInput`, `onCreateCandidatesView`, `onWindowShown`, `onWindowHidden`, `onConfigurationChanged`, `onEvaluateFullscreenMode`, `onEvaluateInputViewShown`, `onComputeInsets`.

### B.3 Where are buttons / layouts initialised?

**Hypothesis from the plan:** in `onCreateInputView()`. **Verified:** yes.

- `findViewById` pass: [DictateInputMethodService.java:422-498](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)
- View-bound controller construction: [DictateInputMethodService.java:500-619](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)
  - `InfoBarController` (500)
  - `KeyboardStateManager` (530) — SSOT for visibility
  - `KeyboardUiController` (544)
  - `MainButtonsController` (559) — registers all click listeners
  - `KeyboardLayoutModeController` (588) — Two-Row/Single-Row switching
  - `LanguageController` (634)
  - `RecordingUiController` (607)
- `cleanupOldControllers()` (preamble): [DictateInputMethodService.java:833-904](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) — explicit disposal of the old view-controller set before the rebuild (covers the re-inflate path).
- `rewireCallbacks()` (bridge): [DictateInputMethodService.java:910-…](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) — connects the long-lived service members with the fresh view-controller set.

### B.4 Where are coroutine scopes created?

**In the IME service: nowhere.** Search `CoroutineScope|SupervisorJob|GlobalScope|MainScope|lifecycleScope` over `app/src/main/java/`:

- `app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistryObserver.kt:5,35` — `lifecycleScope` (from `LifecycleOwner`, i.e. activity/fragment context, **not the IME**).

That means: today the IME uses no Kotlin coroutines for background work. The only coroutine touch points:

1. `ActiveJobRegistry` exposes a `StateFlow<Map<String, JobState>>` ([ActiveJobRegistry.kt:31](app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt)).
2. `ActiveJobRegistryObserver` collects this flow — but **bound to a `LifecycleOwner`**, i.e. only in activities (HistoryActivity etc.), not in the IME.

**Who cancels when (today, without a coroutine):**

- `JobExecutor` ([JobExecutor.kt:33](app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt)) holds a `singleThreadExecutor`. Cancel via cooperative `CancellationToken` + `Thread.interrupt()` ([JobExecutor.kt:184-187](app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt)). **No cancel on view-hidden** — the job continues.
- `PipelineOrchestrator` ([PipelineOrchestrator.kt:145](app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt)) holds its own `ExecutorService`. `cancel()` ([PipelineOrchestrator.kt:780](app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt)) does `shutdownNow()` and replaces the executor with a fresh one — it is called in `onFinishInputView()` state (C) ([DictateInputMethodService.java:771](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)), but **not** in states (A) and (B), where the pipeline may keep living.
- `RecordingStateController` ([RecordingStateController.kt:233](app/src/main/java/net/devemperor/dictate/core/RecordingStateController.kt)) reacts to view-hidden (`onKeyboardHidden`) with auto-pause + a 60s timeout, **without** killing the recording thread.
- `dbExecutor` ([DictateInputMethodService.java:144](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)) — service member, no explicit shutdown. Dies with the service process.
- `mainHandler` ([DictateInputMethodService.java:331](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)) — posts from worker threads back to the main thread. `removeCallbacks(reloadPromptsRunnable)` in `onDestroy()` ([L. 789](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)), otherwise no systematic cancellation.

### B.5 Is there already background-job logic?

- **WorkManager:** **Not present.** `androidx.work` is missing from [`gradle/libs.versions.toml`](gradle/libs.versions.toml) and [`app/build.gradle`](app/build.gradle). A variant with WorkManager persistence requires a new dependency.
- **`JobExecutor` + `ActiveJobRegistry`** is the background-job abstraction today: a process-wide singleton, a single active session, cooperative cancel, a `StateFlow` for UI observers ([JobExecutor.kt](app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt) and [ActiveJobRegistry.kt](app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt)).
- **`PipelineOrchestrator`** ([PipelineOrchestrator.kt](app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt)) — the actual pipeline (transcription → steps → insertion). Holds its own executor; the `*Blocking` methods are called from the `JobExecutor` thread.
- **`SessionManager` + `SessionTracker`** — DB persistence of the pipeline session including status transitions ([SessionManager.kt](app/src/main/java/net/devemperor/dictate/core/SessionManager.kt), [SessionTracker.kt](app/src/main/java/net/devemperor/dictate/core/SessionTracker.kt)). Writes after each step → crash recovery via DB status is possible.

**Interim conclusion:** The "singleton process job" architecture (JobExecutor + ActiveJobRegistry + StateFlow) is a deliberate pre-coroutine variant. It is viable for the view-hidden path (the service keeps living) but fails on a real process death — everything not in Room is gone.

---

## C. Coroutine-Scope Strategy for Background-Send (for the planned variant A+B)

### C.1 Service-scoped vs. view-scoped

**View-scoped (e.g. `viewLifecycleOwner.lifecycleScope` from the fragment world):** The scope is cancelled as soon as the view is gone. In the IME the equivalent would be a scope cancelled at `onFinishInputView()` or at the latest at the `onCreateInputView()` re-inflate. Consequence: a running background send would abort as soon as the user switches the app — exactly what variant A wants to avoid.

**Service-scoped (a dedicated `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, cancelled in `onDestroy()`):** The job lives as long as the service. App switch / rotation do not affect it; only IME switch and process death kill it. **This is the right granularity for variant A** ("service-local coroutine").

**Pseudo pattern for variant A** (not implemented, only a sketch for review):

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

### C.2 What happens to the coroutine on view-hidden if the scope was view-bound?

**Answer:** It is cancelled — `kotlinx.coroutines.CancellationException` is thrown, all suspending operations abort, `withContext`/`launch` children are cancelled. The OkHttp calls in the AI runners abort either via a cancel check or via an IO interrupt.

Exactly this behaviour is simulated in today's `PipelineOrchestrator.cancel()` ([L. 780-791](app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt)) via `executor.shutdownNow()` — the threads get `Thread.interrupt()`, OkHttp throws `InterruptedIOException` ([L. 307](app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt)). For a view-scoped coroutine the behaviour would be identical — and that is exactly why view scope is wrong for the send path.

### C.3 Recommended practice for "the job runs even when the view is gone, but only while the service lives"

1. Initialise `serviceScope` in `onCreate()`.
2. Call `serviceScope.cancel()` in `onDestroy()`.
3. Start long-running operations with `serviceScope.launch(Dispatchers.IO) { ... }` — **not** with `lifecycleScope` or `viewLifecycleOwner.lifecycleScope` (which do not exist in the IME anyway without an `androidx.lifecycle.LifecycleService` bridge).
4. **Beware process death:** Pure service scopes do not solve the "process dies during send" problem. That needs variant B (WorkManager + DB persistence).
5. **Lifecycle-coroutine bridge (optional):** If you want to use `lifecycleScope` in the IME service, `androidx.lifecycle.LifecycleService` is needed as the base class — but that is incompatible with `InputMethodService` (no multiple inheritance in Java). Practically: a custom scope as above.

### C.4 StateFlow / SharedFlow — where does the owner belong?

**Requirement:** the UI in the IME view consumes the flow, the view can be destroyed, the flow owner must not be destroyed by that.

| Lifetime | Owner | Example |
|-------------|-------|----------|
| Process-wide | Singleton object | `ActiveJobRegistry.state` ([ActiveJobRegistry.kt:31](app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt)) — already implemented this way today |
| Service lifetime | Service member | good for IME-specific UI state that should persist beyond view recreate but not beyond service death (e.g. "last shown InfoBar text") |
| View lifetime | View-controller member | good for view-internal animations that may die with the view |

**Consuming flows from the IME view:** The view has no `LifecycleOwner` (unlike activities/fragments). Whoever collects a flow from within `onCreateInputView()` must explicitly cancel the job in `cleanupOldControllers()`. Pseudo:

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

This is consistent with today's pattern, in which view-bound listeners (`servicePipelineCallback`, `inputLanguagesListener`, `audioFocusListener`) are explicitly deregistered in `cleanupOldControllers()` ([L. 856-885](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)). A coroutine-job cancel fits symmetrically into that.

---

## D. WorkManager in the IME Context

### D.1 Current status

- The dependency `androidx.work` is **not** in the project ([`gradle/libs.versions.toml`](gradle/libs.versions.toml), [`app/build.gradle`](app/build.gradle)). Variant B requires adding it.
- There is no WorkManager-specific context code (initialiser, configuration provider, worker classes).

### D.2 Limitations of WorkManager in the IME service

- **WorkManager runs in the same process as the app / IME service.** On an IME switch the IME process is terminated — running workers are cancelled and re-scheduled by the framework according to their `WorkManager` constraints + retry policy (that is exactly the desired effect for variant B).
- **Constructor init:** WorkManager has, since `androidx.work:2.6.0`, used `androidx.startup.Initializer` by default. In an IME project without a custom initializer it suffices to add the dependency — default init via the manifest provider applies. Custom configuration (logging, a custom WorkerFactory for DI) would be hung off `DictateApplication` via `Configuration.Provider`.
- **Call from the IME service:** `WorkManager.getInstance(context).enqueueUniqueWork(...)` is allowed from any `Context` — the IME service is a Context. No known IME-specific block.
- **Quotas (App Standby Buckets, Doze):** On modern Android versions, workers can be paused by Doze mode if the device is idle and the worker is not `expedited`. For a "Send the last transcription" job, `expedited = true` with `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` is the typical path.

### D.3 WorkManager job → view-status propagation

**Recommended pattern:**

```
WorkManager-Worker            DB (Room)             Repository (Flow)        View (Collector)
──────────────────            ─────────            ─────────────────         ────────────────
   doWork() {                                       fun observe(): Flow
     updateProgress(...)                            = dao.observeFlow()
     dao.update(...)    ─→   sessions(status)  ─→
     ...                                                                 ─→  collect { render }
   }
```

- The worker writes **DB updates** (Room with `@Query Flow<...>`).
- Repositories expose `Flow<...>` from the DAO queries.
- The IME view collects the flow in the `serviceScope` (or a view-collector job cancelled in `cleanupOldControllers()`).
- Advantages: the worker stays UI-agnostic, multiple consumers possible (IME view, HistoryActivity), crash recovery automatic (the DB survives process death).

**Alternatives that fit poorly in this architecture:**

- `WorkInfo.observe(LifecycleOwner)` — needs a LifecycleOwner; not idiomatic in the IME view without a `LifecycleService` hack.
- `LiveData<WorkInfo>` without a `LifecycleOwner` — `observeForever()` is possible, but explicit `removeObserver()` is mandatory, otherwise a leak. A DB flow is cleaner.

### D.4 A+B combination sketch (recommendation for the plan author)

| Path | Technique | Owner |
|------|---------|-------|
| Recording active → user switches app | `RecordingStateController.onKeyboardHidden()` (already today) — auto-pause + 60s timeout, because a mic stream without visualisation is pointless | Service |
| Send (pipeline) running → user switches app | Variant A: `serviceScope.launch` — stays active for the service's life | Service |
| Send (pipeline) running → process-death risk | Variant B: the same operation as `WorkManager.enqueueUniqueWork(workName=sessionId, REPLACE)` — on process death the worker is re-scheduled. State lies in the `sessions` table | WorkManager + Room |
| View comes back (service lives) | The view collects `dao.observeSession(sessionId): Flow` — sees the current status | View-collector job in serviceScope |
| View comes back (service dead, process death) | The view collects the same flow → sees the current DB status (either `RUNNING` via the worker or `FAILED`/`CANCELLED` with a recovery affordance) | View-collector job in a new serviceScope |

**The single source of truth remains the DB.** Variant A (coroutine) only ensures the happy path continues in the same process. Variant B (WorkManager) ensures the system makes a new attempt after process death. Both write through the same `SessionManager`/`SessionTracker` path into the same `sessions` table, so the view-collector logic does not have to distinguish between "local" and "re-fetched".

---

## E. User-Flow Mapping (As-Is vs. To-Be)

### Flow A — Recording running, user switches app

**NOW** ([DictateInputMethodService.java:756-762](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) + [RecordingStateController.kt:233-247](app/src/main/java/net/devemperor/dictate/core/RecordingStateController.kt)):
- `onFinishInputView()` state (A): `recordingStateController.onKeyboardHidden()` → `togglePause()` + `startPauseTimeout()` (60 s default).
- BluetoothSCO is released, AudioFocus abandoned, keep-screen-awake turned off.
- ContentArea switches to `MAIN_BUTTONS` (cleanup).
- The service keeps living, the recording thread (in the RecordingManager) runs in the paused state.

**TO-BE** (per the plan variant A+B): unchanged — recording stays paused, auto-stop after 60 s is desired. Recording without mic visualisation in the background is a UX trap (the user forgets it is still running).

### Flow B — Send running, user switches app

**NOW** ([DictateInputMethodService.java:764-768](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)):
- `onFinishInputView()` state (B): `pipelineOrchestrator.isRunning() == true` → only set ContentArea to `MAIN_BUTTONS`, the pipeline continues on its own thread.
- No cancellation, no persistence mutation.
- The result (insertion) happens via `getCurrentInputConnection()` — if the user switched the app in the meantime, the InputConnection may **no longer be valid** (a different EditorInfo). The insertion can fail silently or land in the wrong editor.
- On process death (rare but possible): the pipeline thread dies, the `sessions` entry stays stuck in `RUNNING` status → "stale-running" bug.

**TO-BE** (plan):
- Send still background-capable (already correct for the service-lives path).
- Variant A (serviceScope) makes this semantically cleaner — no more separate executor lifecycles, a unified cancel path.
- Variant B (WorkManager) covers process death: after restart the view sees, via the DB flow, that the session is starting up again (or has gone to `FAILED` per the retry policy).
- InputConnection capture: must be captured **before** `onFinishInputView()` (today partly implemented via `InsertionSource` + fallback strategies in `ResendInsertStrategy`/`ResendStatusDispatcher` — a detail audit is needed at the spec step).

### Flow C — User comes back after 30 s (the service still lives)

**NOW:**
- `onCreateInputView()` does **not** run again (the view was in memory, only the window was hidden).
- More precisely: on a pure app switch without a config change, the next EditText tap calls `onStartInput()` + `onStartInputView()`. The view object stays the same if the framework has not discarded it in the meantime. (In practice: the framework discards the input view after longer inactivity; on a short pause usually not.)
- If the view is re-inflated: plan-specific state (running pipeline status, RecordingState) is restored in `restoreUiState()` ([DictateInputMethodService.java:733-734](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java) + helper) from the service members.
- If the view is not re-inflated: `onStartInputView()` ([L. 1307](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)) re-applies the theme, loads prompts, registers the BT receiver.
- The user sees: the running pipeline status (progress steps), or possibly the finished result in the EditText, if the pipeline completed while the view was gone.

**TO-BE:**
- Behaviour stays correct; after variant A+B: additionally a clearer recovery path if the pipeline went to `FAILED`/`CANCELLED` while hidden — a notification/InfoBar affordance is desirable (currently partly via `InfoBarController.showInfo(...)`).

### Flow D — User comes back after 5 min (the service may be dead)

**NOW:**
- If the service is dead (IME switch, OOM, force-stop, update): a new service process starts on the next EditText tap.
- `onCreate()` → `initLongLivedObjects()` → fresh singletons, `JobExecutor` is `EMPTY` again, `ActiveJobRegistry.state.value` is an empty map.
- `onCreateInputView()` builds a fresh view.
- The DB contains the session from the old process — but in the status it was in at the time of the process death (e.g. `RUNNING`, because the terminal update did not get through).
- The user sees: no running pipeline UI (`ActiveJobRegistry` is empty), no progress strip. The session in the DB is effectively zombie-like — without resurrection logic, no recovery.
- HistoryActivity may show the session as `RUNNING` or with a stale marker (detail in [SessionManager.kt](app/src/main/java/net/devemperor/dictate/core/SessionManager.kt) — out of scope of this research).

**TO-BE** (with variant A+B):
- Process death during a running send → WorkManager has the job persistently queued (variant B). The next IME start (or another app trigger) triggers the worker, which resumes the pipeline from the `sessions` state (provided the pipeline is resumable — it partly is today, see `JobRequest.Resume` and `PipelineOrchestrator.resumePipelineBlocking`).
- On the next view build the view collects the repository flow → sees either the running worker status or the finalised success/failure state.
- Stale-running detection: on service `onCreate()` a cleanup pass over the `sessions` table — mark all `RUNNING` from an earlier process as `INTERRUPTED` or resume them via a worker. This pass is not implemented today and would be part of the spec.

---

## F. What This Research Hardens or Questions in the Plan's Statements

| Plan statement | Status |
|--------------|--------|
| "IME `onCreateInputView`: inflate layout → construct controllers" ([keyboard-layout-refactor.md:52](docs/plans/2026-05-07%20-%20keyboard-layout-refactor/keyboard-layout-refactor.md)) | **Verified.** Implemented exactly so today, [DictateInputMethodService.java:401-746](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java). |
| "IME `onFinishInputView` / `onDestroy`: clearLayoutModeController, lifecycle cleanup" ([keyboard-layout-refactor.md:53](docs/plans/2026-05-07%20-%20keyboard-layout-refactor/keyboard-layout-refactor.md)) | **Partial.** `onFinishInputView` does *not* do `clearLayoutModeController` today — the layout controller stays attached to the view until the next `cleanupOldControllers()` nulls it. The plan proposal is a cleanliness tightening, not the as-is state. |
| "View recreate is a first-class path" (plan tenor) | **Verified.** `cleanupOldControllers()` ([L. 833](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)) + `rewireCallbacks()` ([L. 910](app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java)) + `restoreUiState()` is explicitly designed for the re-inflate scenario. |
| Variant A "service-local coroutine" | **Greenfield.** No coroutines in the IME today — variant A introduces the coroutine pattern into the IME. SupervisorJob + `serviceScope.cancel()` in `onDestroy()` is the standard form. |
| Variant B "WorkManager + DB persistence" | **Greenfield.** Dependency missing; must be added to `gradle/libs.versions.toml`. DB persistence already exists via Room+SessionManager; the worker wrapper and the resume trigger are to be newly implemented. |
| "DB persistence" as a variant-B component | **Half-greenfield.** Pipeline sessions are already persisted ([SessionManager.kt](app/src/main/java/net/devemperor/dictate/core/SessionManager.kt)); what is missing: a "stale-running cleanup" pass and a "resume-on-service-start" trigger. |

---

## G. Open Questions for the Spec Step

1. **Stale-running recovery strategy:** On IME `onCreate()` iterate over the `sessions` table — mark all `RUNNING` from earlier processes as `INTERRUPTED`, or reanimate them via `JobExecutor.start(JobRequest.Resume(...))`? The latter only makes sense if variant B is active.
2. **Worker granularity:** One worker per session vs. one "drain-queue" worker that processes all pending sessions? The latter is more robust with multiple sends in a row.
3. **Expedited worker:** `setExpedited(...)` for the send operation? In practice yes, but the quota behaviour is manufacturer-specific — tests on real hardware are needed.
4. **InputConnection after service death:** cannot be re-established. On process death during a send, the result must surface either to the clipboard, the history (present), or as a notification — the plan author decides what the user affordance is.
5. **Coroutine migration vs. coexistence:** Variant A does not necessarily replace the `JobExecutor` singleton. A hybrid path ("coroutine as a new layer over `JobExecutor.start`") keeps the cancellation semantics unified. A complete migration to coroutines is its own block theme.
6. **Process-wide singletons (`JobExecutor`, `ActiveJobRegistry`) on process death:** both are re-instantiated with the process. A "reattach" path after a process restart is completely missing today — variant A+B must model that explicitly.

---

## H. Glossary (Quick Reference)

| Term | Meaning |
|---------|-----------|
| **IME** | Input Method Editor — a keyboard app. |
| **Service** | Here: the `InputMethodService` subclass, i.e. the process that provides the keyboard. |
| **Input view** | The actual keyboard layout (in Dictate: `activity_dictate_keyboard_view.xml` inflated in `onCreateInputView`). |
| **Window** | The Android window into which the input view is packed. Visibility via `onWindowShown`/`onWindowHidden`. |
| **Editor / Client** | The app that provides a text field for input. Connection via `InputConnection`. |
| **Service death** | The IME process is terminated (user action or system action). |
| **Process death** | A subset of service death: the process is hard-terminated without an `onDestroy()` call (OOM, force-stop). |
| **Configuration change** | Rotation, theme, language, density — the default triggers a re-inflate of the input view, the service keeps living. |
| **`serviceScope`** | The proposed `CoroutineScope` with lifetime = service lifetime. |

---

## References

**AOSP / Android Developer Docs:**
- `InputMethodService` Reference: https://developer.android.com/reference/android/inputmethodservice/InputMethodService
- "Creating an Input Method" Guide: https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method
- AOSP Source (android14-release): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/inputmethodservice/InputMethodService.java
- WorkManager Overview: https://developer.android.com/topic/libraries/architecture/workmanager
- WorkManager Expedited Work: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#expedited

**Repo pointers (all paths absolute, from repo root):**

| File | Lines | Purpose |
|-------|--------|-------|
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 1-2697 | Main IME service (Java) |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 323-327 | `onCreate()` |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 329-396 | `initLongLivedObjects()` (long-lived service members) |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 400-746 | `onCreateInputView()` |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 749-783 | `onFinishInputView()` with 3-state logic |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 785-822 | `onDestroy()` |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 833-904 | `cleanupOldControllers()` |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 1307-1416 | `onStartInputView()` |
| `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` | 1419-1427 | `onUpdateSelection()` |
| `app/src/main/java/net/devemperor/dictate/core/JobExecutor.kt` | 1-364 | Process-wide job singleton + `JobRequest` sealed class |
| `app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistry.kt` | 1-66 | StateFlow-based job registry |
| `app/src/main/java/net/devemperor/dictate/core/ActiveJobRegistryObserver.kt` | 1-49 | LifecycleOwner bridge (for activities) |
| `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt` | 145, 780-815 | Own executor + cancel/shutdown |
| `app/src/main/java/net/devemperor/dictate/core/RecordingStateController.kt` | 233-256 | `onKeyboardHidden()` / `onKeyboardShown()` |
| `app/src/main/AndroidManifest.xml` | 30-44 | IME service declaration (no `configChanges` override) |
| `gradle/libs.versions.toml` | 1-49 | Dependencies (WorkManager missing) |
| `app/build.gradle` | 1-100 | App-module config |
| `docs/plans/2026-05-07 - keyboard-layout-refactor/keyboard-layout-refactor.md` | 50-55 | Plan statements on the IME lifecycle (verified in B.) |
