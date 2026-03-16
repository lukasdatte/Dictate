package net.devemperor.dictate.core

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.widget.AmplitudeVisualizerDrawable
import net.devemperor.dictate.widget.RecordingAnimation
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
    private val uiController: KeyboardUiController,
    private val stateManager: KeyboardStateManager,
    private val context: Context,
    private val getDictateButtonText: () -> String,
    private val isAnimationEnabled: () -> Boolean,
    private val getLastAudioFileExists: () -> Boolean,
    private val qwertzRecButtonProvider: () -> MaterialButton? = { null },
    private val actionBarRecButton: MaterialButton? = null,
    private val getAccentColor: () -> Int = { -14700810 }
) : RecordingStateController.Callback {

    private var actionBarVisualizer: AmplitudeVisualizerDrawable? = null
    private var actionBarPreviousForeground: Drawable? = null

    init {
        recordingAnimation.prepare(recordButton)
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
        actionBarVisualizer?.pushAmplitude(level)
    }

    override fun onTimerTick(elapsedMs: Long) {
        val timerText = String.format(
            Locale.getDefault(), "%02d:%02d",
            (elapsedMs / 60000).toInt(),
            ((elapsedMs / 1000) % 60).toInt()
        )
        // Timer is displayed inside the amplitude visualizer, not as button text
        recordingAnimation.onTimerTick(timerText)
        actionBarVisualizer?.setTimerText(timerText)
    }

    /** Updates animation color (e.g. after theme change). */
    fun updateAnimationColor(accentColor: Int) {
        recordingAnimation.updateColor(accentColor)
        updateActionBarVisualizerColor(accentColor)
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

        // Restore Action Bar rec button to mic icon
        restoreActionBarRecButton()

        // Show resend button if previous audio exists
        resendButton.visibility = if (getLastAudioFileExists()) View.VISIBLE else View.GONE

        // Restore recording indicator if it was showing
        if (uiController.currentMode == KeyboardUiController.PromptAreaMode.RECORDING_INDICATOR) {
            uiController.setMode(KeyboardUiController.PromptAreaMode.PROMPT_BUTTONS)
        }
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

        // Create and set amplitude visualizer on Action Bar rec button
        setupActionBarVisualizer()

        // Show recording indicator when QWERTZ keyboard is visible
        if (stateManager.contentArea == ContentArea.QWERTZ) {
            uiController.showRecordingIndicator()
        }
    }

    private fun applyPausedState() {
        pauseButton.foreground = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_mic_24)

        if (isAnimationEnabled()) {
            recordingAnimation.pause()
        }
    }

    // ── Action Bar Rec Button Visualizer ──

    private fun setupActionBarVisualizer() {
        val btn = actionBarRecButton ?: return
        actionBarPreviousForeground = btn.foreground
        val density = btn.resources.displayMetrics.density
        val sendIcon = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_send_20)

        // Compute bar color from accent (lighter version for contrast)
        val accentColor = getAccentColor()
        val hsv = FloatArray(3)
        Color.colorToHSV(accentColor, hsv)
        hsv[1] = (hsv[1] * 0.4f).coerceAtMost(1f)
        hsv[2] = 1f
        val barColor = Color.HSVToColor(hsv)

        actionBarVisualizer = AmplitudeVisualizerDrawable(
            sendIcon = sendIcon,
            barColor = barColor,
            barCount = 8,
            textColor = Color.WHITE,
            textSizePx = density * 11f,
            insetTopPx = density * 6f,
            insetBottomPx = density * 6f
        )
        btn.foreground = actionBarVisualizer
    }

    private fun restoreActionBarRecButton() {
        val btn = actionBarRecButton ?: return
        actionBarVisualizer?.reset()
        actionBarVisualizer = null
        btn.foreground = actionBarPreviousForeground ?: AppCompatResources.getDrawable(context, R.drawable.ic_baseline_mic_24)
        actionBarPreviousForeground = null
    }

    /** Updates Action Bar visualizer bar color (e.g. after theme change). */
    fun updateActionBarVisualizerColor(accentColor: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(accentColor, hsv)
        hsv[1] = (hsv[1] * 0.4f).coerceAtMost(1f)
        hsv[2] = 1f
        actionBarVisualizer?.updateBarColor(Color.HSVToColor(hsv))
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
