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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import net.devemperor.dictate.ai.AIOrchestrator
import net.devemperor.dictate.ai.prompt.PromptService
import net.devemperor.dictate.database.DictateDatabase
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
 * **C8 scope — IMPL-1 closed: composition root + AI infrastructure
 * migrated from IME to Service.**
 *
 * As of chunk C8, this service owns:
 *
 *  - [DictateUiStateStore] — single source of truth.
 *  - [PipelinePrefMirror] — 19 prefs mirrored into the store at attach time.
 *  - [PipelineRecovery] — DB-replay launched async into [serviceScope].
 *  - [ModuleServices] — DI container handed to every `runEffect` call;
 *    populated with **production subsystem adapters** (no stubs except
 *    the C9-C11-scoped ones — see [PipelineServiceStubSubsystems] for
 *    `sessionRepo` and `audioFileFactory`).
 *  - [DictateOrchestrator] — the action-routing dispatcher itself.
 *  - **AI infrastructure** (per IMPL-1, Spec 1 §11.2.2 sub-step 7):
 *    [AIOrchestrator], [AutoFormattingService], [PromptQueueManager],
 *    [SessionManager], [SessionTracker], [PromptService],
 *    [PipelineCallbackBridge], the legacy [PipelineOrchestrator]
 *    (audio-pipeline runner — Spec 1 §1.x Naming-Konvention; NOT
 *    [DictateOrchestrator]), and [JobExecutor.initialize] is called
 *    here. The IME consumes them via [LocalBinder] getters and
 *    registers itself as the [PipelineCallbackBridge] delegate when it
 *    binds.
 *
 * **C8 lifecycle:**
 *
 *  - `onCreate` builds the composition root in deterministic order:
 *    notification channel → AI infrastructure → subsystem adapters →
 *    store → orchestrator (with prefMirror.attach + recovery.recover
 *    launched in its `init` block per Spec 1 §4.3) →
 *    `JobExecutor.initialize(pipelineOrchestrator)`.
 *  - `onDestroy` calls `orchestrator.shutdown()` (which detaches the
 *    pref mirror and runs per-module `terminate(services)`) **before**
 *    cancelling [serviceScope]. Order is the Spec 1 §4.3
 *    Aufrufer-Vertrag.
 *  - [LocalBinder.dispatch] forwards to `orchestrator.dispatch(action)`
 *    with the typed [Action] surface.
 *  - [LocalBinder.state] exposes the orchestrator's
 *    `StateFlow<DictateUiState>` so the IME-side can `collect { … }`.
 *  - [LocalBinder] exposes typed accessors for the AI infrastructure
 *    objects so the IME's [DictateInputMethodService.onServiceConnected]
 *    can wire them into IME fields.
 *
 * **Composition-root invariant (Spec 1 §7.3 + ADR-0003):** all
 * cross-component dependencies originate here. The IME owns its UI +
 * `Recording*Controller` flow, but the AI/persistence stack is
 * service-scoped.
 *
 * @see `docs/decisions/0001-state-modular-orchestrator-pattern.md` §"Required mechanics"
 * @see `docs/decisions/0003-service-foreground-pipeline-architecture.md` §"Required mechanics"
 * @see `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md` §4 §7 §11
 */
class DictatePipelineService : Service() {

    /**
     * Service-scoped coroutine context (Spec 1 §4.3). Cancelled in
     * [onDestroy] after `orchestrator.shutdown()`.
     */
    private val serviceScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var orchestrator: DictateOrchestrator
    private lateinit var prefMirror: PipelinePrefMirror

    // ── C8 — AI infrastructure (IMPL-1 closure) ────────────────────────
    //
    // Constructed in `onCreate` and exposed via the LocalBinder. The IME
    // reads them in `onServiceConnected` and assigns to its own fields.
    // The PipelineOrchestrator's callback is a bridge — the IME registers
    // its real callback implementation when it binds, clears on unbind.

    /** Bound at `onCreate`. Exposed via [LocalBinder.aiOrchestrator]. */
    private lateinit var aiOrchestratorImpl: AIOrchestrator
    private lateinit var autoFormattingServiceImpl: AutoFormattingService
    private lateinit var promptQueueManagerImpl: PromptQueueManager
    private lateinit var sessionManagerImpl: SessionManager
    private lateinit var sessionTrackerImpl: SessionTracker
    private lateinit var promptServiceImpl: PromptService
    private lateinit var pipelineOrchestratorImpl: PipelineOrchestrator
    private lateinit var pipelineCallbackBridgeImpl: PipelineCallbackBridge
    private lateinit var recordingRepositoryImpl: RecordingRepository

    // ── C8 — Subsystem adapters (production-quality) ───────────────────
    private lateinit var audioFocusGateImpl: AudioFocusGate
    private var bluetoothScoSubsystemAdapterImpl: BluetoothScoSubsystemAdapter? = null

    /** Single binder instance — Spec 1 §11.3.4 Multi-Bind. */
    private val binder: LocalBinder = LocalBinder()

    private var notificationChannelReady: Boolean = false

    /**
     * Test-visibility hook: `true` after [ensureNotificationChannel].
     */
    val isNotificationChannelReady: Boolean
        get() = notificationChannelReady

    override fun onCreate() {
        super.onCreate()
        // ──────────────────────────────────────────────────────────────
        // Step 1 — NotificationChannel (FGS pre-requisite, < 5 ms).
        // ──────────────────────────────────────────────────────────────
        ensureNotificationChannel()

        // ──────────────────────────────────────────────────────────────
        // Step 2 — AI infrastructure (IMPL-1 closure, Spec 1 §11.2.2 step 7).
        // Constructed before the orchestrator because PipelineRunner /
        // PipelineCallbackBridge participate in the ModuleServices wiring.
        // ──────────────────────────────────────────────────────────────
        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val database = DictateDatabase.getInstance(this)

        aiOrchestratorImpl = AIOrchestrator(sharedPrefs, database.usageDao())
        promptServiceImpl = PromptService.create(sharedPrefs)
        autoFormattingServiceImpl = AutoFormattingService.create(sharedPrefs, aiOrchestratorImpl)
        sessionManagerImpl = SessionManager(database)
        sessionTrackerImpl = SessionTracker(database.sessionDao())
        recordingRepositoryImpl = RecordingRepository(this)

        // PromptQueueManager has a callback dependency. The IME registers
        // its own callback via the binder after bind; until then, the
        // service-side stub no-ops (queue mutations from settings activity
        // surface only when the IME is bound and listening).
        val promptDao = database.promptDao()
        promptQueueManagerImpl = PromptQueueManager(
            promptDao::getAutoApplyIds,
            sharedPrefs,
            object : PromptQueueManager.PromptQueueCallback {
                override fun onQueueChanged(queuedIds: List<Int>) {
                    binder.delegatePromptQueueCallback?.onQueueChanged(queuedIds)
                }
            },
        )

        // PipelineCallbackBridge — null delegate until the IME binds.
        pipelineCallbackBridgeImpl = PipelineCallbackBridge()

        // Construct the legacy PipelineOrchestrator (audio-pipeline runner).
        // Naming: `pipelineOrchestratorImpl` is the LEGACY runner; the
        // modular DictateOrchestrator is the state-action router.
        pipelineOrchestratorImpl = PipelineOrchestrator(
            aiOrchestratorImpl,
            autoFormattingServiceImpl,
            promptQueueManagerImpl,
            promptServiceImpl,
            sessionManagerImpl,
            sessionTrackerImpl,
            promptDao,
            pipelineCallbackBridgeImpl,
            recordingRepositoryImpl,
            database.transcriptionDao(),
            database.processingStepDao(),
            database,
        )

        // JobExecutor.initialize — the IMPL-1 closure point. Single-shot
        // process-wide initialization (JobExecutor is a Kotlin `object`).
        JobExecutor.initialize(pipelineOrchestratorImpl)

        // ──────────────────────────────────────────────────────────────
        // Step 3 — Subsystem adapters (Spec 1 §9.6 — wrap legacy classes).
        // ──────────────────────────────────────────────────────────────
        val store = DictateUiStateStore(DictateUiState.initial())
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioFocusGateImpl = buildAudioFocusGate(audioManager)

        // RecordingHardware + Timer + Amplitude + BorderGlow adapters are
        // service-owned. They emit follow-up actions via the orchestrator
        // (forward-reference captured below).
        val recordingHardware = RecordingHardwareAdapter(emitAction = { action ->
            orchestrator.emitAction(action)
        })
        val recordingTimer = RecordingTimerAdapter()
        val amplitudeStream = AmplitudeStreamAdapter()
        val borderGlow = BorderGlowAdapter()
        val audioFocus = AudioFocusSubsystemAdapter(audioFocusGateImpl)

        // BluetoothSco needs a callback. The orchestrator-side adapter
        // routes SCO state changes via `emitAction` to the AudioModule.
        // For C8 we keep the simpler `start()/stop()` adapter (Spec 1
        // §15.3 AudioModule does not have a runEffect-driven listener for
        // SCO state changes — those come as system broadcasts handled
        // out-of-band). The callback-routing path is a B5+ concern.
        val bluetoothScoManager = audioManager?.let {
            BluetoothScoManager(this, it, object : BluetoothScoManager.BluetoothScoCallback {
                override fun onScoConnected() {
                    orchestrator.emitAction(
                        Action.AudioAction.OnBluetoothScoStateChanged(
                            phase = net.devemperor.dictate.state.ScoPhase.Connected,
                            reason = null,
                        )
                    )
                }

                override fun onScoDisconnected() {
                    orchestrator.emitAction(
                        Action.AudioAction.OnBluetoothScoStateChanged(
                            phase = net.devemperor.dictate.state.ScoPhase.Disconnected,
                            reason = null,
                        )
                    )
                }

                override fun onScoFailed() {
                    orchestrator.emitAction(
                        Action.AudioAction.OnBluetoothScoStateChanged(
                            phase = net.devemperor.dictate.state.ScoPhase.Failed,
                            reason = "sco-timeout",
                        )
                    )
                }
            })
        }
        bluetoothScoSubsystemAdapterImpl = bluetoothScoManager?.let {
            BluetoothScoSubsystemAdapter(it)
        }
        val bluetoothSco = bluetoothScoSubsystemAdapterImpl
            ?: PipelineServiceStubSubsystems.bluetoothSco

        // ──────────────────────────────────────────────────────────────
        // Step 4 — ModuleServices: real adapters where available, stubs
        // for C9-C11-scoped subsystems (sessionRepo, audioFileFactory,
        // notificationCoordinator). The IME's recording flow STILL
        // drives MediaRecorder via its own RecordingManager — this
        // adapter set is the orchestrator-side parallel path that
        // future blocks (B5/B6 LayoutCatalog) will route through.
        // ──────────────────────────────────────────────────────────────
        val services = ModuleServices(
            recordingHardware = recordingHardware,
            bluetoothSco = bluetoothSco,
            audioFocus = audioFocus,
            recordingTimer = recordingTimer,
            amplitudeStream = amplitudeStream,
            borderGlow = borderGlow,
            pipelineRunner = PipelineServiceStubSubsystems.pipelineRunner,
            sessionRepo = stubSessionRepo(sharedPrefs),
            notificationCoordinator = PipelineServiceStubSubsystems.notificationCoordinator,
            inputConnectionProvider = { binder.delegateInputConnectionProvider?.invoke() },
            clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager,
            sharedPrefs = sharedPrefs,
            toastSink = realToastSink(applicationContext),
            audioFileFactory = PipelineServiceStubSubsystems.audioFileFactory,
            scope = serviceScope,
            emitAction = { action -> orchestrator.emitAction(action) },
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

        try {
            DictateModuleRegistry.assertCompleteCoverage()
        } catch (t: IllegalStateException) {
            Log.e(TAG, "Registry coverage assertion failed — module(s) missing", t)
            throw t
        }
    }

    /**
     * Build the production [AudioFocusGate] from the system [AudioManager].
     * Returns a no-op [AudioFocusGate] when the AudioManager is unavailable
     * (process-isolation environments, defensive null-check).
     */
    private fun buildAudioFocusGate(audioManager: AudioManager?): AudioFocusGate {
        if (audioManager == null) return NoOpAudioFocusGate
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { focusChange ->
                // Bridge AudioFocus loss/gain into the orchestrator so the
                // AudioModule observes the focus axis. The reducer's
                // cross-module-cascade then pauses the recording if
                // active (Spec 1 §15.3).
                val granted = focusChange == AudioManager.AUDIOFOCUS_GAIN ||
                    focusChange == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                orchestrator.emitAction(
                    Action.AudioAction.OnAudioFocusGrantChanged(granted = granted)
                )
            }
            .build()
        return RealAudioFocusGate(audioManager, request)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundCompat(buildInitialNotification())
        } catch (e: SecurityException) {
            Log.w(TAG, "FGS start denied (security)", e)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                Log.w(TAG, "FGS start denied (background-start restriction)", e)
                stopSelf()
                return START_NOT_STICKY
            }
            throw e
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Spec 1 §4.3: orchestrator.shutdown() BEFORE serviceScope.cancel().
        if (::orchestrator.isInitialized) {
            try {
                orchestrator.shutdown()
            } catch (t: Throwable) {
                Log.w(TAG, "Orchestrator shutdown failed", t)
            }
        }
        // Shutdown the legacy PipelineOrchestrator's executor. Without
        // this the executor thread leaks past process teardown — visible
        // in `adb shell ps -T` on debug devices.
        if (::pipelineOrchestratorImpl.isInitialized) {
            try {
                pipelineOrchestratorImpl.shutdown()
            } catch (t: Throwable) {
                Log.w(TAG, "PipelineOrchestrator shutdown failed", t)
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    // ──────────────────────────────────────────────────────────────────
    // Notification channel + initial notification (C7 baseline)
    // ──────────────────────────────────────────────────────────────────

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
        try {
            mgr.createNotificationChannel(channel)
        } catch (e: SecurityException) {
            Log.w(TAG, "NotificationChannel create denied", e)
        }
    }

    private fun buildInitialNotification(): Notification {
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
    // LocalBinder — single-dispatch surface + AI-infrastructure accessors
    // ──────────────────────────────────────────────────────────────────

    /**
     * IME-facing bind surface. Two responsibilities:
     *
     *  1. **Orchestrator surface** ([state], [dispatch]) — typed action
     *     dispatch + state observation. Stable contract (C7-forward).
     *  2. **AI-infrastructure accessors** — C8 IMPL-1 closure exposes
     *     [aiOrchestrator], [pipelineOrchestrator], etc. so the IME can
     *     wire them into its existing fields in `onServiceConnected`.
     *     The IME also registers callbacks here (via [registerPipelineCallback],
     *     [registerPromptQueueCallback], [registerInputConnectionProvider]).
     */
    inner class LocalBinder : Binder() {

        // ── Orchestrator surface (C7 / C8) ────────────────────────────
        val state: StateFlow<DictateUiState>
            get() = orchestrator.state

        fun dispatch(action: Action): DispatchOutcome = orchestrator.dispatch(action)

        internal val service: DictatePipelineService
            get() = this@DictatePipelineService

        // ── AI infrastructure (C8 — IMPL-1 closure) ───────────────────

        /** The service-owned [AIOrchestrator]. */
        val aiOrchestrator: AIOrchestrator
            get() = aiOrchestratorImpl

        /** The service-owned [AutoFormattingService]. */
        val autoFormattingService: AutoFormattingService
            get() = autoFormattingServiceImpl

        /** The service-owned [PromptQueueManager]. */
        val promptQueueManager: PromptQueueManager
            get() = promptQueueManagerImpl

        /** The service-owned [SessionManager]. */
        val sessionManager: SessionManager
            get() = sessionManagerImpl

        /** The service-owned [SessionTracker]. */
        val sessionTracker: SessionTracker
            get() = sessionTrackerImpl

        /** The service-owned [PromptService]. */
        val promptService: PromptService
            get() = promptServiceImpl

        /** The service-owned [RecordingRepository]. */
        val recordingRepository: RecordingRepository
            get() = recordingRepositoryImpl

        /** The service-owned legacy [PipelineOrchestrator] (audio-pipeline runner). */
        val pipelineOrchestrator: PipelineOrchestrator
            get() = pipelineOrchestratorImpl

        /** The service-owned production [AudioFocusGate]. */
        val audioFocusGate: AudioFocusGate
            get() = audioFocusGateImpl

        // ── Callback registration (IME → Service direction) ──────────
        //
        // The IME registers its own callbacks when it binds and clears
        // them when it unbinds. The service-side stubs/no-ops drop calls
        // during gaps (process boot, IME backgrounded).

        @Volatile
        internal var delegatePromptQueueCallback: PromptQueueManager.PromptQueueCallback? = null

        @Volatile
        internal var delegateInputConnectionProvider: (() -> android.view.inputmethod.InputConnection?)? = null

        /**
         * Register the IME's [PipelineOrchestrator.PipelineCallback] as the
         * active delegate. Called from `onServiceConnected`. Pass `null` on
         * unbind to clear.
         */
        fun registerPipelineCallback(callback: PipelineOrchestrator.PipelineCallback?) {
            pipelineCallbackBridgeImpl.setDelegate(callback)
        }

        /**
         * Register the IME's [PromptQueueManager.PromptQueueCallback] as the
         * active delegate. Called from `onServiceConnected`. Pass `null` on
         * unbind to clear.
         */
        fun registerPromptQueueCallback(callback: PromptQueueManager.PromptQueueCallback?) {
            delegatePromptQueueCallback = callback
        }

        /**
         * Register the IME's `InputConnection` supplier so modules can
         * commit text / send key events via [ModuleServices.inputConnectionProvider].
         * Pass `null` on unbind.
         */
        fun registerInputConnectionProvider(provider: (() -> android.view.inputmethod.InputConnection?)?) {
            delegateInputConnectionProvider = provider
        }
    }

    companion object {
        const val TAG: String = "DictatePipelineSvc"
        const val CHANNEL_ID: String = "dictate_pipeline"
        const val NOTIF_ID: Int = 0xD1C7A7E

        /**
         * SharedPreferences file name used by the rest of the app
         * (`DictateInputMethodService.initLongLivedObjects` calls
         * `getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE)`).
         */
        const val PREFS_NAME: String = "net.devemperor.dictate"
    }
}

/**
 * Last-resort [AudioFocusGate] used when [AudioManager] is unavailable
 * (Robolectric environments without the audio service shadow, defensive
 * fallback). All calls succeed silently — the orchestrator's reducer
 * paths stay deterministic.
 */
private object NoOpAudioFocusGate : AudioFocusGate {
    override fun request(): Boolean = true
    override fun abandon() = Unit
}
