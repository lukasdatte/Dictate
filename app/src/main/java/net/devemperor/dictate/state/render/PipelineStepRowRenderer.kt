package net.devemperor.dictate.state.render

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.core.AutoEnterIconRenderer
import net.devemperor.dictate.core.ElapsedTimer
import net.devemperor.dictate.core.PipelineUiCallback
import net.devemperor.dictate.core.PipelineUiState
import net.devemperor.dictate.core.PipelineUiStateReader
import net.devemperor.dictate.core.RecordingState
import net.devemperor.dictate.core.formatElapsedCompact
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the pipeline **step-row** progress UI + the `PipelineUiState`
 * machinery (the View-side that **BLEIBT** per Spec 1 §9.2 / Spec 2
 * §9.5).
 *
 * # Why this class exists (CR-DEL / RR-3 / A3 option-a)
 *
 * Spec 1 §9.2 says *"stepRows bleibt im `KeyboardUiController`
 * View-side"* and Spec 2 §9.5 maps the record-button-from-pipeline-state
 * to resolvers but leaves the step-row inflate/complete/fail rendering +
 * the per-step / total live timers + the `PipelineUiState` ownership in
 * place. The C10-C3 kill-list deletes `KeyboardUiController`, so — per
 * the **binding A3 option-a** disposition the orchestrator recorded in
 * CR3 (extract the BLEIBT parts into a small new owner so the kill-list
 * class fully deletes and AC-RR-7 stays a clean zero-grep) — that
 * View-side is **relocated here verbatim**, into the render package
 * (sibling to `ContentAreaController` / `RecordingAnimationController`).
 *
 * It is **not** a `RenderBackend`: the pipeline-progress drive is
 * imperative (the IME service calls `startPipeline`/`addRunningStep`/…
 * from the orchestrator's pipeline callbacks), exactly as the legacy
 * `KeyboardUiController` was driven — preserving the drive cadence
 * (per-step inflate is a structural mutation, not a per-state-tick
 * render). The behaviour is byte-equivalent to the deleted
 * `KeyboardUiController` — only the call site (and package) moved.
 *
 * It implements [PipelineUiStateReader] — the narrow read/observe
 * surface for the ReprocessStaging language carrier (Spec 1 §9.6 —
 * adapt, not delete: the interface now points at this relocated owner).
 *
 * Does NOT handle threading — all methods must be called on the main
 * thread. Does NOT make pipeline/orchestration decisions — the service
 * controls when to switch modes.
 *
 * @see PipelineUiStateReader
 * @see QwertzRecordingController — the sibling G9 BLEIBT extraction.
 * @see docs/plans/2026-05-15 - dictate-cutover-completion/reports/B5-theme-cr-render-cutover.md "Chunk C10-C3 (CR-DEL)"
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §9.2
 */
class PipelineStepRowRenderer(
    private val views: PipelineViews,
    private val onPipelineUiStateChanged: () -> Unit,
    /**
     * Supplies the dictate-button label for the Idle recording branch so
     * the central resolver in [applyRecordButtonForRecording] can paint
     * text without owning the language / preferences plumbing
     * (relocated verbatim from the deleted `KeyboardUiController`).
     */
    private val dictateButtonTextProvider: () -> String = { "" },
) : PipelineUiStateReader {

    /**
     * Controller-owned per-run UI configuration for the record button.
     *
     * Relocated from `KeyboardUiController.AutoEnterConfig` (the name and
     * lifecycle are unchanged). Holds the `autoEnterActive` flag for the
     * keyboard record-button rendering; non-null from [startPipeline]
     * until [stopPipeline].
     */
    data class AutoEnterConfig(val autoEnterActive: Boolean)

    data class PipelineViews(
        val pipelineStepsContainer: LinearLayout,
        val pipelineScrollView: ScrollView,
        val recordButton: MaterialButton,
        val infoCl: View,
        val layoutInflater: LayoutInflater,
        val mainHandler: Handler,
    )

    // ── Pipeline UI State ──

    override var state: PipelineUiState = PipelineUiState.Idle
        private set

    /** Active auto-enter configuration; null iff no pipeline run is in progress. */
    private var config: AutoEnterConfig? = null

    /** @return the active [AutoEnterConfig], or null if no pipeline run is in progress. */
    fun getAutoEnterConfig(): AutoEnterConfig? = config

    /**
     * Registered pipeline-state observers (Quality-Gate K-2 +
     * Design-Prinzip 7). `CopyOnWriteArrayList` — a callback may add or
     * remove other callbacks during a `forEach` dispatch.
     */
    private val callbacks = CopyOnWriteArrayList<PipelineUiCallback>()

    override fun addCallback(callback: PipelineUiCallback) {
        callbacks.addIfAbsent(callback)
    }

    override fun removeCallback(callback: PipelineUiCallback) {
        callbacks.remove(callback)
    }

    private var pipelineTotalTimer: ElapsedTimer? = null
    private var latestPipelineElapsedMs: Long = 0

    private data class StepRowBinding(
        val root: View,
        val iconTv: TextView,
        val pb: ProgressBar,
        val nameTv: TextView,
        val durationTv: TextView,
    )

    private val stepRows = mutableListOf<StepRowBinding>()
    private var totalSteps = 0
    private var currentStep = 0
    private var activeTimer: ElapsedTimer? = null

    private var savedRecordButtonTextColors: ColorStateList? = null

    private val autoEnterRenderer by lazy { AutoEnterIconRenderer(views.recordButton.context) }

    // ── State mutation ──

    private fun updatePipelineState(newState: PipelineUiState) {
        val old = state
        state = newState
        refreshRecordButtonFromState()
        if (old != newState) {
            callbacks.forEach { it.onPipelineUiStateChanged(old, newState) }
            onPipelineUiStateChanged()
        }
    }

    private inline fun updateRunningState(transform: (PipelineUiState.Running) -> PipelineUiState.Running) {
        val s = state
        if (s is PipelineUiState.Running) {
            updatePipelineState(transform(s))
        }
    }

    // ── Convenience read-only accessors (Tell-don't-ask for the Service) ──

    fun isPipelineRunning(): Boolean = state is PipelineUiState.Running

    fun isPipelineActive(): Boolean =
        state is PipelineUiState.Running || state is PipelineUiState.Preparing

    fun isBusy(): Boolean = state !is PipelineUiState.Idle

    fun isReprocessStaging(): Boolean = state is PipelineUiState.ReprocessStaging

    fun getLatestPipelineElapsedMs(): Long = latestPipelineElapsedMs

    // ── Timer cleanup (for view-recreation without side-effects) ──

    fun stopActiveTimer() {
        activeTimer?.stop()
        activeTimer = null
        pipelineTotalTimer?.stop()
        pipelineTotalTimer = null
    }

    // ── New pipeline API ──

    fun preparePipeline() {
        if (savedRecordButtonTextColors == null) {
            savedRecordButtonTextColors = views.recordButton.textColors
        }
        updatePipelineState(PipelineUiState.Preparing)
    }

    @JvmOverloads
    fun startPipeline(totalSteps: Int, config: AutoEnterConfig, initialCompletedSteps: Int = 0) {
        if (savedRecordButtonTextColors == null) {
            savedRecordButtonTextColors = views.recordButton.textColors
        }

        this.config = config

        views.pipelineStepsContainer.removeAllViews()
        views.infoCl.visibility = View.GONE
        stepRows.clear()
        this.totalSteps = totalSteps
        currentStep = 0

        updatePipelineState(
            PipelineUiState.Running(
                totalSteps = totalSteps,
                completedSteps = initialCompletedSteps,
                currentStepName = "",
                autoEnterActive = config.autoEnterActive,
            ),
        )

        latestPipelineElapsedMs = 0
        pipelineTotalTimer?.stop()
        pipelineTotalTimer = ElapsedTimer.start(views.mainHandler) { ms ->
            latestPipelineElapsedMs = ms
            refreshRecordButtonFromState()
            val s = state
            if (s is PipelineUiState.Running) {
                callbacks.forEach { it.onPipelineTimerTick(s, ms) }
            }
        }
    }

    fun stopPipeline() {
        pipelineTotalTimer?.stop()
        pipelineTotalTimer = null
        activeTimer?.stop()
        activeTimer = null
        autoEnterRenderer.invalidate()
        updatePipelineState(PipelineUiState.Idle)
        config = null
    }

    fun toggleAutoEnter() {
        val c = config ?: return
        val s = state
        if (s !is PipelineUiState.Running) return
        val newConfig = c.copy(autoEnterActive = !c.autoEnterActive)
        config = newConfig
        updatePipelineState(s.copy(autoEnterActive = newConfig.autoEnterActive))
    }

    /**
     * Phase-2 cutover bridge — drive the renderer's pipeline-state from
     * the orchestrator's `state.PipelineUiState` snapshot.
     *
     * Designed as an **additive** consumer pathway during the
     * `2026-05-21 - dictate-render-cutover-completion-vol2` migration.
     * Today the IME still calls the imperative methods
     * (`preparePipeline`, `startPipeline`, `addRunningStep`,
     * `completeStep`, `failStep`, `stopPipeline`,
     * `enterReprocessStaging`, …) which mutate the renderer's legacy
     * `state` directly. Phase 5 replaces those call sites with a single
     * `StateFlow<DictateUiState>.collect { syncFromOrchestrator(it.pipeline) }`
     * subscription and removes the imperative API + the legacy
     * `state`-property entirely.
     *
     * The bridge ([toCoreLegacy]) is lossy — `currentStepName` and
     * `hasFailure` are not present in the orchestrator state today
     * (Phase 5.A introduces them via `stepHistory`/`hasFailure`).
     * Until then the imperative `addRunningStep` / `failStep`
     * callbacks continue to carry the step-level detail; this method
     * only synchronises the **coarse FSM phase**
     * (Idle / Preparing / Running / ReprocessStaging) and the
     * `autoEnterActive` axis.
     *
     * @see PipelineUiStateBridge
     */
    fun syncFromOrchestrator(orchestratorState: net.devemperor.dictate.state.PipelineUiState) {
        val mapped = orchestratorState.toCoreLegacy()
        // Preserve the step-detail fields (currentStepName, hasFailure)
        // on the Running branch — they are owned by the imperative
        // legacy callbacks until Phase 5.A. The branch type + counters
        // + autoEnterActive come from the orchestrator snapshot.
        val merged = if (mapped is PipelineUiState.Running && state is PipelineUiState.Running) {
            val existing = state as PipelineUiState.Running
            mapped.copy(
                currentStepName = existing.currentStepName,
                hasFailure = existing.hasFailure,
            )
        } else {
            mapped
        }
        if (merged != state) updatePipelineState(merged)
    }

    // ── ReprocessStaging state mutations (Phase 7.3) ──

    fun enterReprocessStaging(
        targetSessionId: String,
        audioDurationSeconds: Long,
        initialQueue: List<Int>,
        language: String?,
    ) {
        updatePipelineState(
            PipelineUiState.ReprocessStaging(
                targetSessionId = targetSessionId,
                audioDurationSeconds = audioDurationSeconds,
                editableQueue = initialQueue,
                selectedLanguage = language,
            ),
        )
    }

    fun cancelReprocessStaging() {
        if (state is PipelineUiState.ReprocessStaging) {
            updatePipelineState(PipelineUiState.Idle)
        }
    }

    fun updateReprocessQueue(queue: List<Int>) {
        val s = state
        if (s is PipelineUiState.ReprocessStaging) {
            updatePipelineState(s.copy(editableQueue = queue))
        }
    }

    override fun updateReprocessLanguage(code: String) {
        val s = state
        if (s is PipelineUiState.ReprocessStaging) {
            updatePipelineState(s.copy(selectedLanguage = code))
        }
    }

    // ── Pipeline steps (Running state only, main thread) ──

    fun addRunningStep(stepName: String) {
        currentStep++

        activeTimer?.stop()

        val row = views.layoutInflater.inflate(
            R.layout.item_pipeline_step_row,
            views.pipelineStepsContainer,
            false,
        )
        val binding = StepRowBinding(
            root = row,
            iconTv = row.findViewById(R.id.pipeline_step_icon_tv),
            pb = row.findViewById(R.id.pipeline_step_pb),
            nameTv = row.findViewById(R.id.pipeline_step_name_tv),
            durationTv = row.findViewById(R.id.pipeline_step_duration_tv),
        )

        binding.iconTv.visibility = View.GONE
        binding.pb.visibility = View.VISIBLE
        binding.nameTv.text = stepName

        binding.durationTv.visibility = View.VISIBLE
        binding.durationTv.text = formatElapsedCompact(0)

        views.pipelineStepsContainer.addView(row)
        stepRows.add(binding)

        activeTimer = ElapsedTimer.start(views.mainHandler) { ms ->
            binding.durationTv.text = formatElapsedCompact(ms)
        }

        views.pipelineScrollView.post { views.pipelineScrollView.fullScroll(View.FOCUS_DOWN) }

        updateRunningState { it.copy(currentStepName = stepName) }
    }

    fun completeStep(stepName: String, durationMs: Long) {
        activeTimer?.stop()
        activeTimer = null

        if (stepRows.isEmpty()) return
        val binding = stepRows.last()

        binding.pb.visibility = View.GONE
        binding.iconTv.visibility = View.VISIBLE
        binding.iconTv.text = "✓" // ✓
        binding.iconTv.setTextColor(0xFF4CAF50.toInt()) // Material Green 500
        binding.nameTv.text = stepName
        binding.durationTv.visibility = View.VISIBLE
        binding.durationTv.text = formatElapsedCompact(durationMs)

        updateRunningState { it.copy(completedSteps = it.completedSteps + 1) }
    }

    fun failStep(stepName: String) {
        activeTimer?.stop()
        activeTimer = null

        if (stepRows.isEmpty()) return
        val binding = stepRows.last()

        binding.pb.visibility = View.GONE
        binding.iconTv.visibility = View.VISIBLE
        binding.iconTv.text = "✕" // ✕
        binding.iconTv.setTextColor(0xFFF44336.toInt()) // Material Red 500
        binding.nameTv.text = stepName

        updateRunningState { it.copy(completedSteps = it.completedSteps + 1, hasFailure = true) }
    }

    // ── Record button rendering from state ──

    /**
     * Central resolver for the recording-axis side of the record-button
     * appearance (Spec 1 §11.2.2 step 2). Behaviour preserved verbatim
     * from the deleted `KeyboardUiController.applyRecordButtonForRecording`
     * — only the call site moved.
     */
    fun applyRecordButtonForRecording(state: RecordingState) {
        if (this.state !is PipelineUiState.Idle) {
            refreshRecordButtonFromState()
            return
        }
        when (state) {
            is RecordingState.Idle -> {
                views.recordButton.text = dictateButtonTextProvider()
                views.recordButton.isEnabled = true
                views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0,
                )
            }
            is RecordingState.Preparing -> {
                views.recordButton.isEnabled = false
            }
            is RecordingState.Active -> {
                views.recordButton.isEnabled = true
                views.recordButton.setText(R.string.dictate_send)
                if (state.useBluetooth) {
                    views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        R.drawable.ic_baseline_send_20, 0, R.drawable.ic_baseline_bluetooth_20, 0,
                    )
                } else {
                    views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        R.drawable.ic_baseline_send_20, 0, 0, 0,
                    )
                }
            }
            is RecordingState.Paused -> {
                // No record-button mutation when entering Paused — text /
                // isEnabled stay at the Active values until resume or stop
                // (deleted `KeyboardUiController` parity).
            }
        }
    }

    private fun refreshRecordButtonFromState() {
        when (val s = state) {
            is PipelineUiState.Idle -> {
                views.recordButton.isEnabled = true
                views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            }
            is PipelineUiState.Preparing -> {
                views.recordButton.isEnabled = false
                views.recordButton.setText(R.string.dictate_sending)
                views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_baseline_send_20, 0, 0, 0,
                )
                views.recordButton.setTextColor(Color.WHITE)
            }
            is PipelineUiState.Running -> {
                views.recordButton.isEnabled = true
                val counter = "${s.completedSteps}/${s.totalSteps}"
                val timer = formatElapsedCompact(latestPipelineElapsedMs)
                views.recordButton.text = if (s.currentStepName.isNotEmpty()) {
                    "${s.currentStepName}  $counter  $timer"
                } else {
                    "$counter  $timer"
                }
                views.recordButton.setTextColor(
                    if (s.hasFailure) 0xFFF44336.toInt() else Color.WHITE,
                )
                updateAutoEnterAppearance(s.autoEnterActive)
            }
            is PipelineUiState.ReprocessStaging -> {
                views.recordButton.isEnabled = true
                views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_baseline_play_arrow_24,
                    0,
                    R.drawable.ic_baseline_send_24,
                    0,
                )
                val durationStr = formatDurationMinSec(s.audioDurationSeconds)
                views.recordButton.text = views.recordButton.resources.getString(
                    R.string.dictate_reprocess_audio_available, durationStr,
                )
                views.recordButton.setTextColor(Color.WHITE)
            }
        }
    }

    private fun formatDurationMinSec(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", m, s)
    }

    fun restoreRecordButtonIdle(text: String, leftIcon: Int, rightIcon: Int) {
        views.recordButton.text = text
        views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(leftIcon, 0, rightIcon, 0)
        savedRecordButtonTextColors?.let { views.recordButton.setTextColor(it) }
    }

    // ── Auto-enter appearance (visual only, no click listener management) ──

    private fun updateAutoEnterAppearance(active: Boolean) {
        views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            null, null, autoEnterRenderer.get(active), null,
        )
    }
}
