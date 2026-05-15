package net.devemperor.dictate.core

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import net.devemperor.dictate.R
import net.devemperor.dictate.settings.DictateSettingsActivity
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateModuleRegistry
import net.devemperor.dictate.state.DictateOrchestrator
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.DictateUiStateStore
import net.devemperor.dictate.state.DispatchOutcome
import net.devemperor.dictate.state.ModuleServices
import net.devemperor.dictate.state.PipelinePrefMirror
import net.devemperor.dictate.state.PipelineRecovery
import net.devemperor.dictate.state.PipelineServiceStubSubsystems
import net.devemperor.dictate.state.realToastSink
import net.devemperor.dictate.state.stubSessionRepo

/**
 * Foreground Service that hosts the Dictate pipeline state container.
 *
 * **C7 scope — composition root wired up.**
 *
 * As of chunk C7, this service owns the full [DictateOrchestrator]
 * composition root (Spec 1 §7.3 + ADR-0003 §"Required mechanics"):
 *
 *  - [DictateUiStateStore] — single source of truth.
 *  - [PipelinePrefMirror] — 19 prefs mirrored into the store at attach time.
 *  - [PipelineRecovery] — DB-replay launched async into [serviceScope].
 *  - [ModuleServices] — DI container handed to every `runEffect` call.
 *    C7 wires it with [PipelineServiceStubSubsystems] no-op stubs;
 *    Block 3 (chunk C8 — subsystem-adapter migration) replaces each
 *    stub with the real Android-backed adapter.
 *  - [DictateOrchestrator] — the action-routing dispatcher itself.
 *    Constructed last because it consumes everything above.
 *
 * **C7 lifecycle additions:**
 *
 *  - `onCreate` builds the composition root in deterministic order:
 *    notification channel → store → services (with stubs) → orchestrator
 *    (with prefMirror.attach + recovery.recover launched in its `init`
 *    block per Spec 1 §4.3).
 *  - `onDestroy` calls `orchestrator.shutdown()` (which detaches the
 *    pref mirror and runs per-module `terminate(services)`)
 *    **before** cancelling [serviceScope]. Order is the Spec 1 §4.3
 *    Aufrufer-Vertrag — terminate effects need a live scope.
 *  - [LocalBinder.dispatch] now forwards to
 *    `orchestrator.dispatch(action)` with the typed [Action] surface
 *    (replacing the Block-2 `Any` no-op stub).
 *  - [LocalBinder.state] exposes the orchestrator's
 *    `StateFlow<DictateUiState>` so the IME-side can `collect { … }`.
 *
 * **What is still B3 territory (per IMPL-1 carry-over):**
 *
 *  - The `JobExecutor.initialize(pipelineOrchestrator)` move from
 *    `DictateInputMethodService.initLongLivedObjects` to this
 *    `onCreate` is **not** done in C7. The move requires constructing
 *    the legacy `PipelineOrchestrator` (12-arg constructor binding
 *    `AIOrchestrator`, `AutoFormattingService`, `PromptQueueManager`,
 *    `SessionManager`, `SessionTracker`, the IME-implemented
 *    `PipelineCallback`, …) inside the service. Those subsystems are
 *    IME-scoped today; rewriting their construction into the service
 *    is the natural body of Block 3 (subsystem-adapter migration,
 *    chunk C8). The carry-over IMPL-1 stays open with that target.
 *
 * @see `docs/decisions/0001-state-modular-orchestrator-pattern.md` §"Required mechanics"
 * @see `docs/decisions/0003-service-foreground-pipeline-architecture.md` §"Required mechanics"
 * @see `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md` §4 §7 §11
 */
class DictatePipelineService : Service() {

    /**
     * Service-scoped coroutine context (Spec 1 §4.3).
     *
     * Used by:
     *  - [DictateOrchestrator]'s `init { … }` block — launches
     *    `recovery.recover(store)` here.
     *  - [DictateOrchestrator.emitAction] — async re-entry from
     *    module `runEffect`.
     *  - Module side-effects that explicitly need background work
     *    (e.g. `PendingSessionsModule.PersistDismissal`'s DB write)
     *    via `services.scope.launch { … }`.
     *
     * `Dispatchers.Main.immediate` matches Spec 1 §4.3 — the
     * orchestrator's single-dispatch path runs on Main.immediate so
     * re-entrant dispatches stay on the same task.
     *
     * Cancelled in [onDestroy] **after** `orchestrator.shutdown()`
     * (Aufrufer-Vertrag per Spec 1 §4.3 KDoc on `shutdown()`).
     */
    private val serviceScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Composition-root reference — lateinit because [onCreate] builds
     * it. Tests bypass this via the [Robolectric][org.robolectric]
     * service controller which calls `onCreate` before any consumer
     * touches the field.
     */
    private lateinit var orchestrator: DictateOrchestrator

    /**
     * Pref mirror reference — kept so the production wiring can be
     * observed by tests (and so `orchestrator.shutdown()` reaches
     * `prefMirror.detach()` through the orchestrator's own init
     * contract; see [DictateOrchestrator.shutdown]).
     */
    private lateinit var prefMirror: PipelinePrefMirror

    /**
     * Single binder instance per service lifetime. Android dispatches
     * the same `IBinder` to every client that calls `bindService`
     * (Spec 1 §11.3.4 Multi-Bind), so we expose one stable singleton.
     */
    private val binder: LocalBinder = LocalBinder()

    /**
     * Tracks whether [ensureNotificationChannel] has run. Exposed via
     * [isNotificationChannelReady] for the channel-order test in
     * `DictatePipelineServiceTest` — Spec 1 §10 Phase-B S-5
     * "NotificationChannel-vor-startForeground" acceptance.
     */
    private var notificationChannelReady: Boolean = false

    /**
     * Test-visibility hook: `true` after [ensureNotificationChannel]
     * has been invoked. Lets the Robolectric service test assert the
     * channel-before-startForeground ordering without reflecting into
     * the framework's `NotificationManager`.
     */
    val isNotificationChannelReady: Boolean
        get() = notificationChannelReady

    override fun onCreate() {
        super.onCreate()
        // ──────────────────────────────────────────────────────────────
        // Channel-order invariant (Spec 1 §11.1.4 + ADR-0003 Failure-Mode):
        // NotificationChannel MUST exist before the first `startForeground`
        // call. On API ≥ 26, NotificationManager throws
        // IllegalArgumentException otherwise — sticky Play-Store ANR class.
        // Synchronous in-memory call, <5 ms — no FGS 5-second-budget risk.
        // ──────────────────────────────────────────────────────────────
        ensureNotificationChannel()

        // ──────────────────────────────────────────────────────────────
        // Composition root (Spec 1 §7.3 + §11.2.2 Block-1b sub-steps 7-8)
        //
        // The order below is the binding contract:
        //  1. Store: holds DictateUiState (empty defaults until pref-mirror runs).
        //  2. Services: DI container of subsystem adapters. C7 wires in
        //     the Stub-* no-op subsystems; Block 3 (chunk C8) replaces
        //     each one with the real Android-backed adapter.
        //  3. PrefMirror + Recovery: constructed but NOT attached/launched
        //     here — the orchestrator's `init` block does both (in the
        //     correct order: attach synchronously, recover async into
        //     `serviceScope`). See Spec 1 §4.3 KDoc on the orchestrator
        //     constructor.
        //  4. Orchestrator: triggers the wiring in its `init`.
        //  5. Registry-coverage assertion: every direct Action sealed
        //     subclass must be claimed (catches a future "module added
        //     but not registered" bug at startup rather than as a
        //     silent runtime drop).
        // ──────────────────────────────────────────────────────────────
        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val store = DictateUiStateStore(DictateUiState.initial())

        // C7: stub subsystems. B3 (chunk C8) swaps each `Stub*.xxx`
        // for the real adapter. `realToastSink(applicationContext)`
        // is already production-quality — toasts surface to the user
        // today via the Android Toast system.
        val services = ModuleServices(
            recordingHardware = PipelineServiceStubSubsystems.recordingHardware,
            bluetoothSco = PipelineServiceStubSubsystems.bluetoothSco,
            audioFocus = PipelineServiceStubSubsystems.audioFocus,
            recordingTimer = PipelineServiceStubSubsystems.recordingTimer,
            amplitudeStream = PipelineServiceStubSubsystems.amplitudeStream,
            borderGlow = PipelineServiceStubSubsystems.borderGlow,
            pipelineRunner = PipelineServiceStubSubsystems.pipelineRunner,
            sessionRepo = stubSessionRepo(sharedPrefs),
            notificationCoordinator = PipelineServiceStubSubsystems.notificationCoordinator,
            inputConnectionProvider = { null }, // IME wires its real connection via Block 3.
            clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager,
            sharedPrefs = sharedPrefs,
            toastSink = realToastSink(applicationContext),
            audioFileFactory = PipelineServiceStubSubsystems.audioFileFactory,
            scope = serviceScope,
            emitAction = { action -> orchestrator.emitAction(action) }, // forward-reference resolves at first invocation
        )

        prefMirror = PipelinePrefMirror(sharedPrefs)
        val recovery = PipelineRecovery(services.sessionRepo)

        orchestrator = DictateOrchestrator(
            scope = serviceScope,
            store = store,
            services = services,
            registry = DictateModuleRegistry,
            prefMirror = prefMirror,
            recovery = recovery,
        )

        // Init-time invariant: every direct sealed Action subclass is
        // claimed by some module. Phase-B S-4 invariant 3 (deferred to
        // C7 per the registry's `validate` KDoc — the C5/C6 modules
        // are now all registered so the check can run).
        try {
            DictateModuleRegistry.assertCompleteCoverage()
        } catch (t: IllegalStateException) {
            // Re-throw with a Service-locatable tag so logcat shows
            // the missing-module-routing diagnostic before
            // startForeground runs. (Without the catch + re-throw the
            // stack would only point at `DictateModuleRegistry`.)
            Log.e(TAG, "Registry coverage assertion failed — module(s) missing", t)
            throw t
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ──────────────────────────────────────────────────────────────
        // FGS 5-second-budget invariant (Spec 1 §11.1.4 + ADR-0003
        // Failure-Mode "startForeground not called within 5 s"):
        // startForeground() runs synchronously as step 1 of onStartCommand,
        // before any coroutine launch, before any DB read, before any
        // potentially-blocking call. buildInitialNotification() is a pure
        // in-memory NotificationCompat.Builder chain — measured well below
        // the 1-second p99 target on API-34 reference devices.
        //
        // Defensive: on API ≥ 31 startForeground can throw
        // `ForegroundServiceStartNotAllowedException` (background-start
        // restrictions); on API ≥ 33 it can throw `SecurityException` when
        // POST_NOTIFICATIONS is denied or the FGS-type does not match. The
        // catch keeps the service from crash-looping behind the IME-side
        // `onBindingDied` rebind (DictateInputMethodService Block-2 wiring).
        // Spec 1 §11.5.1 anticipates this via the onboarding runtime
        // prompt; until that lands (delegated to a follow-up — see Issue
        // Index), stopSelf + START_NOT_STICKY is the safe recovery path.
        //
        // Block 1b switches this call to
        // `notifCoordinator.buildInitial()` + `actionRouter.dispatch(intent)`
        // and adds `notifCoordinator.startReactiveUpdates(::stopSelfWhenTerminal)`
        // (Spec 1 §7.3). The 5-second budget remains the binding contract.
        // ──────────────────────────────────────────────────────────────
        try {
            startForegroundCompat(buildInitialNotification())
        } catch (e: SecurityException) {
            // API 33+: POST_NOTIFICATIONS denied, FGS-type mismatch, etc.
            Log.w(TAG, "FGS start denied (security)", e)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            // API 31+: ForegroundServiceStartNotAllowedException — caught via
            // Exception base to keep the catch usable without an
            // @RequiresApi gate. Re-throw anything else so genuine bugs
            // still surface in logcat.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                Log.w(TAG, "FGS start denied (background-start restriction)", e)
                stopSelf()
                return START_NOT_STICKY
            }
            throw e
        }

        // Block 1b: `intent?.let { actionRouter.dispatch(it) }` lands here.
        // C7 ignores incoming intents — Notification action-routing is
        // delegated to the `PipelineActionRouter` that Block 1b owns.

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Spec 1 §4.3 Aufrufer-Vertrag: orchestrator.shutdown() MUST
        // run BEFORE serviceScope.cancel(). Otherwise the per-module
        // `terminate(services)` calls would launch async cleanup on a
        // cancelled scope and silent-no-op (DB-flushes lost,
        // notification cancellations missed).
        //
        // `runBlocking` + `withTimeout(2000L)` (Spec 1 §7.3 onDestroy
        // snippet) is the Block-1b shape — the orchestrator's
        // `shutdown()` is currently synchronous (no suspending body),
        // so the timeout wrapper is not strictly necessary in C7. We
        // call `shutdown()` plainly; Block 1b's onDestroy wraps it
        // when module-terminate becomes suspending.
        if (::orchestrator.isInitialized) {
            try {
                orchestrator.shutdown()
            } catch (t: Throwable) {
                Log.w(TAG, "Orchestrator shutdown failed", t)
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    // ──────────────────────────────────────────────────────────────────
    // Notification channel + initial notification (C7 baseline)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates the FGS notification channel idempotently.
     *
     * Spec 1 §11.1.2 calls this from `onCreate`. The channel is
     * `IMPORTANCE_LOW` (no sound, no heads-up) because the
     * notification is a passive status indicator — Block 1b's
     * `PipelineNotificationCoordinator` will populate the title/text
     * reactively from `DictateUiState`.
     *
     * On API < 26 NotificationChannels do not exist; the method is a
     * no-op and `notificationChannelReady` is flipped so call-order
     * tests can still observe that this method was reached before
     * [startForegroundCompat].
     */
    private fun ensureNotificationChannel() {
        notificationChannelReady = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.dictate_pipeline_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.dictate_pipeline_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        // Defensive: locked-down devices / restricted profiles can reject
        // channel creation with a SecurityException. We swallow so the
        // service still boots; without a channel, startForeground will
        // raise its own error (handled in onStartCommand).
        try {
            mgr.createNotificationChannel(channel)
        } catch (e: SecurityException) {
            Log.w(TAG, "NotificationChannel create denied", e)
        }
    }

    /**
     * Builds the initial FGS notification — a passive status indicator with
     * no action buttons.
     *
     * Block 1b replaces this with `PipelineNotificationCoordinator.buildInitial(state)`,
     * which adds state-dependent action buttons (Send / Pause / Resume /
     * Cancel — Spec 1 §7.6). The skeleton variant here is deliberately
     * action-less because C7 has not yet wired the action router.
     *
     * Pure in-memory work — must stay below ~50 ms to keep the FGS budget
     * uncontested.
     */
    private fun buildInitialNotification(): Notification {
        // Tapping the notification opens the Settings activity. Spec 1 §11.5.3
        // notes the IME cannot make itself visible from a notification tap —
        // surfacing the configuration UI is the closest equivalent.
        val contentIntent: PendingIntent? = PendingIntent.getActivity(
            this,
            0,
            Intent(this, DictateSettingsActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_mic_20)
            .setContentTitle(getString(R.string.dictate_pipeline_notif_title))
            .setContentText(getString(R.string.dictate_pipeline_notif_idle))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()
    }

    /**
     * `startForeground` with the API-34+ explicit-type form when available.
     *
     * Spec 1 §11.1.1: `targetSdk = 35` plus `foregroundServiceType="microphone"`
     * in the Manifest forces the explicit-type signature on API ≥ 34 or
     * Android throws `ForegroundServiceTypeNotAllowedException`. On
     * pre-API-34 devices the implicit overload picks up the manifest-declared
     * type automatically.
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // LocalBinder — single-dispatch surface (ADR-0003 §"Required mechanics" item 3)
    // ──────────────────────────────────────────────────────────────────

    /**
     * IME-facing bind surface. Returned from [onBind] and consumed by
     * the IME-Service via a [android.content.ServiceConnection].
     *
     * **Stable contract (C7 forward — must NOT widen).** The binder
     * exposes exactly two consumer-facing surfaces, mirroring the
     * Spec 1 §5 sealed contract:
     *
     *  - [state] — read-only `StateFlow<DictateUiState>` for the
     *    IME to `collect { … }` against. Replaces the previous
     *    `service` accessor (which leaked the whole service instance)
     *    with a narrower contract.
     *  - [dispatch] — single entry point for all state-mutating
     *    events (record-start, pause, resume, view-shown/hidden, …).
     *    No typed forwarder methods (F-8 / ADR-0001 §"Required
     *    mechanics" item 1). Takes the typed [Action] sealed-class
     *    surface (replaces the Block-2 `Any` placeholder).
     */
    inner class LocalBinder : Binder() {

        /**
         * Read-only view of the orchestrator's state. The IME calls
         * `binder.state.collect { … }` to render off it.
         *
         * **Lifetime contract:** [onCreate] populates [orchestrator]
         * before [onBind] is called — Android binds the service after
         * `onCreate` returns. So the field's `lateinit` is safe at
         * every observable touch point.
         */
        val state: StateFlow<DictateUiState>
            get() = orchestrator.state

        /**
         * Single-dispatch entry point. Forwards to
         * [DictateOrchestrator.dispatch] which routes the [action]
         * to the owning module's reducer.
         *
         * The IME side does not inspect the [DispatchOutcome] return
         * value today — most call sites only need to know that the
         * action was delivered. The return value is exposed for
         * testing + future error-surfacing.
         */
        fun dispatch(action: Action): DispatchOutcome = orchestrator.dispatch(action)

        /**
         * Module-internal accessor for tests and same-process callers
         * that need the service instance (e.g. the `notificationCoordinator`
         * wiring that Block 1b adds). Production IME code MUST NOT
         * reach through this — the [state] + [dispatch] surface is
         * the complete contract.
         */
        internal val service: DictatePipelineService
            get() = this@DictatePipelineService
    }

    companion object {
        /** Service log tag. */
        const val TAG: String = "DictatePipelineSvc"

        /**
         * NotificationChannel id for the persistent FGS notification.
         *
         * Spec 1 §11.1.2 — kept stable across all blocks because changing
         * it would create a second user-visible channel on devices that
         * already created the v1 channel. Block 1b reuses this constant.
         */
        const val CHANNEL_ID: String = "dictate_pipeline"

        /**
         * FGS notification id.
         *
         * Spec 1 §7.4 places the canonical NOTIF_ID on
         * `PipelineNotificationCoordinator` — that companion does not yet
         * exist in C7, so the service owns the constant in the
         * meantime. Block 1b moves the constant into the coordinator's
         * companion and the service reads
         * `PipelineNotificationCoordinator.NOTIF_ID` (Spec 1 §11.1.2
         * NOTIF_ID consolidation block). The value `0xD1C7A7E` is reused
         * verbatim so on-device notifications keep the same id across the
         * Block-2 → Block-1b transition.
         */
        const val NOTIF_ID: Int = 0xD1C7A7E

        /**
         * SharedPreferences file name used by the rest of the app
         * (`DictateInputMethodService.initLongLivedObjects` calls
         * `getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE)`).
         * Pinned here so the service uses the **same** SP instance as
         * the IME — otherwise the pref-mirror would read a different
         * file from the one the user's Settings activity wrote to.
         */
        const val PREFS_NAME: String = "net.devemperor.dictate"
    }
}
