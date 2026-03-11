package net.devemperor.dictate.core

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import java.util.Locale

/**
 * Controls the keyboard prompt area UI: mode switching between prompt buttons,
 * standalone spinner, and pipeline progress view.
 *
 * Responsibilities:
 * - Centralized visibility management for the three prompt area modes
 * - Pipeline step rows (add running, complete, fail)
 * - Record button text updates during pipeline execution
 *
 * Does NOT handle threading — all methods must be called on the main thread.
 * Does NOT make pipeline/orchestration decisions — the Service controls when to switch modes.
 */
class KeyboardUiController(private val views: PipelineViews) {

    enum class PromptAreaMode {
        PROMPT_BUTTONS, STANDALONE_SPINNER, PIPELINE_PROGRESS
    }

    data class PipelineViews(
        val promptsRv: RecyclerView,
        val runningPromptTv: TextView,
        val runningPromptPb: ProgressBar,
        val pipelineProgressLl: View,
        val pipelineStepsContainer: LinearLayout,
        val pipelineScrollView: ScrollView,
        val recordButton: MaterialButton,
        val infoCl: View,
        val layoutInflater: LayoutInflater
    )

    var currentMode: PromptAreaMode = PromptAreaMode.PROMPT_BUTTONS
        private set

    private val stepRows = mutableListOf<View>()
    private var totalSteps = 0
    private var currentStep = 0

    // ── Mode switching (centralizes ALL visibility changes) ──

    /**
     * Switches the prompt area to the given mode.
     * Each mode has a distinct set of visible/hidden views.
     */
    fun setMode(mode: PromptAreaMode) {
        currentMode = mode
        when (mode) {
            PromptAreaMode.PROMPT_BUTTONS -> {
                views.promptsRv.visibility = View.VISIBLE
                views.runningPromptTv.visibility = View.GONE
                views.runningPromptPb.visibility = View.GONE
                views.pipelineProgressLl.visibility = View.GONE
                views.pipelineStepsContainer.removeAllViews()
                stepRows.clear()
            }
            PromptAreaMode.STANDALONE_SPINNER -> {
                views.promptsRv.visibility = View.GONE
                views.runningPromptTv.visibility = View.VISIBLE
                views.runningPromptPb.visibility = View.VISIBLE
                views.pipelineProgressLl.visibility = View.GONE
            }
            PromptAreaMode.PIPELINE_PROGRESS -> {
                views.promptsRv.visibility = View.GONE
                views.runningPromptTv.visibility = View.GONE
                views.runningPromptPb.visibility = View.GONE
                views.pipelineProgressLl.visibility = View.VISIBLE
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

    /**
     * Shows the standalone spinner with a display name.
     * Used for single prompts outside a recording pipeline.
     *
     * @param displayName the prompt name to show next to the spinner
     */
    fun showStandaloneSpinner(displayName: String) {
        setMode(PromptAreaMode.STANDALONE_SPINNER)
        views.runningPromptTv.text = displayName
        views.infoCl.visibility = View.GONE
    }

    // ── Pipeline steps (PIPELINE_PROGRESS mode only, main thread) ──

    /**
     * Adds a new step row in "running" state (spinner + name).
     * Increments the step counter and updates the record button.
     *
     * @param stepName display name for the step (e.g., "Transkription", "Formatierung")
     */
    fun addRunningStep(stepName: String) {
        currentStep++

        val row = views.layoutInflater.inflate(
            R.layout.item_pipeline_step_row,
            views.pipelineStepsContainer,
            false
        )
        val iconTv = row.findViewById<TextView>(R.id.pipeline_step_icon_tv)
        val pb = row.findViewById<ProgressBar>(R.id.pipeline_step_pb)
        val nameTv = row.findViewById<TextView>(R.id.pipeline_step_name_tv)

        iconTv.visibility = View.GONE
        pb.visibility = View.VISIBLE
        nameTv.text = stepName

        views.pipelineStepsContainer.addView(row)
        stepRows.add(row)

        // Auto-scroll to bottom
        views.pipelineScrollView.post { views.pipelineScrollView.fullScroll(View.FOCUS_DOWN) }

        updateRecordButtonForStep(stepName)
    }

    /**
     * Updates the last step row to "done" state (checkmark + duration).
     *
     * @param stepName display name (re-set in case it changed)
     * @param durationMs step duration in milliseconds
     */
    fun completeStep(stepName: String, durationMs: Long) {
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
        durationTv.text = String.format(Locale.US, "%.1fs", durationMs / 1000.0)
    }

    /**
     * Updates the last step row to "error" state (cross mark, red name).
     *
     * @param stepName display name of the failed step
     */
    fun failStep(stepName: String) {
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
}
