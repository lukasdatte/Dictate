package net.devemperor.dictate.widget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * Custom Drawable that renders a mini amplitude waveform with timer text.
 *
 * Layout (left to right):
 * ```
 * [send icon]  [||||amplitude bars||||]  [MM:SS]
 * ```
 *
 * The bars show a rolling history of recent amplitude values, creating a
 * scrolling waveform effect. New values are pushed from the right.
 *
 * Designed to be used as a MaterialButton's foreground during recording.
 *
 * Supports two bar count modes:
 * - [BarCountMode.Fixed]: Always renders exactly N bars
 * - [BarCountMode.Adaptive]: Computes bar count from available width, gracefully
 *   hiding the send icon and timer when space is tight
 *
 * @param sendIcon drawable for the send icon (left side)
 * @param barColor color for the amplitude bars
 * @param barCountMode how to determine the number of bars
 * @param textColor color for the timer text
 * @param textSizePx timer text size in pixels
 * @param insetTopPx MaterialButton insetTop in pixels (default 6dp)
 * @param insetBottomPx MaterialButton insetBottom in pixels (default 6dp)
 */
class AmplitudeVisualizerDrawable(
    private val sendIcon: Drawable?,
    private var barColor: Int,
    private val barCountMode: BarCountMode = BarCountMode.Fixed(12),
    textColor: Int = Color.WHITE,
    private val textSizePx: Float = 36f,
    private val insetTopPx: Float = 0f,
    private val insetBottomPx: Float = 0f
) : Drawable() {

    /**
     * Determines how many amplitude bars to render.
     */
    sealed class BarCountMode {
        /** Always render exactly [count] bars. */
        data class Fixed(val count: Int) : BarCountMode()
        /** Compute bar count from available width, with a floor of [minBars]. */
        data class Adaptive(val minBars: Int = 3) : BarCountMode()
    }

    companion object {
        /** Maximum number of amplitude samples kept in the rolling buffer. */
        private const val MAX_BAR_COUNT = 30

        /** Minimum bar width in pixels before we stop rendering. */
        private const val MIN_BAR_WIDTH_PX = 3f
    }

    private val amplitudeBuffer = FloatArray(MAX_BAR_COUNT)
    private var timerText: String = ""

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = barColor
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = textSizePx
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
    }

    private val barRect = RectF()

    /** Pushes a new amplitude value (0.0-1.0) into the rolling buffer. */
    fun pushAmplitude(level: Float) {
        if (bounds.isEmpty) return
        // Shift left
        System.arraycopy(amplitudeBuffer, 1, amplitudeBuffer, 0, MAX_BAR_COUNT - 1)
        // Add new value at the end (rightmost bar = most recent)
        amplitudeBuffer[MAX_BAR_COUNT - 1] = level.coerceIn(0f, 1f)
        invalidateSelf()
    }

    /** Sets the timer display text (e.g. "01:23"). */
    fun setTimerText(text: String) {
        timerText = text
        invalidateSelf()
    }

    /** Updates the bar color (e.g. after theme change). */
    fun updateBarColor(color: Int) {
        barColor = color
        barPaint.color = color
        invalidateSelf()
    }

    /** Resets all bars to zero. */
    fun reset() {
        amplitudeBuffer.fill(0f)
        timerText = ""
        invalidateSelf()
    }

    private fun computeEffectiveBarCount(barsAreaWidth: Float): Int {
        return when (barCountMode) {
            is BarCountMode.Fixed -> barCountMode.count
            is BarCountMode.Adaptive -> {
                val barSpacing = barsAreaWidth * 0.02f
                val computed = (barsAreaWidth / (MIN_BAR_WIDTH_PX + barSpacing)).toInt()
                max(barCountMode.minBars, computed.coerceAtMost(MAX_BAR_COUNT))
            }
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return

        // Account for MaterialButton insets
        val contentTop = b.top + insetTopPx
        val contentBottom = b.bottom - insetBottomPx
        val contentHeight = contentBottom - contentTop
        if (contentHeight <= 0) return

        val paddingH = contentHeight * 0.35f  // horizontal padding
        val contentLeft = b.left + paddingH
        val contentRight = b.right - paddingH
        val contentWidth = contentRight - contentLeft
        if (contentWidth <= 0) return

        // ── 1. Send Icon (left side, only if enough space) ──
        val iconSize = (contentHeight * 0.45f).toInt()
        val showIcon = barCountMode !is BarCountMode.Adaptive ||
                contentWidth > iconSize * 4
        val afterIcon: Float
        if (showIcon && sendIcon != null) {
            val iconLeft = contentLeft.toInt()
            val iconTop = (contentTop + (contentHeight - iconSize) / 2f).toInt()
            sendIcon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
            sendIcon.draw(canvas)
            afterIcon = iconLeft + iconSize + paddingH * 0.5f
        } else {
            afterIcon = contentLeft
        }

        // ── 2. Timer Text (right side, only if enough space) ──
        val timerWidth = if (timerText.isNotEmpty()) textPaint.measureText(timerText) else 0f
        val showTimer = barCountMode !is BarCountMode.Adaptive ||
                contentWidth > timerWidth * 2
        val beforeTimer: Float
        if (showTimer && timerText.isNotEmpty()) {
            val timerX = contentRight
            val timerY = contentTop + contentHeight / 2f -
                    (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(timerText, timerX, timerY, textPaint)
            beforeTimer = contentRight - timerWidth - paddingH * 0.5f
        } else {
            beforeTimer = contentRight
        }

        // ── 3. Amplitude Bars (center area) ──
        val barsAreaWidth = beforeTimer - afterIcon
        if (barsAreaWidth <= 0) return

        val effectiveBarCount = computeEffectiveBarCount(barsAreaWidth)
        if (effectiveBarCount < 2) return

        val barSpacing = barsAreaWidth * 0.02f  // 2% gap between bars
        val totalSpacing = barSpacing * (effectiveBarCount - 1)
        val barWidth = (barsAreaWidth - totalSpacing) / effectiveBarCount
        if (barWidth <= 0) return

        val maxBarHeight = contentHeight * 0.55f
        val minBarHeight = contentHeight * 0.06f
        val barCenterY = contentTop + contentHeight / 2f
        val barCornerRadius = barWidth / 2f  // pill-shaped bars

        // Render the last N values from the buffer
        val bufferOffset = MAX_BAR_COUNT - effectiveBarCount

        for (i in 0 until effectiveBarCount) {
            val amplitude = amplitudeBuffer[bufferOffset + i]
            val barHeight = minBarHeight + (maxBarHeight - minBarHeight) * amplitude

            val x = afterIcon + i * (barWidth + barSpacing)
            val halfH = barHeight / 2f

            // Fade older bars slightly (leftmost = oldest = more transparent)
            val ageFactor = 0.4f + 0.6f * (i.toFloat() / (effectiveBarCount - 1))
            barPaint.alpha = (255 * ageFactor).toInt()

            barRect.set(x, barCenterY - halfH, x + barWidth, barCenterY + halfH)
            canvas.drawRoundRect(barRect, barCornerRadius, barCornerRadius, barPaint)
        }

        // Reset alpha for next frame
        barPaint.alpha = 255
    }

    override fun setAlpha(alpha: Int) { /* controlled per-bar */ }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        barPaint.colorFilter = colorFilter
        textPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
