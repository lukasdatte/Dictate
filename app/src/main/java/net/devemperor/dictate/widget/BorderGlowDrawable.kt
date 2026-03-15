package net.devemperor.dictate.widget

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Simple foreground overlay that draws a semi-transparent color fill.
 *
 * Used in combination with Android's native shadow system (elevation +
 * colored outlineAmbientShadowColor/outlineSpotShadowColor) to create
 * a "button lights up" effect.
 *
 * The overlay provides the on-button brightening, while the shadow system
 * handles the smooth outer glow. Together they make the button feel alive.
 *
 * @param overlayColor base overlay color (typically the accent color)
 * @param cornerRadiusPx corner radius matching the button shape
 * @param maxAlpha maximum alpha at glowLevel=1.0 (0–255, default 45 = ~18%)
 */
class BorderGlowDrawable(
    var overlayColor: Int,
    private val cornerRadiusPx: Float,
    private val maxAlpha: Int = 45
) : Drawable() {

    private var glowLevel: Float = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    fun setGlowLevel(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        if (clamped != glowLevel) {
            glowLevel = clamped
            invalidateSelf()
        }
    }

    fun getGlowLevel(): Float = glowLevel

    override fun draw(canvas: Canvas) {
        if (glowLevel == 0f) return

        val b = bounds
        if (b.isEmpty) return

        rect.set(b)

        // Simple color fill with amplitude-driven alpha
        val alpha = (maxAlpha * glowLevel).toInt().coerceIn(0, 255)
        paint.color = (overlayColor and 0x00FFFFFF) or (alpha shl 24)
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
    }

    override fun setAlpha(alpha: Int) { /* controlled via glowLevel */ }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
