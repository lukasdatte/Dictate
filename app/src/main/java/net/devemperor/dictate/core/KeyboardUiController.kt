package net.devemperor.dictate.core

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

/**
 * Controls the keyboard prompt area UI: mode switching between prompt buttons
 * and pipeline progress view with live elapsed timer per step.
 *
 * Owns the [currentMode] state and the [state] (pipeline UI state).
 * Side-effects (timer stop, container clear) stay here.
 * Visibility calculation is delegated to [KeyboardStateManager] via [stateManager.refresh()].
 *
 * Does NOT handle threading — all methods must be called on the main thread.
 * Does NOT make pipeline/orchestration decisions — the Service controls when to switch modes.
 */
class KeyboardUiController(
    private val views: PipelineViews,
    private val stateManager: KeyboardStateManager
) {

    enum class PromptAreaMode {
        PROMPT_BUTTONS, PIPELINE_PROGRESS
    }

    data class PipelineViews(
        val pipelineStepsContainer: LinearLayout,
        val pipelineScrollView: ScrollView,
        val recordButton: MaterialButton,
        val infoCl: View,
        val layoutInflater: LayoutInflater,
        val mainHandler: Handler
    )

    var currentMode: PromptAreaMode = PromptAreaMode.PROMPT_BUTTONS
        private set

    // ── Pipeline UI State ──

    var state: PipelineUiState = PipelineUiState.Idle
        private set

    /** Fires on actual state changes (step completed, auto-enter toggled, pipeline start/stop). */
    var onPipelineUiStateChanged: ((old: PipelineUiState, newState: PipelineUiState) -> Unit)? = null

    /** Fires every 100ms while pipeline runs — for QWERTZ timer updates. */
    var onPipelineTimerTick: ((state: PipelineUiState.Running, elapsedMs: Long) -> Unit)? = null

    private var pipelineTotalTimer: ElapsedTimer? = null
    private var latestPipelineElapsedMs: Long = 0

    /**
     * Holds resolved view references for a single step row so that
     * [addRunningStep]/[completeStep]/[failStep] don't re-run findViewById lookups.
     */
    private data class StepRowBinding(
        val root: View,
        val iconTv: TextView,
        val pb: ProgressBar,
        val nameTv: TextView,
        val durationTv: TextView
    )

    private val stepRows = mutableListOf<StepRowBinding>()
    private var totalSteps = 0
    private var currentStep = 0
    private var activeTimer: ElapsedTimer? = null

    private var savedRecordButtonTextColors: ColorStateList? = null

    // Cached auto-enter compound drawables (one per active/inactive state).
    // Rebuilt lazily once per density and invalidated in [stopPipeline] so theme-/config-changes
    // after view recreation get a fresh copy.
    private var cachedAutoEnterActiveDrawable: android.graphics.drawable.Drawable? = null
    private var cachedAutoEnterInactiveDrawable: android.graphics.drawable.Drawable? = null

    // ── State mutation ──

    private fun updatePipelineState(newState: PipelineUiState) {
        val old = state
        state = newState
        refreshRecordButtonFromState()
        if (old != newState) {
            onPipelineUiStateChanged?.invoke(old, newState)
            stateManager.refresh()
        }
    }

    /**
     * DRY helper: if the current state is [PipelineUiState.Running], apply [transform]
     * and commit the result via [updatePipelineState]. No-op otherwise.
     */
    private inline fun updateRunningState(transform: (PipelineUiState.Running) -> PipelineUiState.Running) {
        val s = state
        if (s is PipelineUiState.Running) {
            updatePipelineState(transform(s))
        }
    }

    // ── Convenience read-only accessors (Tell-don't-ask for the Service) ──

    /** True iff the pipeline is in the [PipelineUiState.Running] state. */
    fun isPipelineRunning(): Boolean = state is PipelineUiState.Running

    /** True iff the pipeline is in [PipelineUiState.Running] or [PipelineUiState.Preparing]. */
    fun isPipelineActive(): Boolean =
        state is PipelineUiState.Running || state is PipelineUiState.Preparing

    /** Last elapsed-timer value observed by the running pipeline (0 if none). */
    fun getLatestPipelineElapsedMs(): Long = latestPipelineElapsedMs

    // ── Timer cleanup (for view-recreation without side-effects) ──

    /** Stops only the active elapsed timer without resetting mode or triggering side-effects. */
    fun stopActiveTimer() {
        activeTimer?.stop()
        activeTimer = null
        pipelineTotalTimer?.stop()
        pipelineTotalTimer = null
    }

    // ── New pipeline API ──

    /**
     * Enters the "Preparing" state: audio is being uploaded, pipeline hasn't started yet.
     * Disables the record button and shows "Sending..." text.
     * Transitions: Idle → Preparing → Running (via [startPipeline]).
     */
    fun preparePipeline() {
        // Save text colors before changing them
        if (savedRecordButtonTextColors == null) {
            savedRecordButtonTextColors = views.recordButton.textColors
        }
        updatePipelineState(PipelineUiState.Preparing)
    }

    /**
     * Enters pipeline progress mode with state tracking.
     * Starts the overall pipeline timer and sets the initial [PipelineUiState.Running] state.
     *
     * @param totalSteps total number of pipeline steps (transcription + format + prompts)
     * @param autoEnterActive whether auto-enter is active at pipeline start
     * @param initialCompletedSteps number of already completed steps (for UI restore after view recreation)
     */
    @JvmOverloads
    fun startPipeline(totalSteps: Int, autoEnterActive: Boolean, initialCompletedSteps: Int = 0) {
        // Save text colors for restoreRecordButtonIdle after pipeline end
        if (savedRecordButtonTextColors == null) {
            savedRecordButtonTextColors = views.recordButton.textColors
        }

        // Prompts area: pipeline progress mode
        currentMode = PromptAreaMode.PIPELINE_PROGRESS
        views.pipelineStepsContainer.removeAllViews()
        views.infoCl.visibility = View.GONE
        stepRows.clear()
        this.totalSteps = totalSteps
        currentStep = 0

        // Set state (Preparing → Running transition re-enables button via refreshRecordButtonFromState)
        updatePipelineState(PipelineUiState.Running(
            totalSteps = totalSteps,
            completedSteps = initialCompletedSteps,
            currentStepName = "",
            autoEnterActive = autoEnterActive
        ))

        // Start overall pipeline timer
        latestPipelineElapsedMs = 0
        pipelineTotalTimer?.stop()
        pipelineTotalTimer = ElapsedTimer.start(views.mainHandler) { ms ->
            latestPipelineElapsedMs = ms
            refreshRecordButtonFromState()
            val s = state
            if (s is PipelineUiState.Running) {
                onPipelineTimerTick?.invoke(s, ms)
            }
        }
    }

    /**
     * Exits pipeline progress mode, stops all timers, and resets state to [PipelineUiState.Idle].
     * The [onPipelineUiStateChanged] callback fires, allowing consumers (e.g. QWERTZ) to reset.
     */
    fun stopPipeline() {
        pipelineTotalTimer?.stop()
        pipelineTotalTimer = null
        activeTimer?.stop()
        activeTimer = null
        // Invalidate cached auto-enter drawables so the next pipeline (potentially after
        // a config/theme change or view recreation) rebuilds them from the current context.
        cachedAutoEnterActiveDrawable = null
        cachedAutoEnterInactiveDrawable = null
        // IMPORTANT: Set mode BEFORE state, so stateManager.refresh() (via updatePipelineState)
        // calculates correct visibility. The existing resetToPromptButtons() does the same.
        currentMode = PromptAreaMode.PROMPT_BUTTONS
        updatePipelineState(PipelineUiState.Idle)
        // onPipelineUiStateChanged fires → Service can reset QWERTZ
    }

    /**
     * Updates the auto-enter flag in the pipeline state.
     * No-op if the pipeline is not running or the value hasn't changed.
     */
    fun setAutoEnter(active: Boolean) {
        val s = state
        if (s is PipelineUiState.Running && s.autoEnterActive != active) {
            updatePipelineState(s.copy(autoEnterActive = active))
        }
    }

    // ── Pipeline steps (PIPELINE_PROGRESS mode only, main thread) ──

    /**
     * Adds a new step row in "running" state (spinner + name + live timer).
     * Starts a live elapsed timer that updates the duration text every 100ms.
     * Increments the step counter and updates the pipeline state.
     *
     * @param stepName display name for the step (e.g., "Transkription", "Formatierung")
     */
    fun addRunningStep(stepName: String) {
        currentStep++

        // Stop previous timer if still running (safety)
        activeTimer?.stop()

        val row = views.layoutInflater.inflate(
            R.layout.item_pipeline_step_row,
            views.pipelineStepsContainer,
            false
        )
        val binding = StepRowBinding(
            root = row,
            iconTv = row.findViewById(R.id.pipeline_step_icon_tv),
            pb = row.findViewById(R.id.pipeline_step_pb),
            nameTv = row.findViewById(R.id.pipeline_step_name_tv),
            durationTv = row.findViewById(R.id.pipeline_step_duration_tv)
        )

        binding.iconTv.visibility = View.GONE
        binding.pb.visibility = View.VISIBLE
        binding.nameTv.text = stepName

        // Show live timer from 0.0s
        binding.durationTv.visibility = View.VISIBLE
        binding.durationTv.text = formatElapsedCompact(0)

        views.pipelineStepsContainer.addView(row)
        stepRows.add(binding)

        // Start elapsed timer
        activeTimer = ElapsedTimer.start(views.mainHandler) { ms ->
            binding.durationTv.text = formatElapsedCompact(ms)
        }

        // Auto-scroll to bottom
        views.pipelineScrollView.post { views.pipelineScrollView.fullScroll(View.FOCUS_DOWN) }

        // Update pipeline state with current step name
        updateRunningState { it.copy(currentStepName = stepName) }
    }

    /**
     * Updates the last step row to "done" state (checkmark + final duration).
     * Stops the live timer and replaces it with the authoritative duration from the orchestrator.
     * Increments the completed steps counter in the pipeline state.
     *
     * @param stepName display name (re-set in case it changed)
     * @param durationMs step duration in milliseconds (from orchestrator, more accurate than timer)
     */
    fun completeStep(stepName: String, durationMs: Long) {
        activeTimer?.stop()
        activeTimer = null

        if (stepRows.isEmpty()) return
        val binding = stepRows.last()

        binding.pb.visibility = View.GONE
        binding.iconTv.visibility = View.VISIBLE
        binding.iconTv.text = "\u2713" // ✓
        binding.iconTv.setTextColor(0xFF4CAF50.toInt()) // Material Green 500
        binding.nameTv.text = stepName
        binding.durationTv.visibility = View.VISIBLE
        binding.durationTv.text = formatElapsedCompact(durationMs)

        // Update pipeline state: increment completed steps
        updateRunningState { it.copy(completedSteps = it.completedSteps + 1) }
    }

    /**
     * Updates the last step row to "error" state (cross mark).
     * Stops the live timer. Increments the completed steps counter
     * (a failed step is "completed" in terms of progress).
     *
     * @param stepName display name of the failed step
     */
    fun failStep(stepName: String) {
        activeTimer?.stop()
        activeTimer = null

        if (stepRows.isEmpty()) return
        val binding = stepRows.last()

        binding.pb.visibility = View.GONE
        binding.iconTv.visibility = View.VISIBLE
        binding.iconTv.text = "\u2715" // ✕
        binding.iconTv.setTextColor(0xFFF44336.toInt()) // Material Red 500
        binding.nameTv.text = stepName

        // Update pipeline state: increment completed steps (failed = still "done" for progress)
        // and mark the state so the record button text turns red.
        updateRunningState { it.copy(completedSteps = it.completedSteps + 1, hasFailure = true) }
    }

    // ── Record button rendering from state ──

    /**
     * Renders the record button based on the current [PipelineUiState].
     * Shows step name, counter, and timer during pipeline; clears compound drawables when idle.
     */
    private fun refreshRecordButtonFromState() {
        when (val s = state) {
            is PipelineUiState.Idle -> {
                views.recordButton.isEnabled = true
                views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            }
            is PipelineUiState.Preparing -> {
                // "Sending..." — button disabled, send icon, no auto-enter toggle
                views.recordButton.isEnabled = false
                views.recordButton.setText(R.string.dictate_sending)
                views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_baseline_send_20, 0, 0, 0)
                views.recordButton.setTextColor(Color.WHITE)
            }
            is PipelineUiState.Running -> {
                // Pipeline active — button enabled for auto-enter toggle
                views.recordButton.isEnabled = true
                val counter = "${s.completedSteps}/${s.totalSteps}"
                val timer = formatElapsedCompact(latestPipelineElapsedMs)
                views.recordButton.text = if (s.currentStepName.isNotEmpty()) {
                    "${s.currentStepName}  $counter  $timer"
                } else {
                    "$counter  $timer"
                }
                views.recordButton.setTextColor(
                    if (s.hasFailure) 0xFFF44336.toInt() else Color.WHITE)
                updateAutoEnterAppearance(s.autoEnterActive)
            }
        }
    }

    /**
     * Restores the record button to its idle state after pipeline completion or cancellation.
     * Does NOT call applyRecordingIconState — the Service handles icon color/animation separately.
     *
     * @param text button label text
     * @param leftIcon drawable resource for left compound drawable (0 for none)
     * @param rightIcon drawable resource for right compound drawable (0 for none)
     */
    fun restoreRecordButtonIdle(text: String, leftIcon: Int, rightIcon: Int) {
        views.recordButton.text = text
        views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(leftIcon, 0, rightIcon, 0)
        // isEnabled is managed by refreshRecordButtonFromState() — Idle branch sets it to true
        savedRecordButtonTextColors?.let { views.recordButton.setTextColor(it) }
    }

    // ── Auto-enter appearance (visual only, no click listener management) ──

    private fun updateAutoEnterAppearance(active: Boolean) {
        val drawable = if (active) {
            cachedAutoEnterActiveDrawable
                ?: buildAutoEnterActiveDrawable().also { cachedAutoEnterActiveDrawable = it }
        } else {
            cachedAutoEnterInactiveDrawable
                ?: buildAutoEnterInactiveDrawable().also { cachedAutoEnterInactiveDrawable = it }
        }
        views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, drawable, null)
    }

    /**
     * Builds the "active" knockout drawable: white circle with a transparent arrow cutout
     * (PorterDuff DST_OUT). Allocated once and cached in [cachedAutoEnterActiveDrawable].
     */
    private fun buildAutoEnterActiveDrawable(): android.graphics.drawable.Drawable {
        val ctx = views.recordButton.context
        val density = ctx.resources.displayMetrics.density
        val sizePx = (24 * density).toInt()

        val enterIcon = ctx.getDrawable(R.drawable.ic_baseline_subdirectory_arrow_left_24)?.mutate()
        enterIcon?.setBounds(0, 0, sizePx, sizePx)
        enterIcon?.setTint(Color.WHITE)

        // Render icon to a temporary bitmap, offset slightly left+up for optical centering
        val iconBitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val iconCanvas = android.graphics.Canvas(iconBitmap)
        val offset = (-1.5f * density)
        iconCanvas.translate(offset, offset)
        enterIcon?.draw(iconCanvas)

        // Create the knockout composite
        val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // Draw white circle
        paint.color = Color.WHITE
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

        // Punch out the icon using DST_OUT on the icon bitmap
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        canvas.drawBitmap(iconBitmap, 0f, 0f, paint)
        paint.xfermode = null

        iconBitmap.recycle()

        val drawable = android.graphics.drawable.BitmapDrawable(ctx.resources, bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        return drawable
    }

    /**
     * Builds the "inactive" drawable: white arrow icon on a transparent background.
     * Allocated once and cached in [cachedAutoEnterInactiveDrawable].
     */
    private fun buildAutoEnterInactiveDrawable(): android.graphics.drawable.Drawable {
        val ctx = views.recordButton.context
        val density = ctx.resources.displayMetrics.density
        val sizePx = (24 * density).toInt()

        val enterIcon = ctx.getDrawable(R.drawable.ic_baseline_subdirectory_arrow_left_24)?.mutate()!!
        enterIcon.setTint(Color.WHITE)
        enterIcon.setBounds(0, 0, sizePx, sizePx)
        return enterIcon
    }
}
