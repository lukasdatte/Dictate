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
import java.util.Locale

/**
 * Controls the keyboard prompt area UI: mode switching between prompt buttons
 * and pipeline progress view with live elapsed timer per step.
 *
 * Owns the [currentMode] state and side-effects (timer stop, container clear).
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

    private val stepRows = mutableListOf<View>()
    private var totalSteps = 0
    private var currentStep = 0
    private var activeTimer: ElapsedTimer? = null

    // ── Timer cleanup (for view-recreation without side-effects) ──

    /** Stops only the active elapsed timer without resetting mode or triggering side-effects. */
    fun stopActiveTimer() {
        activeTimer?.stop()
        activeTimer = null
    }

    // ── Mode switching ──

    /**
     * Enters pipeline progress mode and resets step tracking.
     * Side-effects (container clear, info bar hide) stay here.
     * Visibility recalculation is triggered via [stateManager.refresh].
     *
     * @param totalSteps total number of pipeline steps (transcription + format + prompts)
     */
    fun showPipelineProgress(totalSteps: Int) {
        currentMode = PromptAreaMode.PIPELINE_PROGRESS
        views.pipelineStepsContainer.removeAllViews()
        views.infoCl.visibility = View.GONE
        stepRows.clear()
        this.totalSteps = totalSteps
        currentStep = 0
        stateManager.refresh()
    }

    /**
     * Resets back to prompt buttons mode.
     * Side-effects (timer cancel) stay here.
     * Visibility recalculation is triggered via [stateManager.refresh].
     */
    fun resetToPromptButtons() {
        currentMode = PromptAreaMode.PROMPT_BUTTONS
        activeTimer?.stop()
        activeTimer = null
        stepRows.clear()
        views.pipelineStepsContainer.removeAllViews()
        hideAutoEnterToggle()
        stateManager.refresh()
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
        views.recordButton.setTextColor(Color.WHITE)
        // Record button stays enabled during pipeline for auto-enter toggle
    }

    /**
     * Restores the record button to its idle state after pipeline completion or cancellation.
     * Does NOT call applyRecordingIconState — the Service handles icon color/animation separately.
     *
     * @param text button label text
     * @param leftIcon drawable resource for left compound drawable (0 for none)
     * @param rightIcon drawable resource for right compound drawable (0 for none)
     */
    private var savedRecordButtonTextColors: ColorStateList? = null

    fun restoreRecordButtonIdle(text: String, leftIcon: Int, rightIcon: Int) {
        views.recordButton.text = text
        views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(leftIcon, 0, rightIcon, 0)
        views.recordButton.isEnabled = true
        savedRecordButtonTextColors?.let { views.recordButton.setTextColor(it) }
    }

    // ── Auto-enter toggle (integrated into record button during pipeline) ──

    private var autoEnterToggleCallback: (() -> Unit)? = null

    /**
     * Enables the auto-enter toggle on the record button during pipeline.
     * The record button becomes clickable and shows an enter icon (⏎) on the right
     * that is highlighted when auto-enter is active.
     *
     * @param onRestore called when the toggle is hidden to re-register the original click listener
     */
    fun showAutoEnterToggle(active: Boolean, onToggle: () -> Unit, onRestore: () -> Unit) {
        autoEnterToggleCallback = onToggle
        autoEnterRestoreCallback = onRestore
        if (savedRecordButtonTextColors == null) {
            savedRecordButtonTextColors = views.recordButton.textColors
        }
        views.recordButton.isEnabled = true
        views.recordButton.setOnClickListener { onToggle() }
        updateAutoEnterAppearance(active)
    }

    private var autoEnterRestoreCallback: (() -> Unit)? = null

    fun updateAutoEnterToggle(active: Boolean) {
        updateAutoEnterAppearance(active)
    }

    fun hideAutoEnterToggle() {
        autoEnterToggleCallback = null
        views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        // Restore original click listener via callback
        autoEnterRestoreCallback?.invoke()
        autoEnterRestoreCallback = null
    }

    private fun updateAutoEnterAppearance(active: Boolean) {
        val ctx = views.recordButton.context
        val density = ctx.resources.displayMetrics.density
        val sizePx = (24 * density).toInt()

        if (active) {
            // Inverted: white rounded-rect with transparent icon cutout (knockout effect)
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
            views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, drawable, null)
        } else {
            // Default: white icon on transparent background
            val enterIcon = ctx.getDrawable(R.drawable.ic_baseline_subdirectory_arrow_left_24)?.mutate()
            enterIcon?.setTint(Color.WHITE)
            enterIcon?.setBounds(0, 0, sizePx, sizePx)
            views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, enterIcon, null)
        }
    }

    // ── Helpers ──

    private fun formatDuration(ms: Long): String =
        String.format(Locale.US, "%.1fs", ms / 1000.0)
}
