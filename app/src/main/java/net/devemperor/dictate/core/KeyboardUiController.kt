package net.devemperor.dictate.core

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Handler
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import java.util.Locale

/**
 * Controls the keyboard prompt area UI: mode switching between prompt buttons
 * and pipeline progress view with live elapsed timer per step.
 *
 * Responsibilities:
 * - Centralized visibility management for the two prompt area modes
 * - Pipeline step rows (add running, complete, fail) with live duration timer
 * - Record button text updates during pipeline execution
 *
 * Does NOT handle threading — all methods must be called on the main thread.
 * Does NOT make pipeline/orchestration decisions — the Service controls when to switch modes.
 */
class KeyboardUiController(private val views: PipelineViews) {

    enum class PromptAreaMode {
        PROMPT_BUTTONS, PIPELINE_PROGRESS, RECORDING_INDICATOR
    }

    data class PipelineViews(
        val promptsRv: RecyclerView,
        val pipelineProgressLl: View,
        val pipelineStepsContainer: LinearLayout,
        val pipelineScrollView: ScrollView,
        val recordButton: MaterialButton,
        val infoCl: View,
        val layoutInflater: LayoutInflater,
        val mainHandler: Handler
    )

    var currentMode: PromptAreaMode = PromptAreaMode.PROMPT_BUTTONS
        private set

    private val stepRows = mutableListOf<View>()
    private var totalSteps = 0
    private var currentStep = 0
    private var activeTimer: ElapsedTimer? = null

    // Recording indicator (created lazily, reused)
    private var recordingIndicatorView: LinearLayout? = null
    private var recordingDotAnimator: ObjectAnimator? = null

    // ── Mode switching (centralizes ALL visibility changes) ──

    /**
     * Switches the prompt area to the given mode.
     * Stops any running timer when leaving PIPELINE_PROGRESS.
     */
    fun setMode(mode: PromptAreaMode) {
        currentMode = mode
        when (mode) {
            PromptAreaMode.PROMPT_BUTTONS -> {
                views.promptsRv.visibility = View.VISIBLE
                views.pipelineProgressLl.visibility = View.GONE
                hideRecordingIndicator()
                views.pipelineStepsContainer.removeAllViews()
                stepRows.clear()
                activeTimer?.stop()
                activeTimer = null
            }
            PromptAreaMode.PIPELINE_PROGRESS -> {
                views.promptsRv.visibility = View.GONE
                views.pipelineProgressLl.visibility = View.VISIBLE
                hideRecordingIndicator()
            }
            PromptAreaMode.RECORDING_INDICATOR -> {
                views.promptsRv.visibility = View.GONE
                views.pipelineProgressLl.visibility = View.GONE
                showRecordingIndicatorView()
            }
        }
    }

    /**
     * Enters pipeline progress mode and resets step tracking.
     * Hides the info bar to make room for step rows.
     *
     * @param totalSteps total number of pipeline steps (transcription + format + prompts)
     */
    fun showPipelineProgress(totalSteps: Int) {
        setMode(PromptAreaMode.PIPELINE_PROGRESS)
        stepRows.clear()
        views.pipelineStepsContainer.removeAllViews()
        this.totalSteps = totalSteps
        currentStep = 0
        views.infoCl.visibility = View.GONE
    }

    // ── Pipeline steps (PIPELINE_PROGRESS mode only, main thread) ──

    /**
     * Adds a new step row in "running" state (spinner + name + live timer).
     * Starts a live elapsed timer that updates the duration text every 100ms.
     * Increments the step counter and updates the record button.
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
        val iconTv = row.findViewById<TextView>(R.id.pipeline_step_icon_tv)
        val pb = row.findViewById<ProgressBar>(R.id.pipeline_step_pb)
        val nameTv = row.findViewById<TextView>(R.id.pipeline_step_name_tv)
        val durationTv = row.findViewById<TextView>(R.id.pipeline_step_duration_tv)

        iconTv.visibility = View.GONE
        pb.visibility = View.VISIBLE
        nameTv.text = stepName

        // Show live timer from 0.0s
        durationTv.visibility = View.VISIBLE
        durationTv.text = formatDuration(0)

        views.pipelineStepsContainer.addView(row)
        stepRows.add(row)

        // Start elapsed timer
        activeTimer = ElapsedTimer.start(views.mainHandler) { ms ->
            durationTv.text = formatDuration(ms)
        }

        // Auto-scroll to bottom
        views.pipelineScrollView.post { views.pipelineScrollView.fullScroll(View.FOCUS_DOWN) }

        updateRecordButtonForStep(stepName)
    }

    /**
     * Updates the last step row to "done" state (checkmark + final duration).
     * Stops the live timer and replaces it with the authoritative duration from the orchestrator.
     *
     * @param stepName display name (re-set in case it changed)
     * @param durationMs step duration in milliseconds (from orchestrator, more accurate than timer)
     */
    fun completeStep(stepName: String, durationMs: Long) {
        activeTimer?.stop()
        activeTimer = null

        if (stepRows.isEmpty()) return
        val row = stepRows.last()
        val iconTv = row.findViewById<TextView>(R.id.pipeline_step_icon_tv)
        val pb = row.findViewById<ProgressBar>(R.id.pipeline_step_pb)
        val nameTv = row.findViewById<TextView>(R.id.pipeline_step_name_tv)
        val durationTv = row.findViewById<TextView>(R.id.pipeline_step_duration_tv)

        pb.visibility = View.GONE
        iconTv.visibility = View.VISIBLE
        iconTv.text = "\u2713" // ✓
        iconTv.setTextColor(0xFF4CAF50.toInt()) // Material Green 500
        nameTv.text = stepName
        durationTv.visibility = View.VISIBLE
        durationTv.text = formatDuration(durationMs)
    }

    /**
     * Updates the last step row to "error" state (cross mark).
     * Stops the live timer.
     *
     * @param stepName display name of the failed step
     */
    fun failStep(stepName: String) {
        activeTimer?.stop()
        activeTimer = null

        if (stepRows.isEmpty()) return
        val row = stepRows.last()
        val iconTv = row.findViewById<TextView>(R.id.pipeline_step_icon_tv)
        val pb = row.findViewById<ProgressBar>(R.id.pipeline_step_pb)
        val nameTv = row.findViewById<TextView>(R.id.pipeline_step_name_tv)

        pb.visibility = View.GONE
        iconTv.visibility = View.VISIBLE
        iconTv.text = "\u2715" // ✕
        iconTv.setTextColor(0xFFF44336.toInt()) // Material Red 500
        nameTv.text = stepName
    }

    // ── Record button state ──

    /**
     * Updates the record button text to reflect the current pipeline step.
     * Shows step counter (e.g., "Formatierung (2/5)") when there are more than 2 steps,
     * otherwise just shows the step name with ellipsis.
     *
     * @param stepName current step display name
     */
    fun updateRecordButtonForStep(stepName: String) {
        views.recordButton.text = if (totalSteps > 2) {
            String.format(Locale.getDefault(), "%s (%d/%d)", stepName, currentStep, totalSteps)
        } else {
            "$stepName \u2026"
        }
        views.recordButton.isEnabled = false
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
        views.recordButton.isEnabled = true
    }

    // ── Recording indicator ──

    /**
     * Shows a recording indicator in the prompt area (red blinking dot + "Aufnahme..." text).
     * Call this when the user starts recording while the QWERTZ keyboard is visible.
     */
    fun showRecordingIndicator() {
        setMode(PromptAreaMode.RECORDING_INDICATOR)
    }

    private fun showRecordingIndicatorView() {
        val indicator = getOrCreateRecordingIndicator()
        indicator.visibility = View.VISIBLE

        // Start blinking animation on the red dot
        val dot = indicator.getChildAt(0)
        recordingDotAnimator?.cancel()
        recordingDotAnimator = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.2f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun hideRecordingIndicator() {
        recordingDotAnimator?.cancel()
        recordingDotAnimator = null
        recordingIndicatorView?.visibility = View.GONE
    }

    private fun getOrCreateRecordingIndicator(): LinearLayout {
        recordingIndicatorView?.let { return it }

        val context = views.promptsRv.context

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Red dot
        val dotSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 12f, context.resources.displayMetrics
        ).toInt()
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 8f, context.resources.displayMetrics
                ).toInt()
            }
            val drawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.RED)
            }
            background = drawable
        }
        container.addView(dot)

        // Recording indicator text (localized)
        val textView = TextView(context).apply {
            text = context.getString(R.string.dictate_recording_indicator)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.RED)
        }
        container.addView(textView)

        // Add to the prompts_keyboard_cl parent (same level as RecyclerView and pipeline)
        val parent = views.promptsRv.parent as? android.view.ViewGroup
        parent?.addView(container)

        recordingIndicatorView = container
        return container
    }

    // ── Helpers ──

    private fun formatDuration(ms: Long): String =
        String.format(Locale.US, "%.1fs", ms / 1000.0)
}
