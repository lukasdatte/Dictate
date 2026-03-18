package net.devemperor.dictate.widget

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import com.google.android.material.button.MaterialButton

/**
 * A [RecordingAnimation] that transforms the record button into a live
 * amplitude visualizer during recording.
 *
 * During recording, the button displays:
 * - **Left:** Send icon
 * - **Center:** Rolling amplitude bars (waveform)
 * - **Right:** Timer (MM:SS)
 *
 * The button text is cleared and the visualizer is drawn as a foreground Drawable.
 * On cancel, the original button state is restored.
 *
 * Additionally, the button background color brightens with amplitude and a
 * stroke border grows, providing a secondary "glow" effect on the button itself.
 *
 * @param baseColor button's resting accent color (for brightness modulation)
 * @param sendIcon drawable for the send icon inside the visualizer
 * @param barCountMode how to determine the number of amplitude bars
 * @param maxBrightnessBoost how much the button brightens at full amplitude (0.0-1.0)
 * @param density display density for dp-to-px conversion
 */
class BorderGlowAnimation(
    private var baseColor: Int,
    private val sendIcon: Drawable?,
    private val barCountMode: AmplitudeVisualizerDrawable.BarCountMode = AmplitudeVisualizerDrawable.BarCountMode.Fixed(12),
    private val maxBrightnessBoost: Float = 0.45f,
    private val density: Float = 1f
) : RecordingAnimation {

    companion object {
        private const val PAUSE_BASELINE = 0.12f
    }

    private var button: MaterialButton? = null
    private var visualizer: AmplitudeVisualizerDrawable? = null
    private var previousForeground: Drawable? = null
    private var previousText: CharSequence? = null
    private var previousStartDrawable: Drawable? = null
    private var previousEndDrawable: Drawable? = null
    private var isPaused = false
    private var isActive = false

    // HSV cache for base color
    private val baseHsv = FloatArray(3)
    private var barVisualColor: Int = Color.WHITE

    init {
        recomputeColors()
    }

    override fun prepare(target: View) {
        button = target as? MaterialButton
    }

    override fun start() {
        val btn = button ?: return

        // Save original button state
        if (!isActive) {
            previousForeground = btn.foreground
            previousText = btn.text
            previousStartDrawable = btn.compoundDrawablesRelative[0]
            previousEndDrawable = btn.compoundDrawablesRelative[2]
        }

        // Create visualizer drawable
        val insetTop = density * 6f  // MaterialButton default insetTop
        val insetBottom = density * 6f
        visualizer = AmplitudeVisualizerDrawable(
            sendIcon = sendIcon,
            barColor = barVisualColor,
            barCountMode = barCountMode,
            textColor = Color.WHITE,
            textSizePx = density * 13f,
            insetTopPx = insetTop,
            insetBottomPx = insetBottom
        )

        // Transform button: clear text + drawables, set visualizer as foreground
        btn.text = ""
        btn.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        btn.foreground = visualizer

        isPaused = false
        isActive = true
    }

    override fun pause() {
        isPaused = true
        applyBackgroundLevel(PAUSE_BASELINE)
        // Bars stay frozen at their last values (no new pushes)
    }

    override fun resume() {
        isPaused = false
    }

    override fun cancel() {
        if (!isActive) return
        isPaused = false
        isActive = false
        restoreButton()
    }

    override fun onAmplitude(level: Float) {
        if (!isActive || isPaused) return
        visualizer?.pushAmplitude(level)
        applyBackgroundLevel(level)
    }

    override fun onTimerTick(timerText: String) {
        if (!isActive) return
        visualizer?.setTimerText(timerText)
    }

    override fun updateColor(color: Int) {
        baseColor = color
        recomputeColors()
        visualizer?.updateBarColor(barVisualColor)
    }

    // ── Internal ──

    private fun applyBackgroundLevel(level: Float) {
        val btn = button ?: return

        // Brighten background via HSV
        val hsv = baseHsv.copyOf()
        hsv[2] = (hsv[2] + maxBrightnessBoost * level).coerceAtMost(1f)
        btn.setBackgroundColor(Color.HSVToColor(Color.alpha(baseColor), hsv))
    }

    private fun restoreButton() {
        val btn = button ?: return
        btn.foreground = previousForeground
        btn.setBackgroundColor(baseColor)
        visualizer?.reset()
        visualizer = null
        // Text and drawables are restored by RecordingUiController.applyIdleState()
    }

    private fun recomputeColors() {
        Color.colorToHSV(baseColor, baseHsv)
        barVisualColor = computeVisualizerBarColor(baseColor)
    }
}
