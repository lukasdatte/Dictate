package net.devemperor.dictate.widget

import android.graphics.Color

/**
 * Computes a lighter bar color from an accent color for use in [AmplitudeVisualizerDrawable].
 *
 * Reduces saturation to 40% and maximizes brightness, producing a pastel tint
 * that contrasts well against the darker accent button background.
 */
fun computeVisualizerBarColor(accentColor: Int): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(accentColor, hsv)
    hsv[1] = (hsv[1] * 0.4f).coerceAtMost(1f)
    hsv[2] = 1f
    return Color.HSVToColor(hsv)
}
