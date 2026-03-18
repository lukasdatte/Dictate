package net.devemperor.dictate.core

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.widget.AmplitudeVisualizerDrawable
import net.devemperor.dictate.widget.RecordingAnimation
import net.devemperor.dictate.widget.computeVisualizerBarColor
import java.util.Locale

/**
 * Handles all recording-related UI updates.
 *
 * Implements [RecordingStateController.Callback] for the UI-relevant methods
 * (state changes, amplitude, timer). Lifecycle events (completed, error, keepAwake)
 * are handled by the Service via a composite callback wrapper.
 *
 * Extracted from DictateInputMethodService to separate recording UI from
 * recording coordination logic.
 */
class RecordingUiController(
    private val recordButton: MaterialButton,
    private val pauseButton: MaterialButton,
    private val resendButton: MaterialButton,
    private val recordingAnimation: RecordingAnimation,
    private val stateManager: KeyboardStateManager,
    private val context: Context,
    private val getDictateButtonText: () -> String,
    private val isAnimationEnabled: () -> Boolean,
    private val getLastAudioFileExists: () -> Boolean,
    private val qwertzRecButtonProvider: () -> MaterialButton? = { null },
    private val promptRecButton: MaterialButton? = null
) : RecordingStateController.Callback {

    private var promptsVisualizer: AmplitudeVisualizerDrawable? = null

    init {
        recordingAnimation.prepare(recordButton)
        if (promptRecButton != null) {
            setupPromptsVisualizer()
        }
    }

    override fun onStateChanged(oldState: RecordingState, newState: RecordingState) {
        when (newState) {
            is RecordingState.Idle -> applyIdleState()
            is RecordingState.Preparing -> applyPreparingState()
            is RecordingState.Active -> applyActiveState(newState.useBluetooth)
            is RecordingState.Paused -> applyPausedState()
        }
        updateQwertzRecButton(newState.isRecordingOrPaused)
        stateManager.refresh()
    }

    override fun onAmplitudeUpdate(level: Float) {
        recordingAnimation.onAmplitude(level)
        promptsVisualizer?.pushAmplitude(level)
    }

    override fun onTimerTick(elapsedMs: Long) {
        val timerText = String.format(
            Locale.getDefault(), "%02d:%02d",
            (elapsedMs / 60000).toInt(),
            ((elapsedMs / 1000) % 60).toInt()
        )
        // Timer is displayed inside the amplitude visualizer, not as button text
        recordingAnimation.onTimerTick(timerText)
        promptsVisualizer?.setTimerText(timerText)
    }

    /** Updates animation color (e.g. after theme change). */
    fun updateAnimationColor(accentColor: Int) {
        recordingAnimation.updateColor(accentColor)
        promptsVisualizer?.updateBarColor(computeVisualizerBarColor(accentColor))
    }

    // ── Prompts Visualizer ──

    /**
     * Creates an adaptive [AmplitudeVisualizerDrawable] for the prompts recording
     * indicator button. The visualizer gracefully degrades when the button is small
     * (hides send icon and timer automatically).
     */
    private fun setupPromptsVisualizer() {
        val btn = promptRecButton ?: return
        val density = btn.resources.displayMetrics.density
        val accentColor = context.getColor(R.color.dictate_blue)

        promptsVisualizer = AmplitudeVisualizerDrawable(
            sendIcon = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_send_20),
            barColor = computeVisualizerBarColor(accentColor),
            barCountMode = AmplitudeVisualizerDrawable.BarCountMode.Adaptive(minBars = 3),
            textColor = Color.WHITE,
            textSizePx = density * 12f,
            insetTopPx = density * 4f,
            insetBottomPx = density * 4f
        )
    }

    // ── State Application ──

    private fun applyIdleState() {
        recordButton.text = getDictateButtonText()
        recordButton.isEnabled = true
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0
        )
        pauseButton.foreground = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_pause_24)

        if (isAnimationEnabled()) {
            recordingAnimation.cancel()
        }

        // Reset prompts visualizer
        promptsVisualizer?.reset()
        promptRecButton?.foreground = null

        // Show resend button if previous audio exists
        resendButton.visibility = if (getLastAudioFileExists()) View.VISIBLE else View.GONE
    }

    private fun applyPreparingState() {
        recordButton.isEnabled = false
    }

    private fun applyActiveState(useBluetooth: Boolean) {
        recordButton.isEnabled = true
        recordButton.setText(R.string.dictate_send)

        if (useBluetooth) {
            recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_baseline_send_20, 0, R.drawable.ic_baseline_bluetooth_20, 0
            )
        } else {
            recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_baseline_send_20, 0, 0, 0
            )
        }

        resendButton.visibility = View.GONE
        pauseButton.foreground = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_pause_24)

        if (isAnimationEnabled()) {
            recordingAnimation.start()
        }

        // Activate prompts visualizer
        promptRecButton?.let { btn ->
            btn.text = ""
            btn.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            btn.foreground = promptsVisualizer
        }

        // Recording controls visibility is handled by KeyboardStateManager.refresh()
        // which is called via onStateChanged -> stateManager.refresh()
    }

    private fun applyPausedState() {
        pauseButton.foreground = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_mic_24)

        if (isAnimationEnabled()) {
            recordingAnimation.pause()
        }
    }

    // ── QWERTZ Rec Button ──

    /**
     * Updates the QWERTZ bottom-row Rec button icon to reflect recording state.
     * Mic icon when idle, stop icon when recording/paused.
     */
    fun updateQwertzRecButton(isActive: Boolean) {
        val recButton = qwertzRecButtonProvider() ?: return
        val iconRes = if (isActive) R.drawable.ic_baseline_stop_24 else R.drawable.ic_baseline_mic_24
        recButton.icon = AppCompatResources.getDrawable(context, iconRes)
    }
}
