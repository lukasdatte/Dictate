package net.devemperor.dictate.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import net.devemperor.dictate.R
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionOrigin
import net.devemperor.dictate.history.KeyboardHistoryAdapter
import net.devemperor.dictate.history.KeyboardHistoryController
import net.devemperor.dictate.history.KeyboardHistoryPager
import net.devemperor.dictate.preferences.AmbiguityMode
import net.devemperor.dictate.preferences.LanguageResolver
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.WindowsTarget
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DispatchNotice
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.insertion.KeyboardActionDispatcher
import net.devemperor.dictate.state.layout.LogicalButtonId
import net.devemperor.dictate.state.render.ImeViewBackend
import net.devemperor.dictate.state.render.RealMotionSurface
import net.devemperor.dictate.windows.PcInputSink
import net.devemperor.dictate.windows.SessionEntityMapper
import java.io.File
import java.util.concurrent.Executors

/**
 * PC-dictation Activity — a THIRD render host next to the IME view and the floating overlay widget
 * (pc-dictation-activity; ADR-0004 multi-backend, ADR-0008 surface axes, ADR pc-dictation-activity).
 *
 * The Activity binds [DictatePipelineService], attaches its own [ImeViewBackend] to the SAME
 * service-owned [net.devemperor.dictate.state.layout.KeyboardLayoutManager] the IME uses (the
 * manager fans every state-emit out to all attached backends, so both surfaces render the one live
 * `DictateUiState`), and drives the session history at the top of the screen.
 *
 * # Everything goes to the PC (`features.pcOnly`)
 *
 * There is no local `InputConnection` here. While the Activity is in the foreground it pushes
 * [Action.FeatureToggleAction.SetPcOnly]`(true)`, which makes every pipeline terminal divert to the
 * paired PC source-independently (see `WindowsAutoSend.shouldDivertToPc(source, sp, pcOnly)`), and
 * a failed dispatch surfaces here (error banner + retry) instead of a local pending part.
 *
 * # Two foreground-host registrations (precedence over the IME)
 *
 *  - a **config resolver** ([ImePipelineConfigResolver]) so a headless recording can resolve its
 *    `JobRequest` — without it the service-side [DefaultPipelineConfigResolver] throws for a fresh
 *    recording (there is no IME resolver bound). Snapshotted at the RECORD send-tap, mirroring the
 *    IME's `captureFreshConfigSnapshot`.
 *  - a **keyboard-action dispatcher** wrapping [PcInputSink] so the catalog live keys route to the
 *    PC (`/v1/input`) rather than a local field.
 *
 * Both are registered via the binder's foreground slots, which take precedence over the IME's while
 * set and fall back to the IME's when cleared (`onStop`) — a bound-but-hidden IME keeps working once
 * the Activity closes.
 */
class PcDictationActivity : AppCompatActivity() {

    private var binder: DictatePipelineService.LocalBinder? = null
    private var backend: ImeViewBackend? = null

    /** Foreground-host config resolver — the same snapshot mechanism the IME uses, host-agnostic. */
    private val configResolver: ImePipelineConfigResolver by lazy {
        ImePipelineConfigResolver(
            recordingsDirProvider = { filesDir },
            reprocessFallback = DefaultPipelineConfigResolver(filesDirProvider = { filesDir }),
        )
    }

    /** PC-only live-key dispatcher (no local sink — every key goes to the PC). */
    private var pcKeyboardActions: KeyboardActionDispatcher? = null

    private var historyController: KeyboardHistoryController? = null
    private var historyAdapter: KeyboardHistoryAdapter? = null
    private var noticeJob: kotlinx.coroutines.Job? = null

    /** Off-main-thread reads (`getFinalOutput` / `getSessionById`) for the retry + history send. */
    private val dbExecutor = Executors.newSingleThreadExecutor()

    private val sp by lazy { getSharedPreferences(DictatePipelineService.PREFS_NAME, Context.MODE_PRIVATE) }

    private lateinit var errorBanner: View
    private lateinit var errorTv: TextView
    private lateinit var retryBtn: MaterialButton
    private lateinit var historyRv: RecyclerView
    private lateinit var historyEmptyTv: View

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val b = service as? DictatePipelineService.LocalBinder ?: return
            binder = b
            onBound(b)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            teardownBackend()
            binder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pc_dictation)
        errorBanner = findViewById(R.id.pc_dictation_error_banner)
        errorTv = findViewById(R.id.pc_dictation_error_tv)
        retryBtn = findViewById(R.id.pc_dictation_retry_btn)
        historyRv = findViewById(R.id.pc_dictation_history_rv)
        historyEmptyTv = findViewById(R.id.pc_dictation_history_empty_tv)
        retryBtn.setOnClickListener { onRetryClicked() }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, DictatePipelineService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        super.onStop()
        teardownBackend()
        try {
            unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // Not bound (bind failed) — nothing to release.
        }
        binder = null
    }

    override fun onDestroy() {
        super.onDestroy()
        dbExecutor.shutdown()
    }

    // ── Bind / attach ────────────────────────────────────────────────────

    private fun onBound(b: DictatePipelineService.LocalBinder) {
        // PC-only terminal mode: every completion diverts to the PC while this Activity is bound.
        b.dispatch(Action.FeatureToggleAction.SetPcOnly(true))

        // Foreground-host registrations (precedence over the IME, cleared in teardownBackend()).
        b.registerForegroundConfigResolver(configResolver)
        val dispatcher = KeyboardActionDispatcher(PcInputSink(b.pcInputCoordinator))
        pcKeyboardActions = dispatcher
        b.registerForegroundKeyboardActionsProvider { pcKeyboardActions }

        attachBackend(b)
        wireHistory(b)

        // The error banner is driven by state.windowsDispatch.notice. Collected here (once bound)
        // rather than through the shared render manager, which owns the keyboard grid. repeatOnLifecycle
        // keeps it STARTED-scoped; the job is cancelled in teardownBackend().
        noticeJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                b.state.collect { renderNotice(it.windowsDispatch.notice) }
            }
        }
    }

    private fun attachBackend(b: DictatePipelineService.LocalBinder) {
        val motion = findViewById<View>(R.id.main_buttons_cl) as? MotionLayout ?: return
        val views = HashMap<LogicalButtonId, View>()
        // Mandatory slots — every keyboard mode declares them, so a missing entry is a render error.
        views[LogicalButtonId.RECORD] = findViewById(R.id.record_btn)
        views[LogicalButtonId.RESEND] = findViewById(R.id.resend_btn)
        views[LogicalButtonId.RECORD_SECONDARY] = findViewById(R.id.secondary_record_btn)
        views[LogicalButtonId.BACKSPACE] = findViewById(R.id.backspace_btn)
        views[LogicalButtonId.AUDIO_FOCUS] = findViewById(R.id.audio_focus_btn)
        views[LogicalButtonId.TRASH] = findViewById(R.id.trash_btn)
        views[LogicalButtonId.SPACE] = findViewById(R.id.space_btn)
        views[LogicalButtonId.PAUSE] = findViewById(R.id.pause_btn)
        views[LogicalButtonId.ENTER] = findViewById(R.id.enter_btn)
        findViewById<View?>(R.id.widget_toggle_btn)?.let { views[LogicalButtonId.WIDGET_TOGGLE] = it }

        val b2 = ImeViewBackend(
            motionSurface = RealMotionSurface(motion),
            buttonViews = views,
            ctx = this,
            services = b.moduleServices,
            onVibrate = {},
            // RECORD send-tap: snapshot the headless recording config BEFORE the catalog dispatches
            // StopRecordingAndSend, or resolveFresh throws (R-1). Mirrors the IME's affordance.
            imeSideAffordance = { id, isLongPress -> onBackendAffordance(id, isLongPress) },
        )
        b.keyboardLayoutManager.attachBackend(b2)
        backend = b2
    }

    private fun onBackendAffordance(id: LogicalButtonId, isLongPress: Boolean) {
        if (id != LogicalButtonId.RECORD || isLongPress) return
        val b = binder ?: return
        val rec = b.state.value.recording
        val session: Pair<String, File> = when (rec) {
            is RecordingState.Active -> rec.sessionId to rec.audioFile
            is RecordingState.Paused -> rec.sessionId to rec.audioFile
            else -> return // Idle/Preparing: this is a START tap, the catalog resolver allocates.
        }
        snapshotFreshConfig(b, session.first, session.second)
    }

    /**
     * Build a headless [ImePipelineConfigResolver.FreshConfig] and stash it for the async
     * `resolveFresh`. Field-faithful to the IME's `captureFreshConfigSnapshot`, degraded for the
     * host-less context: no target app, no live prompt, no keyboard-switch, and ALWAYS_INSERT
     * (the review panel is IME-only) so the pipeline produces final text that diverts to the PC.
     */
    private fun snapshotFreshConfig(b: DictatePipelineService.LocalBinder, sessionId: String, audioFile: File) {
        val effLang = LanguageResolver.effectiveLanguage(sp)
        val language = if (effLang == "detect") null else effLang
        val stylePrompt = b.promptService.resolveWhisperStylePrompt(effLang)
        val totalSteps = 1 + if (b.autoFormattingService.isEnabled()) 1 else 0
        configResolver.snapshotFresh(
            sessionId,
            ImePipelineConfigResolver.FreshConfig(
                totalSteps = totalSteps,
                audioFilePath = audioFile.absolutePath,
                language = language,
                queuedPromptIds = emptyList(),
                targetAppPackage = null,
                stylePrompt = stylePrompt,
                livePrompt = false,
                autoSwitchKeyboard = false,
                showResendButton = false,
                ambiguityMode = AmbiguityMode.ALWAYS_INSERT,
                transcriptionOnly = false,
                origin = SessionOrigin.KEYBOARD,
                uiContext = null,
            ),
        )
    }

    private fun teardownBackend() {
        val b = binder
        if (b != null) {
            b.dispatch(Action.FeatureToggleAction.SetPcOnly(false))
            b.registerForegroundConfigResolver(null)
            b.registerForegroundKeyboardActionsProvider(null)
            backend?.let { b.keyboardLayoutManager.detachBackend(it) }
        }
        backend = null
        pcKeyboardActions = null
        noticeJob?.cancel()
        noticeJob = null
        historyController?.onViewDestroyed()
        historyController = null
        historyAdapter = null
    }

    // ── History (top of screen) ──────────────────────────────────────────

    private fun wireHistory(b: DictatePipelineService.LocalBinder) {
        val db = DictateDatabase.getInstance(this)
        historyRv.layoutManager = LinearLayoutManager(this)
        val adapter = KeyboardHistoryAdapter(
            object : KeyboardHistoryAdapter.Callback {
                override fun onInsert(session: SessionEntity, pending: Boolean) {
                    // No local field in PC mode — "insert" and "send" both go to the PC.
                    sendToPc(session, pending)
                }

                override fun onSendToWindows(session: SessionEntity, pending: Boolean) {
                    sendToPc(session, pending)
                }

                override fun onOpenDetail(session: SessionEntity) {
                    // The in-keyboard detail panel is an IME-surface concern; no-op here.
                }
            },
            // Paired is a precondition for entering the Activity, so the per-row send slot is shown.
            windowsTargetPaired = true,
        )
        historyRv.adapter = adapter
        historyAdapter = adapter
        val controller = KeyboardHistoryController(KeyboardHistoryPager(db.sessionDao()), adapter)
        controller.onViewCreated()
        controller.onPanelOpen()
        historyController = controller
    }

    private fun sendToPc(session: SessionEntity, pending: Boolean) {
        val b = binder ?: return
        val sid = session.id
        dbExecutor.execute {
            val text = b.sessionManager.getFinalOutput(sid)
            if (text.isNullOrEmpty()) return@execute
            if (WindowsTarget.from(sp) == null) return@execute
            runOnUiThread {
                b.windowsDispatchCoordinator.dispatch(
                    sid,
                    text,
                    session.createdAt,
                    SessionEntityMapper.originToWire(session.origin),
                    /* acknowledgeOnSuccess = */ pending,
                    /* surfacedAsPending = */ pending,
                    /* suppressPendingFallback = */ true,
                )
            }
        }
    }

    // ── Error banner + retry ─────────────────────────────────────────────

    private fun renderNotice(notice: DispatchNotice?) {
        when (notice) {
            is DispatchNotice.Error -> {
                errorTv.setText(R.string.pc_dictation_send_failed)
                retryBtn.visibility = if (notice.sessionId != null) View.VISIBLE else View.GONE
                errorBanner.visibility = View.VISIBLE
                errorBanner.tag = notice.sessionId
            }
            else -> {
                errorBanner.visibility = View.GONE
                errorBanner.tag = null
            }
        }
    }

    private fun onRetryClicked() {
        val b = binder ?: return
        val sid = errorBanner.tag as? String ?: return
        b.dispatch(Action.WindowsDispatchAction.DismissNotice)
        dbExecutor.execute {
            val text = b.sessionManager.getFinalOutput(sid) ?: return@execute
            val session = b.sessionManager.getSessionById(sid)
            val createdAt = session?.createdAt ?: System.currentTimeMillis()
            val originWire = SessionEntityMapper.originToWire(session?.origin ?: SessionOrigin.KEYBOARD.name)
            runOnUiThread {
                b.windowsDispatchCoordinator.dispatch(
                    sid,
                    text,
                    createdAt,
                    originWire,
                    /* acknowledgeOnSuccess = */ true,
                    /* surfacedAsPending = */ false,
                    /* suppressPendingFallback = */ true,
                )
            }
        }
    }

    companion object {
        /** Intent action the launch trampoline fires (mirrors StartDictationActivity's pattern). */
        const val ACTION_OPEN: String = "net.devemperor.dictate.OPEN_PC_DICTATION"
    }
}
