package net.devemperor.dictate.companion.ui.panel

/**
 * The recording widget's design language as pure numbers and curves — the 1:1 port of the Android
 * originals (desktop-host.md §7.1–§7.3, F19). Every constant cites its Android source; the
 * `RecordingBarDesignTest` asserts the transfer (acceptance §2 criterion 9), so a drive-by "tweak"
 * here fails a test instead of silently forking the product's look.
 *
 * Compose-free on purpose: `RecordingBar.kt` does the drawing, this object does the math.
 */
object RecordingBarDesign {

    /** Ring-buffer size / visible bars — `AmplitudeVisualizerDrawable.kt:66`, `RecordGlowFactory.kt:41`. */
    const val BAR_COUNT = 30

    /** Bar gap as a fraction of one bar cell — `AmplitudeVisualizerDrawable.kt:220`. */
    const val GAP_FRACTION = 0.02f

    /** Max/min bar height as fractions of the widget height — `AmplitudeVisualizerDrawable.kt:225-226`. */
    const val MAX_HEIGHT_FRACTION = 0.55f
    const val MIN_HEIGHT_FRACTION = 0.06f

    /** Amplitude push cadence — the Android ticker runs at 10 Hz (`AmplitudeVisualizerDrawable.kt:326`). */
    const val UPDATE_PERIOD_MILLIS = 100L

    /** Age-fade floor: the oldest (leftmost) bar draws at α 0.4 — `AmplitudeVisualizerDrawable.kt:241-242`. */
    const val MIN_ALPHA = 0.4f

    /** Pastel bar color: accent with saturation × 0.4, value 1.0 — `VisualizerUtils.kt:11-16`. */
    const val PASTEL_SATURATION_FACTOR = 0.4f

    /** Glow: brightness couples to amplitude, `v = baseV + 0.35 × level` — `BorderGlowAnimation.kt:154-156`. */
    const val GLOW_LEVEL_GAIN = 0.35f

    /** Pause keeps a faint baseline glow of +0.12 — `RecordGlowFactory.kt:38`. */
    const val PAUSE_GLOW_BASELINE = 0.12f

    /** Breathing pulse: peak↔dim, 1500 ms, infinite reverse — `RecordingAnimationController.kt:320-326`. */
    const val PULSE_PERIOD_MILLIS = 1500
    /** dim = darken(peak, 0.18) — `RecordingAnimationController.kt:324`. */
    const val PULSE_DARKEN_FRACTION = 0.18f

    /** Icon size / horizontal padding relative to widget height — `AmplitudeVisualizerDrawable.kt:16-24`. */
    const val ICON_SIZE_FRACTION = 0.45f
    const val H_PADDING_FRACTION = 0.35f

    /** Shift-left push, newest right, clamped 0..1 — `AmplitudeVisualizerDrawable.kt:96-103`. */
    fun pushLevel(levels: List<Float>, level: Float): List<Float> =
        levels.drop(1) + level.coerceIn(0f, 1f)

    /** A cold buffer: [BAR_COUNT] silent bars. */
    fun emptyLevels(): List<Float> = List(BAR_COUNT) { 0f }

    /** Age fade `α = 0.4 + 0.6 × (i / (n−1))` — left α 0.4 … right α 1.0 (`:241-242`). */
    fun ageFadeAlpha(index: Int, count: Int = BAR_COUNT): Float =
        if (count <= 1) 1f else MIN_ALPHA + (1f - MIN_ALPHA) * (index.toFloat() / (count - 1))

    /** Height mapping `minH + (maxH − minH) × amplitude`, as a fraction of the widget height (`:235`). */
    fun barHeightFraction(amplitude: Float): Float =
        MIN_HEIGHT_FRACTION + (MAX_HEIGHT_FRACTION - MIN_HEIGHT_FRACTION) * amplitude.coerceIn(0f, 1f)

    /** Pill cap: `cornerRadius = barWidth / 2` (`:228`). */
    fun capCornerRadius(barWidth: Float): Float = barWidth / 2f

    /** Pastel transform in HSV space: `sat × 0.4, val = 1.0` (`VisualizerUtils.kt:11-16`). */
    fun pastel(hsv: FloatArray): FloatArray =
        floatArrayOf(hsv[0], hsv[1] * PASTEL_SATURATION_FACTOR, 1f)

    /** Glow brightness for [level], clamped to valid HSV value range (`BorderGlowAnimation.kt:154`). */
    fun glowValue(baseValue: Float, level: Float): Float =
        (baseValue + GLOW_LEVEL_GAIN * level.coerceIn(0f, 1f)).coerceIn(0f, 1f)

    /** `darken(color, fraction)`: scale each RGB channel by `1 − fraction` (pulse dim endpoint). */
    fun darken(argb: Int, fraction: Float = PULSE_DARKEN_FRACTION): Int {
        val keep = (1f - fraction).coerceIn(0f, 1f)
        val a = argb ushr 24 and 0xFF
        val r = ((argb ushr 16 and 0xFF) * keep).toInt()
        val g = ((argb ushr 8 and 0xFF) * keep).toInt()
        val b = ((argb and 0xFF) * keep).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** `MM:SS`, bold-white on Android — `%02d:%02d` (`AmplitudeVisualizerDrawable` timer). */
    fun formatTimer(elapsedMillis: Long): String {
        val totalSeconds = (elapsedMillis / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    /** ARGB → HSV (h 0..360, s 0..1, v 0..1). Hand-rolled: pure and identical on every platform. */
    fun rgbToHsv(argb: Int): FloatArray {
        val r = (argb ushr 16 and 0xFF) / 255f
        val g = (argb ushr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val h = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val s = if (max == 0f) 0f else delta / max
        return floatArrayOf(h, s, max)
    }

    /** HSV → opaque ARGB. Inverse of [rgbToHsv]. */
    fun hsvToRgb(hsv: FloatArray): Int {
        val h = ((hsv[0] % 360f) + 360f) % 360f
        val s = hsv[1].coerceIn(0f, 1f)
        val v = hsv[2].coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
