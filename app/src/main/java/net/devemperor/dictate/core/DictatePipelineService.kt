package net.devemperor.dictate.core

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.devemperor.dictate.R
import net.devemperor.dictate.ai.AIOrchestrator
import net.devemperor.dictate.ai.prompt.PromptService
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.migration.LegacyAudioFileMigration
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.AudioFileFactory
import net.devemperor.dictate.state.DictateModuleRegistry
import net.devemperor.dictate.state.DictateOrchestrator
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.DictateUiStateStore
import net.devemperor.dictate.state.DispatchOutcome
import net.devemperor.dictate.state.ModuleServices
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.state.PipelineOrphanCleaner
import net.devemperor.dictate.state.PipelinePrefMirror
import net.devemperor.dictate.state.PipelineRecovery
import net.devemperor.dictate.state.PipelineServiceStubSubsystems
import net.devemperor.dictate.state.PipelineSessionRepoAdapter
import net.devemperor.dictate.state.SharedPrefsPersistenceService
import net.devemperor.dictate.state.layout.KeyboardLayoutManager
import net.devemperor.dictate.state.layout.LayoutCatalog
import net.devemperor.dictate.state.layout.LayoutStrings
import net.devemperor.dictate.state.realToastSink
import net.devemperor.dictate.state.render.overlay.AndroidOverlayWindow
import net.devemperor.dictate.state.render.overlay.DefaultOverlayLayoutParamsFactory
import net.devemperor.dictate.state.render.overlay.DefaultOverlayPermissionGate
import net.devemperor.dictate.state.render.overlay.OverlayBackend
import net.devemperor.dictate.state.render.overlay.OverlayPermissionGate
import net.devemperor.dictate.state.render.overlay.OverlayPermissionObserver
import kotlinx.coroutines.launch
import java.util.Locale

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

    // ── C10 — DB-persistence recovery + orphan cleanup ─────────────────
    //
    // [orphanCleaner] runs the dual cleanup pass (deleteInsertedOlderThan +
    // KG-SST-2 orphan-audio) when the service reaches an all-terminal state
    // before stopSelf — see [triggerOrphanCleanupAsync]. [sessionRepoAdapterRef]
    // is held for diagnostic + test-injection paths.
    private var orphanCleaner: PipelineOrphanCleaner? = null
    @Volatile
    private var sessionRepoAdapterRef: PipelineSessionRepoAdapter? = null

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

    // Post-cutover hotfix #3+#4 — service-owned RecordingHardwareAdapter so
    // the LocalBinder can expose its [maxAmplitudeOrNull] poll for the IME's
    // recording-animation side-channel (the legacy 100ms MediaRecorder
    // amplitude polling). Pre-hotfix the adapter was a local `val` in
    // onCreate; the IME had no access to the active MediaRecorder.
    private lateinit var recordingHardwareAdapterImpl: RecordingHardwareAdapter

    // ── C3-B1 — real PipelineRunnerSubsystem (Spec 1 §9.6/§13.3.11) ────
    //
    // Thin JobExecutor.INSTANCE delegation. `lateinit` (set in onCreate
    // Step 4); held as a field for symmetry with the other adapter impls
    // and so a future LocalBinder accessor (C5) can reach the same
    // instance without re-constructing.
    private lateinit var pipelineRunnerSubsystemAdapterImpl: PipelineRunnerSubsystemAdapter

    // ── C4-B2 — real PipelineNotificationCoordinator + ActionRouter ────
    //
    // Spec 1 §7.4/§7.5/§7.6/§11.1.2. The coordinator implements the
    // `PipelineNotificationCoordinatorSubsystem` command interface (wired
    // into ModuleServices, Step 4) and also supplies `buildInitial()` for
    // the Service's `startForeground` call. The router is the
    // notification-action-button → Action back-channel; its
    // `dispatch(intent)` is invoked from `onStartCommand` when a
    // `[Pause]`/`[Stopp]`/`[Senden]`/… button is tapped. Both are
    // `lateinit` (set in onCreate Step 4); held as fields so
    // `onStartCommand`/`onDestroy` reach the same instances without
    // reconstruction (mirrors the C3-B1 adapter field discipline).
    private lateinit var pipelineActionRouterImpl: PipelineActionRouter
    private lateinit var notificationCoordinatorImpl: PipelineNotificationCoordinator

    // ── C11 — Pre-Dispatch audio file allocator (Spec 1 §4.11) ─────────
    //
    // Lives from `onCreate` through `onDestroy`. Held as `lateinit` so
    // the [LocalBinder] surface can hand the same instance to the IME
    // for its pre-dispatch resolver (`startRecording → allocate()`),
    // while `runEffect`-callers consume it via [ModuleServices].
    private lateinit var audioFileFactoryImpl: AudioFileFactory

    // ── C15 — Keyboard layout render orchestration (Spec 2 §11.8 5c) ───
    //
    // The catalog + manager live in the Service so they survive
    // IME-View recreation (rotation, theme switch). The IME constructs
    // an `ImeViewBackend` per `onCreateInputView` and attaches it to
    // the manager via the LocalBinder. The state-collect coroutine
    // below forwards every `DictateUiState` emit into
    // `manager.onStateChanged(...)` so attached backends re-render
    // reactively.
    //
    // `lateinit` because both depend on [Context.getString] (LayoutStrings)
    // and on the orchestrator's onAction sink.
    private lateinit var layoutCatalogImpl: LayoutCatalog
    private lateinit var keyboardLayoutManagerImpl: KeyboardLayoutManager

    /**
     * CR3 (Spec 2 §10 / §11.8 5c) — the Strict-Mode no-double-write
     * ledger, shared by the [KeyboardLayoutManager] fan-out, the legacy
     * [KeyboardStateManager], and the three dormant visibility
     * controllers' [net.devemperor.dictate.state.render.RenderGate]s so
     * the "exactly one live writer per visibility axis" acceptance is
     * observable across the CR3→CR4 staged cutover (render-path-cutover.md
     * §6 RR-2). Single instance per manager → all writers report to the
     * same ledger.
     */
    private val visibilityWriteAuditLoggerImpl =
        net.devemperor.dictate.core.audit.VisibilityWriteAuditLogger()

    // ── C16 — Floating-overlay render backend (Spec 3 §4.2) ────────────
    //
    // [overlayBackendImpl] is constructed here so the Service owns its
    // [OverlayWindow] reference (= the [android.view.WindowManager]
    // indirection), but it is **not yet attached** to the
    // [KeyboardLayoutManager]. C18 wires the attach into the
    // ViewMode-transition logic (KEYBOARD ↔ WIDGET / HOVER per
    // ADR-0005), so the overlay only appears on the user's explicit
    // toggle or the auto-HOVER trigger.
    //
    // C17 wires the real [DefaultOverlayPermissionGate] (Settings.canDrawOverlays
    // + persisted onboarding flags per Spec 3 §5.1) into the backend
    // **and** constructs the [OverlayPermissionObserver] (Spec 3 §5.0)
    // — the single live source for the `state.overlay.hasPermission`
    // axis. The observer is exposed to the IME via [LocalBinder] so
    // IME-lifecycle hooks can call `refresh()` after a user returns
    // from System Settings.
    private var overlayBackendImpl: OverlayBackend? = null
    private lateinit var overlayPermissionGateImpl: OverlayPermissionGate
    private lateinit var overlayPermissionObserverImpl: OverlayPermissionObserver

    /**
     * Tracks whether [overlayBackendImpl] is currently registered with
     * the [KeyboardLayoutManager]. The collector below flips this on
     * the Triangle-FSM transitions (T1–T7); the boolean prevents a
     * duplicate `attachBackend` (which raises `IllegalStateException`)
     * and a no-op `detachBackend` per state-emit.
     *
     * Accessed only from the single state-collect coroutine
     * (`Dispatchers.Main.immediate`), so no synchronisation is needed.
     */
    private var overlayBackendAttached: Boolean = false

    /**
     * Service-owned [ModuleServices] DI container. Promoted from a
     * local `val` to a field in C15 so the IME can hand the same
     * reference to `ImeViewBackend` (its `actionResolver`s need
     * `audioFileFactory.allocate()` at click time — Spec 1 §4.11 +
     * Spec 2 §6 `services` parameter).
     */
    private lateinit var moduleServicesImpl: ModuleServices

    /** Single binder instance — Spec 1 §11.3.4 Multi-Bind. */
    private val binder: LocalBinder = LocalBinder()

    private var notificationChannelReady: Boolean = false

    /**
     * Test-visibility hook: `true` after [ensureNotificationChannel].
     */
    val isNotificationChannelReady: Boolean
        get() = notificationChannelReady

    override fun onCreate() {
        Log.i("DictateTrace", "Service.onCreate()")
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

        // Block A2 (recording-stack-completion) — instantiate the audio
        // repository EARLY so the PipelineOrchestrator below can consume
        // it via readForPipeline(). The same instance is reused later by
        // the RecordingHardwareAdapter + ContinuationLookup + repo adapter
        // (single instance, no state leak: the repository is stateless).
        val audioFileRepository = CacheDirAudioFileRepository(
            cacheDirProvider = { applicationContext.cacheDir },
        )

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
            audioFileRepository,
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
        // (forward-reference captured below). The RecordingHardwareAdapter
        // is also held as a service field [recordingHardwareAdapterImpl] so
        // the LocalBinder can expose [RecordingHardwareAdapter.maxAmplitudeOrNull]
        // for the IME's amplitude-polling side-channel (post-cutover #3+#4).
        // Audio-File Repository (ADR-0007) is service-owned and shared
        // with the RecordingHardwareAdapter (for Rolling-Segments via
        // allocateNext) and the future B2 Cold-Resume path (read
        // codec-params + reuse session-id). The rolling interval comes
        // from `Pref.RollingSegmentIntervalSec` (default 30 s); if the
        // user changes it, the new value takes effect on the next
        // service-start (no live re-read — recording in flight keeps
        // its interval).
        // Block A2 — the AudioFileRepository was already instantiated
        // earlier (see comment above PipelineOrchestrator). Reuse the
        // same instance here so the adapter + repository + continuation-
        // lookup all share one stateless coordinator.
        val rollingIntervalSec: Long = run {
            val pref = net.devemperor.dictate.preferences.Pref.RollingSegmentIntervalSec
            sharedPrefs.getLong(pref.key, pref.default)
        }
        recordingHardwareAdapterImpl = RecordingHardwareAdapter(
            emitAction = { action -> orchestrator.emitAction(action) },
            audioFileRepository = audioFileRepository,
            rollingIntervalMs = rollingIntervalSec * 1000L,
            // B1.3-hotfix: Rolling-Segments are now MediaRecorder
            // OnInfoListener-driven (no Kotlin coroutine timer), so no
            // CoroutineScope is needed any more.
        )
        val recordingHardware = recordingHardwareAdapterImpl
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
        // C10 — real DB-backed PipelineSessionRepoAdapter (replaces the C7
        // stubSessionRepo). Adapter is constructed first so it can be wired
        // into both ModuleServices.sessionRepo AND PipelineRecovery (the
        // recovery class reads via the same Adapter so the §6.3 algorithm
        // and the steady-state pendingFlow share a single DAO instance).
        // B3-VAL-W1 F-12 — Run the legacy-audio migration synchronously
        // BEFORE constructing the orchestrator + adapters. The migration
        // promotes leftover `cacheDir/audio.m4a`-referencing sessions to
        // FAILED; if recovery (which runs in orchestrator init {}) reads
        // those rows concurrently, the resulting `last_error_message`
        // is non-deterministic (`recording-interrupted-by-process-death`
        // vs. `audio_file_path_legacy_purged`). Synchronous + ordered
        // first yields a deterministic post-migration state. Migration
        // is sub-100ms per its own KDoc, so the FGS 5s budget holds.
        try {
            LegacyAudioFileMigration.run(applicationContext)
        } catch (t: Throwable) {
            // Migration is best-effort. A DB or pref failure must not
            // crash the service boot — the next service start retries.
            Log.w(TAG, "LegacyAudioFileMigration failed", t)
        }

        val sessionRepoAdapterImpl = PipelineSessionRepoAdapter(
            sessionDao = database.sessionDao(),
            // Block A1 (recording-stack-completion) — wire the audio
            // repository so the adapter's `syncAudioFilePaths` can read
            // the live segment list. The same `audioFileRepository`
            // instance is shared with the RecordingHardwareAdapter +
            // ContinuationLookup below.
            audioFileRepository = audioFileRepository,
            // F-2 freshness floor — gate legacy pre-M4 rows so
            // NotifyManualPasteNeeded doesn't flood on first post-upgrade
            // boot (Spec 1 §6.5 + Pref.PendingInsertionFreshnessMs).
            pendingInsertionFreshnessFloor = {
                System.currentTimeMillis() - sharedPrefs.get(Pref.PendingInsertionFreshnessMs)
            },
        )
        sessionRepoAdapterRef = sessionRepoAdapterImpl
        orphanCleaner = PipelineOrphanCleaner(database.sessionDao())

        // C11 — Pre-Dispatch audio-file allocator (Spec 1 §4.11). Replaces
        // the C7 stub. The factory is `lateinit` (see field declaration)
        // so the LocalBinder can expose it to the IME's startRecording
        // resolver while ModuleServices.audioFileFactory routes the
        // orchestrator-side pre-dispatch.
        audioFileFactoryImpl = CacheDirAudioFileFactory(
            cacheDirProvider = { applicationContext.cacheDir },
        )

        // C3-B1 — real PipelineRunnerSubsystem (Spec 1 §9.6/§13.3.11).
        // Thin delegation to JobExecutor.INSTANCE (OQ-1 thin-delegation
        // option; PipelineOrchestrator NOT rewritten). Submit-direction
        // only — the IME's legacy JobExecutor.start path stays
        // authoritative until C5/C7 (Epic §6.2). R-1: the
        // fresh-recording config resolver is the C5 insertion point;
        // the C3 default resolver throws for IME-runtime-only fields
        // rather than silently defaulting them (silent data loss is the
        // R-1 failure mode). applicationContext is used for
        // JobExecutor.start's Room failure-path (outlives any IME view).
        // C5 — the resolver is a DelegatingPipelineConfigResolver: it
        // forwards to the IME-registered resolver (installed from
        // `onServiceConnected` via
        // `LocalBinder.registerPipelineConfigResolver`, which snapshots
        // the IME's live recording config field-for-field per R-1) and
        // falls back to the C3 DefaultPipelineConfigResolver (which
        // throws for the fresh IME-runtime-only fields) when no IME is
        // bound.
        pipelineRunnerSubsystemAdapterImpl = PipelineRunnerSubsystemAdapter(
            context = applicationContext,
            configResolver = DelegatingPipelineConfigResolver(
                fallback = DefaultPipelineConfigResolver(
                    filesDirProvider = { filesDir },
                ),
                imeResolverProvider = { binder.delegatePipelineConfigResolver },
            ),
        )

        // C4-B2 — real notification coordinator + action router (Spec 1
        // §7.4/§7.5/§7.6/§11.1.2). Replaces the
        // `PipelineServiceStubSubsystems.notificationCoordinator` no-op.
        // The router dispatches decoded notification-button actions via
        // a late-bound `orchestrator.dispatch` lambda (the orchestrator
        // is constructed below — same construction-order pattern as
        // `emitAction`). The coordinator does NOT create a second
        // NotificationChannel — it reuses the one
        // `ensureNotificationChannel()` (Step 1) already created
        // (R-2: channel-before-startForeground ordering preserved).
        pipelineActionRouterImpl = PipelineActionRouter(
            dispatchAction = { action -> orchestrator.dispatch(action) },
        )
        notificationCoordinatorImpl = PipelineNotificationCoordinator(
            context = this,
            actionRouter = pipelineActionRouterImpl,
        )

        // B2 / ADR-0008 §"Auto-Continuation" — ContinuationLookup composite.
        // Reuses the service-owned SessionTracker (DB query) and the same
        // AudioFileRepository (allocateNext + segment-list). The freshness
        // window is read live from `Pref.ContinuationFreshnessMs` so the
        // user can adjust the 24h default without rebinding.
        val continuationLookup = RecordingContinuationLookup(
            sessionTracker = sessionTrackerImpl,
            audioFileRepository = audioFileRepository,
            freshnessMsSupplier = { sharedPrefs.get(Pref.ContinuationFreshnessMs) },
        )

        moduleServicesImpl = ModuleServices(
            recordingHardware = recordingHardware,
            bluetoothSco = bluetoothSco,
            audioFocus = audioFocus,
            recordingTimer = recordingTimer,
            amplitudeStream = amplitudeStream,
            borderGlow = borderGlow,
            pipelineRunner = pipelineRunnerSubsystemAdapterImpl,
            sessionRepo = sessionRepoAdapterImpl,
            notificationCoordinator = notificationCoordinatorImpl,
            inputConnectionProvider = { binder.delegateInputConnectionProvider?.invoke() },
            clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager,
            sharedPrefs = sharedPrefs,
            // Chunk 3.0 (indirection-cleanup) — typed State → SP write
            // seam for module Effects. Production wraps the same
            // `sharedPrefs` reference used elsewhere; tests substitute a
            // recording fake.
            prefs = SharedPrefsPersistenceService(sharedPrefs),
            toastSink = realToastSink(applicationContext),
            audioFileFactory = audioFileFactoryImpl,
            audioFileRepository = audioFileRepository,
            continuationLookup = continuationLookup,
            scope = serviceScope,
            emitAction = { action -> orchestrator.emitAction(action) },
        )

        prefMirror = PipelinePrefMirror(sharedPrefs)
        // C10 — Recovery now drives the full §6.3 algorithm via the DAO and
        // dispatches `ResendAction.NotifyManualPasteNeeded` for SF-4 (the
        // post-extraction manual-paste hint). emitAction is captured by-value
        // here so the orchestrator-init's `recovery.recover(store)` launches
        // with the correct sink.
        val recovery = PipelineRecovery(
            sessionDao = database.sessionDao(),
            sessionRepo = sessionRepoAdapterImpl,
            emitAction = { action -> orchestrator.emitAction(action) },
            // F-2 freshness floor — same supplier as the adapter so
            // Phase 4 SF-4 dispatch + Phase 2 loadPending agree on the
            // legacy-row cutoff.
            pendingInsertionFreshnessFloor = {
                System.currentTimeMillis() - sharedPrefs.get(Pref.PendingInsertionFreshnessMs)
            },
            // Continuation-freshness — Phase 5 auto-surfacing reuses the
            // same window the auto-continuation lookup uses (a recording
            // older than this is not offered for resume).
            continuationFreshnessMs = { sharedPrefs.get(Pref.ContinuationFreshnessMs) },
            // 2026-05-22 — elapsed-ms for the recovery auto-surfacing:
            // sum the on-disk segment durations so the surfaced
            // RecordingState.Interrupted freezes its timer at the real
            // recorded length (the user's "0:08").
            interruptedRecordingElapsedMsProvider = { sessionId ->
                audioFileRepository.segments(sessionId).sumOf { segment ->
                    recordingRepositoryImpl.extractDurationSeconds(segment)
                } * 1000L
            },
        )

        orchestrator = DictateOrchestrator(
            scope = serviceScope,
            store = store,
            services = moduleServicesImpl,
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

        // ──────────────────────────────────────────────────────────────
        // Step 5 — Legacy audio-file migration moved to BEFORE
        // orchestrator construction (B3-VAL-W1 F-12). See the comment
        // block at the top of the adapter wiring above.
        // ──────────────────────────────────────────────────────────────

        // ──────────────────────────────────────────────────────────────
        // Step 6 — Crash-orphan cleanup (Spec 1 §4.11.5.1 step 8).
        //
        // Async via `Dispatchers.IO` so the FGS-5-second start budget
        // (§11.1.4) is preserved. Reads every non-null `audio_file_path`
        // from the DB, builds the "referenced" set, then deletes every
        // file in `cacheDir/audio/` that matches the factory naming
        // scheme AND is older than the 60-second freshness cut-off
        // (KG-AFF-4 — closes the allocate → MediaRecorder.prepare race).
        // Errors stay best-effort — a DB failure mid-boot is logged but
        // does not kill the service.
        // ──────────────────────────────────────────────────────────────
        serviceScope.launch(Dispatchers.IO) {
            try {
                val referenced = database.sessionDao()
                    .findAllAudioFilePaths()
                    .filterNotNull()
                    .toSet()
                audioFileFactoryImpl.cleanupOrphans(referenced)
            } catch (t: Throwable) {
                Log.w(TAG, "audio-file orphan cleanup failed at boot", t)
            }
        }

        // ──────────────────────────────────────────────────────────────
        // Step 7 — Keyboard layout render-orchestrator wiring (C15 / Spec 2 §11.8 5c).
        //
        // The catalog + manager are constructed here so they survive
        // IME-View recreation. The IME's `onCreateInputView` later
        // builds an `ImeViewBackend` and attaches it via the
        // LocalBinder. The state-collect below forwards every
        // `DictateUiState` emit into `manager.onStateChanged(...)`;
        // attached backends re-render reactively without the IME
        // needing its own collect.
        // ──────────────────────────────────────────────────────────────
        layoutCatalogImpl = LayoutCatalog(buildLayoutStrings())
        keyboardLayoutManagerImpl = KeyboardLayoutManager(
            catalog = layoutCatalogImpl,
            onAction = { action ->
                // Backend click → orchestrator dispatch. The manager
                // owns the single click-sink; backends turn user
                // clicks into Actions and push them through this
                // pipe (F-8 Single-Dispatch).
                orchestrator.dispatch(action)
            },
            // CR3 — open one audit render-generation per state-emit so
            // the no-double-write ledger keys per fan-out (RR-2, Spec 2
            // §10 / §11.8 5c).
            visibilityAuditLogger = visibilityWriteAuditLoggerImpl,
        )
        serviceScope.launch {
            orchestrator.state.collect { state ->
                // C18 — Triangle-FSM ↔ OverlayBackend attach/detach
                // (Spec 3 §6 + §7.2 + ADR-0005). The manager owns the
                // backend list; we toggle the OverlayBackend membership
                // on viewMode-axis transitions BEFORE forwarding the
                // state so the freshly-attached backend sees its first
                // render with the correct snapshot.
                //
                // The whole body is wrapped: a render exception in ONE
                // backend (e.g. a transient inflate failure on the
                // overlay window) must not cancel the state-collect
                // coroutine — that would silently freeze the
                // notification, DB, and every other state subscriber
                // for the rest of the process lifetime. C18 is the
                // first chunk that attaches the OverlayBackend to the
                // live manager, so this guard lands here. Per-emit
                // isolation keeps the pipeline alive; the next emit
                // re-attempts the render via the manager's fan-out.
                try {
                    syncOverlayBackendAttachment(state)
                    keyboardLayoutManagerImpl.onStateChanged(state)
                } catch (t: Throwable) {
                    Log.w(TAG, "state-collect render pass failed (isolated, pipeline continues)", t)
                }
            }
        }

        // ──────────────────────────────────────────────────────────────
        // Step 8 — Floating-overlay backend construction (C16, Spec 3 §4.2)
        //          + Permission gate / observer (C17, Spec 3 §5.0 + §5.1).
        //
        // The Service owns the WindowManager reference so the same
        // OverlayBackend instance survives IME-View recreation
        // (rotation, theme switch). The backend is attached/detached
        // reactively by [syncOverlayBackendAttachment] in the
        // state-collect coroutine above (C18, Spec 3 §6 + §7.2,
        // ADR-0005) — the window only appears on user-toggle (WIDGET)
        // or auto (HOVER). The default constructor wires
        // DefaultOverlayPositionMapper + DefaultOverlayDragControllerFactory
        // (both `ctx`-bound, Spec 3 §4.6 + §4.7).
        //
        // The permission gate + observer are constructed **before** the
        // backend so the backend's `permissions` parameter wires through
        // the production gate. `observer.init()` is called below — that
        // dispatch sets `state.overlay.hasPermission` to the live system
        // value before any subscriber collects the first emission.
        // ──────────────────────────────────────────────────────────────
        overlayPermissionGateImpl = DefaultOverlayPermissionGate(
            ctx = this,
            prefs = sharedPrefs,
        )
        overlayPermissionObserverImpl = OverlayPermissionObserver(
            gate = overlayPermissionGateImpl,
            // The dispatch sink is captured by-reference (orchestrator
            // is `lateinit val`-initialised above); the lambda survives
            // the entire observer lifetime.
            dispatch = { action -> orchestrator.dispatch(action) },
        )

        val windowManager: WindowManager? =
            getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager != null) {
            // Side-channel renderer factories for the overlay surface
            // (dictate-widget-integration §8.1 Chunks 1.3-1.4). The
            // factories run AFTER `OverlayBackend.inflateAndAttach`
            // inflates the overlay layout — they receive the live
            // `overlay_record_btn` + `overlay_pulse_layout` views and
            // return renderer instances symmetric to the IME-View
            // backend's (constructed in `attachImeViewBackendIfReady`).
            // The same classes are reused; only the bound View instance
            // differs — User-Req: "exakt den gleichen Button" =
            // identical wiring, independent View instances.
            val animationsEnabledLambda: () -> Boolean = {
                sharedPrefs.get(Pref.Animations)
            }
            val recordingAnimationFactory =
                net.devemperor.dictate.state.render.overlay.RecordingAnimationControllerFactory {
                    recordButton ->
                    val ctx = recordButton.context
                    val displayDensity = ctx.resources.displayMetrics.density
                    val animation = net.devemperor.dictate.widget.BorderGlowAnimation(
                        sharedPrefs.get(Pref.AccentColor),
                        androidx.appcompat.content.res.AppCompatResources.getDrawable(
                            ctx, net.devemperor.dictate.R.drawable.ic_baseline_send_20,
                        ),
                        net.devemperor.dictate.widget.AmplitudeVisualizerDrawable
                            .BarCountMode.Fixed(30),
                        0.35f,
                        displayDensity,
                    )
                    animation.prepare(recordButton)
                    net.devemperor.dictate.state.render.RecordingAnimationController(
                        animation,
                        recordButton,
                        { sharedPrefs.get(Pref.AccentColor) },
                        animationsEnabledLambda,
                    )
                }
            val autoEnterFactory =
                net.devemperor.dictate.state.render.overlay.AutoEnterRendererFactory {
                    recordButton ->
                    net.devemperor.dictate.state.render.AutoEnterRenderer(recordButton)
                }
            val colorFactory =
                net.devemperor.dictate.state.render.overlay.RecordButtonColorControllerFactory {
                    recordButton ->
                    net.devemperor.dictate.state.render.RecordButtonColorController(recordButton)
                }

            overlayBackendImpl = OverlayBackend(
                ctx = this,
                services = moduleServicesImpl,
                overlayWindow = AndroidOverlayWindow(windowManager),
                permissions = overlayPermissionGateImpl,
                layoutParamsFactory = DefaultOverlayLayoutParamsFactory(this),
                recordingAnimationControllerFactory = recordingAnimationFactory,
                autoEnterRendererFactory = autoEnterFactory,
                recordButtonColorControllerFactory = colorFactory,
                // §8.3 Chunk 3.1+3.2 — late-bound affordance via the
                // LocalBinder. Captured at click time so it sees the
                // currently-registered IME lambda (or null if IME
                // unbound, in which case the click is a no-op).
                //
                // Plan §8.1 R-3 mitigation (fix-wave G3): if the click
                // lands during the pre-bind race window (overlay shown
                // but IME hasn't called registerImeSideAffordance yet),
                // the delegate is null and the click is dropped. Log it
                // so a recurring "first click after boot is a no-op"
                // user report has a breadcrumb to grep for — without
                // this log the drop would be invisible in logcat.
                imeSideAffordance = { id, isLongPress ->
                    val delegate = binder.delegateImeSideAffordance
                    if (delegate == null) {
                        Log.w(
                            TAG,
                            "affordance hook unwired at click time (pre-bind race); " +
                                "event dropped: id=$id isLongPress=$isLongPress",
                        )
                    } else {
                        delegate.invoke(id, isLongPress)
                    }
                },
            )
        } else {
            // Defensive: a Service without WindowManager (e.g. an
            // isolated-process Robolectric environment) skips the
            // overlay path entirely. WIDGET / HOVER ViewModes won't
            // render anything until a real WindowManager is available.
            Log.w(TAG, "WindowManager service unavailable — overlay backend disabled")
        }

        // Initial one-shot permission dispatch (Spec 3 §5.0). The
        // observer reads `Settings.canDrawOverlays` via the gate and
        // dispatches the boolean through the orchestrator — the
        // OverlayModule reducer arm filters the no-op case by equality
        // so a repeat boot with the same value produces a Rejected
        // outcome rather than a cascade.
        overlayPermissionObserverImpl.init()
    }

    /**
     * Wire the Triangle-FSM (ADR-0005) into the [OverlayBackend]'s
     * attach/detach lifecycle (Spec 3 §6 + §7.2).
     *
     * The [KeyboardLayoutManager] already picks `OVERLAY_5BUTTON` for
     * WIDGET / HOVER and routes the render-tick to backends whose
     * [net.devemperor.dictate.state.layout.RenderBackend.backendType]
     * matches. But the manager only renders to **attached** backends —
     * a permanently-attached OverlayBackend would keep a
     * `WindowManager` view alive in KEYBOARD mode. So the backend's
     * membership is toggled per viewMode-axis transition:
     *
     * | Transition | Action |
     * |------------|--------|
     * | T1 KEYBOARD → WIDGET | attach (permission-gated by the backend's own render guard) |
     * | T2 WIDGET → KEYBOARD | detach |
     * | T3 KEYBOARD → HOVER  | attach |
     * | T4 WIDGET → HOVER    | no-op (already attached) |
     * | T5 HOVER → KEYBOARD  | detach |
     * | T6 HOVER → WIDGET    | no-op (already attached) |
     * | T7 HOVER → KEYBOARD (pipeline-done cascade) | detach (T5-equivalent) |
     *
     * The classification collapses to a single rule: **attach iff
     * `viewMode != KEYBOARD`**. The overlay window is the union of
     * WIDGET + HOVER (Spec 3 §3.1 — both render `OVERLAY_5BUTTON`);
     * KEYBOARD is the only mode that needs the overlay torn down. T4
     * and T6 stay in the overlay union so no churn happens; T7 is
     * structurally identical to T5 (both land on KEYBOARD) so it needs
     * no special arm — the "Geist-Widget" structural protection is
     * already in `ViewModeModule.reduce` (the cascade settles
     * `viewMode = KEYBOARD`, this collector then detaches).
     *
     * **Permission gate (T1 / T3):** attaching does **not** force the
     * window open — [OverlayBackend.render] bails at its
     * `state.overlay.hasPermission == false` guard (Spec 3 §5.4). So a
     * permission-less attach is a cheap no-op; the backend simply never
     * inflates. This keeps the permission check single-sourced in the
     * backend's render path (no duplicate `Settings.canDrawOverlays`
     * read here).
     *
     * No-op when the overlay backend is unavailable (a Service without
     * a `WindowManager`, e.g. Robolectric isolated process — see
     * [onCreate] Step 8).
     *
     * @see docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md
     * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md §6 §7.2
     */
    private fun syncOverlayBackendAttachment(state: net.devemperor.dictate.state.DictateUiState) {
        val backend = overlayBackendImpl ?: return
        // 2026-05-23 sticky-widget refactor (final) — `state.widget` is
        // the sole source of truth for the floating overlay's window
        // attachment. The legacy `state.viewMode` axis used to drive
        // this decision, but the post-refactor invariant is "the widget
        // is open iff `state.widget is Visible`". Reading both axes
        // (the prior `viewMode != KEYBOARD || widget is Visible` form)
        // created a class of bugs where a stale `viewMode` blocked the
        // detach even after the user had explicitly closed the widget,
        // or vice-versa: the X-click resolver routed through `CloseOverlay`
        // (which only mutates `viewMode`) and the window stayed alive
        // because `widget` was still Visible. The OR form was a half-
        // fix; the proper fix is a single source of truth. ViewMode
        // bookkeeping continues for the renderer-mode (HOVER vs WIDGET
        // affects which slots the catalog produces) but no longer
        // gates window attach/detach.
        val shouldBeAttached =
            state.widget is net.devemperor.dictate.state.WidgetState.Visible
        if (shouldBeAttached == overlayBackendAttached) return

        if (shouldBeAttached) {
            // Flip the bookkeeping bit FIRST: `attachBackend` performs
            // an immediate first-render, and a backend whose first
            // render throws (e.g. a transient inflate failure) must
            // still count as attached so the matching detach fires on
            // the next KEYBOARD transition. Without this ordering a
            // render-exception (re-thrown out of `attachBackend`,
            // isolated by the collector's catch) would strand the
            // backend in the manager's list with
            // `overlayBackendAttached == false`, and the cleanup
            // detach would never run (window leak).
            overlayBackendAttached = true
            keyboardLayoutManagerImpl.attachBackend(backend)
        } else {
            // F-13 (B5) — symmetric to the attach-path flag-first
            // ordering above. Clearing `overlayBackendAttached = false`
            // BEFORE `detachBackend(backend)` is correct only because
            // `KeyboardLayoutManager.detachBackend` removes the backend
            // from `activeBackends` *before* calling `backend.detach()`:
            // so even a throwing `detach()` leaves a consistent state
            // (the backend is already out of the list, and a later
            // re-attach's `check(backend !in activeBackends)` passes).
            // This is a cross-class ordering guarantee — do not reorder
            // either side without revisiting both.
            overlayBackendAttached = false
            keyboardLayoutManagerImpl.detachBackend(backend)
        }
    }

    /**
     * Build the [LayoutStrings] bundle from the service's Android
     * Context. Captured at service-construction time (Android string
     * resources don't change at runtime within a process). Re-built
     * if the IME re-attaches (e.g. theme switch) — but for that the
     * IME goes through `onCreateInputView` which re-attaches the
     * backend; the strings themselves stay stable.
     *
     * **F-15 (Epic §4 Block A2):** `dictateButtonText` now receives the
     * effective-language code (`DictateUiState.language.effective`,
     * threaded by [resolveRecordButtonText]) and produces a
     * language-suffixed label (e.g. `"Record (en)"`). This is a
     * **read-only** consumption of the `LanguageModule` state axis. D-13
     * (Epic §4 Block C1) is now closed: the legacy effective-language
     * writer is deleted and `LanguageState.effective` is fed by the IME's
     * payload-bearing `LanguageAction.RefreshFromPref` dispatch (resolved
     * from prefs via `preferences.LanguageResolver`), so this read now
     * reflects a live value rather than the `"system"` boot sentinel.
     */
    private fun buildLayoutStrings(): LayoutStrings = LayoutStrings(
        record = getString(R.string.dictate_record),
        send = getString(R.string.dictate_send, getString(R.string.dictate_record)),
        sending = getString(R.string.dictate_sending),
        // F-15 — language-aware label. `effectiveLanguage` is
        // `DictateUiState.language.effective` (LanguageModule axis). The
        // `"system"` sentinel (boot default before the pref resolves)
        // renders the plain "Record" label; any concrete language code
        // is suffixed so the button reflects the current language. The
        // legacy `MainButtonsController.updateRecordButtonText` path
        // still runs in Phase 1; this is the new render path's source.
        dictateButtonText = { effectiveLanguage ->
            if (effectiveLanguage.isEmpty() || effectiveLanguage == "system") {
                getString(R.string.dictate_record)
            } else {
                "${getString(R.string.dictate_record)} ($effectiveLanguage)"
            }
        },
        formatStagingLabel = { audioDurationSeconds ->
            // Defensive default — Spec 1 §3 `ReprocessStaging` will
            // grow `audioDurationSeconds`; format as MM:SS.
            // Locale.US: technical format — keeps digits ASCII regardless
            // of device locale (B4-VAL F-5, mirrors RecordingAnimationController).
            val minutes = audioDurationSeconds / 60
            val seconds = audioDurationSeconds % 60
            String.format(Locale.US, "Audio %d:%02d · Send", minutes, seconds)
        },
        formatPipelineLabel = { stepName, completedSteps, totalSteps, autoEnterActive, elapsedMs ->
            // Live `stepName / completedSteps / totalSteps / elapsedMs`
            // come from `PipelineUiState.Running` via
            // `resolveRecordButtonTextPipeline` (F-13, Epic §4 Block A1
            // + B-D-1, dictate-pipeline-render-and-state-unification §5.1).
            //
            // Layout (OQ-1 Variante A — two-line):
            //   - With a non-empty stepName:
            //     `"<stepName>\n<N>/<M>[ ↵] <M>:<SS>"`
            //   - Without a step name (between steps / right after
            //     StartPipeline before first StepStarted): single-line
            //     legacy shape `"<N>/<M>[ ↵] <M>:<SS>"` so the empty-name
            //     window doesn't shrink the button to one line + back
            //     to two on every step boundary (a UX-jitter the
            //     Variante-A two-line layout otherwise causes).
            //
            // Locale.US: technical format — keeps ASCII digits
            // regardless of device locale (B4-VAL F-5, mirrors
            // RecordingAnimationController + the F-13 formatter
            // contract).
            val seconds = (elapsedMs / 1000L).toInt()
            val mm = seconds / 60
            val ss = seconds % 60
            val arrow = if (autoEnterActive) " ↵" else ""
            val phase = stepName?.takeIf { it.isNotBlank() }
            if (phase != null) {
                String.format(
                    Locale.US,
                    "%s\n%d/%d%s %d:%02d",
                    phase, completedSteps, totalSteps, arrow, mm, ss,
                )
            } else {
                String.format(
                    Locale.US,
                    "%d/%d%s %d:%02d",
                    completedSteps, totalSteps, arrow, mm, ss,
                )
            }
        },
        formatPreparingLabel = { autoEnterActive ->
            // #AE-DEEP2 — Preparing carries an autoEnter-toggle too; append
            // the same ↵ marker the formatPipelineLabel formatter uses so
            // the two upload phases (Preparing then Running) read the same
            // visual cue. Localised base via R.string.dictate_sending.
            if (autoEnterActive) {
                getString(R.string.dictate_sending) + " ↵"
            } else {
                getString(R.string.dictate_sending)
            }
        },
        // B3.4 — Pause-Toggle labels. The overlay record-button morphs
        // into a Pause/Resume toggle when the widget is visible
        // (resolveOverlayRecordAction + resolveOverlayRecordButtonText).
        // Strings reuse the existing notification-action labels for
        // pause/resume so localisation stays in one place.
        pauseLabel = getString(R.string.dictate_action_pause),
        resumeLabel = getString(R.string.dictate_action_resume),
    )

    /**
     * Kick off the dual cleanup pass (Spec 1 §6.2 R.17 + §6.3.1):
     *
     *  1. `deleteInsertedOlderThan(cutoff)` — drop COMPLETED rows whose
     *     `inserted_at` is older than `now − Pref.SessionCleanupGracePeriodMs`.
     *  2. `cleanupOrphanedTerminalAudio(cutoff)` — drop audio files for
     *     FAILED/CANCELLED rows older than the same cutoff; bulk-clear
     *     `audio_file_path` in DB.
     *
     * Launches into [serviceScope] so the cleanup doesn't block
     * `onDestroy` (Android can SIGKILL the service mid-flight; every step
     * is idempotent and will retry on next boot via [PipelineRecovery] +
     * the boot-time orphan scan).
     *
     * Visibility: `internal` so test-only callers can invoke the cleanup
     * directly without simulating an `onDestroy` round-trip.
     */
    internal fun triggerOrphanCleanupAsync() {
        val cleaner = orphanCleaner ?: return
        val sharedPrefs = try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (t: Throwable) {
            Log.w(TAG, "orphan-cleanup: shared-prefs lookup failed", t)
            return
        }
        val gracePeriodMs = sharedPrefs.get(Pref.SessionCleanupGracePeriodMs)
        serviceScope.launch {
            try {
                val result = cleaner.cleanup(gracePeriodMs)
                Log.i(
                    TAG,
                    "orphan-cleanup: deletedCompletedRows=${result.deletedCompletedRows}, " +
                        "filesActuallyDeleted=${result.filesActuallyDeleted}, " +
                        "clearedAudioPathRows=${result.clearedAudioPathRows}",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "orphan-cleanup failed", t)
            }
        }

        // Second cleanup pass — cache-audio (`cache/audio/`) periodic
        // sweep (recording-stack-completion §4.5.2). The scheduler
        // self-gates on Pref.CacheCleanupLastRunMs so this onDestroy
        // tick is a no-op if the app-onCreate tick already ran within
        // the 24h interval. Two trigger sites give the 24h cadence
        // resilience against either "app rarely opened" (then service-
        // onDestroy is the path) or "service rarely stopped" (then
        // app-onCreate is the path).
        net.devemperor.dictate.audio.CacheAudioCleanupScheduler.scheduleFromApp(
            cacheDirProvider = { cacheDir },
            prefs = sharedPrefs,
            sessionDao = net.devemperor.dictate.database.DictateDatabase
                .getInstance(this)
                .sessionDao(),
        )
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
        Log.i("DictateTrace", "Service.onStartCommand(action=${intent?.action} flags=$flags startId=$startId)")
        // C4-B2 — notification-action back-channel (Spec 1 §7.3/§7.5).
        // A tap on a `[Pause]`/`[Stopp]`/`[Senden]`/`[Abbrechen]`/
        // `[Einfügen]`/`[Verwerfen]` button re-enters here; the router
        // decodes the intent's action and forwards the typed Action to
        // the orchestrator. Non-action starts (the first FGS start)
        // carry no `intent.action` and are ignored by the router.
        if (::pipelineActionRouterImpl.isInitialized) {
            try {
                pipelineActionRouterImpl.dispatch(intent)
            } catch (t: Throwable) {
                // R-2: an action-decode failure must not abort the FGS
                // start below (which keeps recording alive). Log + carry
                // on to startForeground.
                Log.w(TAG, "notification-action dispatch failed", t)
            }
        }
        try {
            // C4-B2 — `buildInitial()` is the coordinator's pure,
            // in-memory state→notification render (Spec 1 §7.4); safe
            // within the FGS-5-second budget (no DB/IO).
            startForegroundCompat(notificationCoordinatorImpl.buildInitial())
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
        // B1.4 (FGS Crash-Resilience): `START_REDELIVER_INTENT` so the
        // system re-delivers the last `startForegroundService` Intent
        // after an OOM-kill. Combined with `PipelineRecovery.recover()`
        // in `DictateOrchestrator.init`, this gives the service a
        // chance to resume its work on the row it was processing when
        // it died, instead of starting blank.
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i("DictateTrace", "Service.onBind()")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try {
            val s = orchestrator.state.value
            Log.i(
                "DictateTrace",
                "Service.onUnbind() recording=${s.recording::class.simpleName} " +
                    "pipeline=${s.pipeline::class.simpleName} viewMode=${s.viewMode}"
            )
        } catch (t: Throwable) {
            Log.w("DictateTrace", "snapshot in Service.onUnbind failed", t)
        }
        return super.onUnbind(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i("DictateTrace", "Service.onTaskRemoved()")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            val s = orchestrator.state.value
            Log.i(
                "DictateTrace",
                "Service.onDestroy() recording=${s.recording::class.simpleName} " +
                    "pipeline=${s.pipeline::class.simpleName} viewMode=${s.viewMode}"
            )
        } catch (t: Throwable) {
            Log.w("DictateTrace", "snapshot in Service.onDestroy failed", t)
        }
        // C15 — Detach any backends that the IME left attached. The
        // KeyboardLayoutManager owns the View references, so a clean
        // detach here prevents leaks (the IME's `onDestroy` runs in
        // parallel; whichever fires first wins, both paths are
        // idempotent via KeyboardLayoutManager.detachAll).
        if (::keyboardLayoutManagerImpl.isInitialized) {
            try {
                keyboardLayoutManagerImpl.detachAll()
            } catch (t: Throwable) {
                Log.w(TAG, "keyboardLayoutManager.detachAll failed", t)
            }
        }

        // C10 — Service-idle cleanup slot (Spec 1 §6.2 R.17 + §6.3.1).
        // When the service is reaching its terminal state (onDestroy =
        // Android-side decision to stop), kick off the dual cleanup pass.
        // The pass runs best-effort in the background; the service may be
        // killed mid-flight (acceptable — every cleanup step is idempotent
        // and re-runnable on the next boot's recovery pass).
        triggerOrphanCleanupAsync()

        // 2026-05-22 — the previous Pre-Cancel-Dispatch (B3-VAL-W1 F-3,
        // ADR-0003) was the root cause of "Aufnahme geht verloren bei
        // Tastaturwechsel". When Android tears down the IME-Service
        // (user switches to a different keyboard), the IME's unbindService
        // brings DictatePipelineService.onDestroy down with it. Dispatching
        // CancelRecording here runs the StopMediaRecorder + DeleteAudioFile
        // effects synchronously → the in-flight recording's audio file is
        // *deleted* before the process exits. The Native-heap-leak argument
        // in the original comment is moot: the process dies milliseconds
        // later, and the OS reclaims every native allocation.
        //
        // Letting the recording state stay non-Idle is intentional. The
        // B2a RECORDING_INTERRUPTED migration + the next service-start's
        // recovery pass (PipelineRecovery + ContinuationLookup) detect the
        // orphan session via SessionDao.findActiveSessionIds and mark it
        // INTERRUPTED — the audio segments stay on disk and can be replayed
        // by the user from the InfoBar partial-recovery surface (B4).

        // B3-VAL-W1 F-3 — Timeout-bounded shutdown (ADR-0003
        // §"Required mechanics" item 8). The 2-second wall clock
        // bounds module-terminate so a single stuck module can't
        // wedge Android's onDestroy slot (which itself has a system
        // budget). On timeout we proceed without joining — the
        // serviceScope.cancel() below ensures launched coroutines
        // are torn down regardless.
        if (::orchestrator.isInitialized) {
            try {
                runBlocking {
                    withTimeout(SHUTDOWN_TIMEOUT_MS) { orchestrator.shutdown() }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "orchestrator.shutdown() timed out at ${SHUTDOWN_TIMEOUT_MS}ms — proceeding with onDestroy", e)
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
        // C4-B2 — explicit notification cancel (Spec 1 §7.3 final
        // step). Idempotent: `stopSelf()` on a terminal state already
        // removes the FGS-sticky notification, but an onDestroy not
        // preceded by `stopSelf` (Android-initiated stop, OOM) would
        // otherwise leave the notification orphaned. Double-cancel is a
        // harmless no-op. Uses the single SoT NOTIF_ID.
        if (::notificationCoordinatorImpl.isInitialized) {
            try {
                notificationCoordinatorImpl.dismiss()
            } catch (t: Throwable) {
                Log.w(TAG, "notification dismiss during onDestroy failed", t)
            }
        }
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

    // C4-B2 — `buildInitialNotification()` removed. The initial
    // `startForeground` notification is now built by
    // `notificationCoordinatorImpl.buildInitial()` (Spec 1 §7.4); the
    // idle/Recording/Pipeline content + action buttons live in
    // `PipelineNotificationCoordinator` (Spec 1 §7.6/§11.1.2). The
    // builder block here was a duplicate of §11.1.2 that the coordinator
    // now owns as the single source.

    private fun startForegroundCompat(notification: Notification) {
        // C4-B2 — NOTIF_ID is sourced from
        // `PipelineNotificationCoordinator.NOTIF_ID` (Spec 1 §7.4/§10
        // "NOTIF_ID-Konsistenz" SoT). The Service no longer declares its
        // own `const val NOTIF_ID` — one id ⇒ no duplicate/orphan
        // notification.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                PipelineNotificationCoordinator.NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(PipelineNotificationCoordinator.NOTIF_ID, notification)
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

        /**
         * Post-cutover hotfix #3+#4 — current peak amplitude from the
         * service-owned [RecordingHardwareAdapter], for the IME's
         * recording-animation / waveform side-channel polling.
         *
         * Returns `null` when no recording is in flight or the
         * MediaRecorder is in a non-polling state (the wrapper inside
         * [RecordingHardwareAdapter.maxAmplitudeOrNull] absorbs the
         * `IllegalStateException` MediaRecorder can throw at the
         * start/stop boundaries). The IME calls this every ~100ms while
         * `state.recording` is Active|Paused (see
         * [RecordingActivityTickerObserver]) and forwards the value to
         * [ImeViewBackend.onAmplitude]. Pre-hotfix the orchestrator-side
         * [AmplitudeStreamAdapter] was a deliberate no-op (per its own
         * KDoc) and the IME had no path to the active MediaRecorder.
         */
        fun pollRecordingMaxAmplitude(): Int? =
            recordingHardwareAdapterImpl.maxAmplitudeOrNull()

        /**
         * The service-owned [AudioFileFactory] (Spec 1 §4.11 — Pre-Dispatch).
         *
         * Exposed so the IME's `startRecording` resolver can call
         * `allocate()` **before** dispatching
         * `Action.RecordingAction.StartRecording(target, audioFile)`.
         * The reducer is pure (R.2) — the file has to be picked
         * outside the dispatch, in the View-layer.
         *
         * Same instance also lives in [ModuleServices.audioFileFactory]
         * for orchestrator-side consumers (LayoutCatalog resolvers,
         * Spec 2 §10).
         */
        val audioFileFactory: AudioFileFactory
            get() = audioFileFactoryImpl

        // ── C15 — Keyboard layout render orchestration (Spec 2 §11.8 5c) ─

        /**
         * The service-owned [LayoutCatalog]. Exposed so the IME can
         * construct an `ImeViewBackend` against the same catalog the
         * manager uses. Catalog itself is immutable; safe to share.
         */
        val layoutCatalog: LayoutCatalog
            get() = layoutCatalogImpl

        /**
         * The service-owned [KeyboardLayoutManager]. The IME calls
         * [KeyboardLayoutManager.attachBackend] in `onCreateInputView`
         * and [KeyboardLayoutManager.detachBackend] (or
         * [KeyboardLayoutManager.detachAll]) when the view is torn
         * down. The manager re-renders on every state emit via the
         * service-side collector.
         */
        val keyboardLayoutManager: KeyboardLayoutManager
            get() = keyboardLayoutManagerImpl

        /**
         * CR3 (Spec 2 §10 / §11.8 5c) — the shared Strict-Mode
         * visibility-write audit ledger. The IME wires this same
         * instance into the legacy [KeyboardStateManager] and the three
         * dormant visibility controllers' `RenderGate`s so the
         * no-double-write acceptance is provable across the staged
         * CR3→CR4 cutover (render-path-cutover.md §6 RR-2). Removed
         * ersatzlos in CR-DEL (= Block 5d) together with KSM.
         */
        val visibilityWriteAuditLogger:
            net.devemperor.dictate.core.audit.VisibilityWriteAuditLogger
            get() = visibilityWriteAuditLoggerImpl

        /**
         * Service-owned [ModuleServices] container — exposed so the IME
         * can hand the same `services` reference to `ImeViewBackend`
         * (its `actionResolver`s need `audioFileFactory.allocate()` at
         * click time, Spec 1 §4.11). The IME does NOT mutate the
         * container; the LocalBinder field is read-only access.
         */
        val moduleServices: ModuleServices
            get() = moduleServicesImpl

        /**
         * Service-owned [OverlayBackend] (Spec 3 §4.2). Constructed in
         * [onCreate]; attached/detached reactively by
         * [syncOverlayBackendAttachment] on the Triangle-FSM
         * transitions (KEYBOARD ↔ WIDGET / HOVER per ADR-0005). `null`
         * when the Service runs in an environment without a
         * `WindowManager` (e.g. Robolectric isolated process).
         */
        val overlayBackend: OverlayBackend?
            get() = overlayBackendImpl

        /**
         * Service-owned [OverlayPermissionObserver] (Spec 3 §5.0). The
         * IME calls [OverlayPermissionObserver.refresh] from
         * `onCreateInputView` / `onStartInputView` so the
         * `state.overlay.hasPermission` axis catches users who toggle
         * the permission in System Settings without going through the
         * in-IME info-bar flow.
         */
        val overlayPermissionObserver: OverlayPermissionObserver
            get() = overlayPermissionObserverImpl

        /**
         * Service-owned production [OverlayPermissionGate] (Spec 3
         * §5.1). Exposed so non-reducer surfaces (in-IME info-bar
         * click-handler, future Activity-result hand-offs) can query
         * `shouldShowOnboarding` / write the permanently-denied bit
         * without re-instantiating the gate.
         */
        val overlayPermissionGate: OverlayPermissionGate
            get() = overlayPermissionGateImpl

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
         * IME-side affordance hook for the overlay-surface RECORD click
         * (dictate-widget-integration §8.3 Chunk 3.2). The IME registers
         * the same lambda it uses for the keyboard-surface RECORD click
         * (see `DictateInputMethodService.imeSideAffordance`); the
         * [OverlayBackend] looks the lambda up at click-time so the R-1
         * `JobRequest` snapshot (`prepareCatalogStopRecordingIfActive`)
         * runs BEFORE the catalog dispatches `StopRecordingAndSend`.
         *
         * **Why a late-bound field and not a `ModuleServices` member?**
         * The IME's affordance lambda captures IME-private state
         * (`imePipelineConfigResolver`, `newPathRecordingSessionId`)
         * that has no place in `ModuleServices` (the DI container for
         * pure reducer effects). The lambda also has to survive the IME's
         * onCreate → onStartInputView → onUnbind lifecycle independently
         * of the Service's onCreate. A `@Volatile` register-with-IME
         * field on the binder mirrors the `delegateInputConnectionProvider`
         * pattern.
         *
         * `null` when the IME is not currently bound — the overlay's
         * click handler treats `null` as a no-op (the lambda passed into
         * the backend defaults to `{ _, _ -> }`).
         */
        @Volatile
        internal var delegateImeSideAffordance:
            ((net.devemperor.dictate.state.layout.LogicalButtonId, Boolean) -> Unit)? = null

        /**
         * C5 — the IME-registered [PipelineConfigResolver]. Read by the
         * [DelegatingPipelineConfigResolver] wrapping the
         * [PipelineRunnerSubsystemAdapter]: when non-null the fresh /
         * reprocess `JobRequest` is built from the IME's snapshotted
         * recording config (R-1 field fidelity, C3-IMPL-1/-2); when
         * null the C3 [DefaultPipelineConfigResolver] fallback applies
         * (throws for fresh — surfacing beats silent data loss).
         * `@Volatile` — set on `onServiceConnected`, cleared on unbind,
         * read on the orchestrator dispatch thread.
         */
        @Volatile
        internal var delegatePipelineConfigResolver: PipelineConfigResolver? = null

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

        /**
         * Register the IME's overlay-RECORD affordance lambda
         * (dictate-widget-integration §8.3 Chunk 3.2). The IME calls
         * this on bind with the same lambda used for the keyboard-RECORD
         * click handler. Pass `null` on unbind so a click that races the
         * unbind becomes a no-op.
         *
         * The lambda is invoked from
         * [OverlayBackend.wireStaticOverlayHandlers] (overlay click
         * branch for `OVERLAY_RECORD`) — see [OverlayBackend] KDoc for
         * the R-1 snapshot rationale.
         */
        fun registerImeSideAffordance(
            affordance: ((net.devemperor.dictate.state.layout.LogicalButtonId, Boolean) -> Unit)?,
        ) {
            delegateImeSideAffordance = affordance
        }

        /**
         * C5 — register the IME's [PipelineConfigResolver] so the new
         * recording-drive path builds a `JobRequest` field-for-field
         * identical to the legacy `DictateInputMethodService.java:2214-2230`
         * construction (R-1 mitigation, closes C3-IMPL-1/-2). Called from
         * `onServiceConnected`; pass `null` on unbind so the
         * [DelegatingPipelineConfigResolver] falls back to the C3
         * [DefaultPipelineConfigResolver] (which throws for fresh — the
         * R-1 surfacing guard).
         */
        fun registerPipelineConfigResolver(resolver: PipelineConfigResolver?) {
            delegatePipelineConfigResolver = resolver
        }
    }

    companion object {
        const val TAG: String = "DictatePipelineSvc"
        const val CHANNEL_ID: String = "dictate_pipeline"
        // C4-B2 — `NOTIF_ID` removed from this companion (Spec 1 §10
        // "NOTIF_ID-Konsistenz", Epic AC-3). The canonical constant is
        // `PipelineNotificationCoordinator.NOTIF_ID`; a second
        // definition here is the exact drift the spec forbids
        // (`1001` vs `0xD1C7A7E` → duplicate/orphan notification).

        /**
         * SharedPreferences file name used by the rest of the app
         * (`DictateInputMethodService.initLongLivedObjects` calls
         * `getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE)`).
         */
        const val PREFS_NAME: String = "net.devemperor.dictate"

        /**
         * Wall-clock budget for `orchestrator.shutdown()` in onDestroy
         * (B3-VAL-W1 F-3 + ADR-0003 §"Required mechanics" item 8).
         * Beyond 2 seconds Android's onDestroy slot risks ANR-style
         * termination — better to log + proceed than block forever on
         * a wedged module-terminate.
         */
        private const val SHUTDOWN_TIMEOUT_MS: Long = 2_000L
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
