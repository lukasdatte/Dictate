package net.devemperor.dictate.companion.ui.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Design-parameter transfer (acceptance §2 criterion 9): the Compose recording bar uses exactly the
 * Android widget's numbers and curves (desktop-host.md §7.1–§7.3). Each assertion cites the Android
 * source it must match — a "tweak" on the desktop side fails here instead of silently forking F19.
 */
class RecordingBarDesignTest {

    private val epsilon = 1e-4f

    @Test
    fun coreParameters_matchTheAndroidWidget() {
        assertEquals("ring buffer (AmplitudeVisualizerDrawable:66)", 30, RecordingBarDesign.BAR_COUNT)
        assertEquals("gap 2% (:220)", 0.02f, RecordingBarDesign.GAP_FRACTION, epsilon)
        assertEquals("max height 0.55h (:225)", 0.55f, RecordingBarDesign.MAX_HEIGHT_FRACTION, epsilon)
        assertEquals("min height 0.06h (:226)", 0.06f, RecordingBarDesign.MIN_HEIGHT_FRACTION, epsilon)
        assertEquals("10 Hz ticker (:326)", 100L, RecordingBarDesign.UPDATE_PERIOD_MILLIS)
        assertEquals("age-fade floor (:241)", 0.4f, RecordingBarDesign.MIN_ALPHA, epsilon)
        assertEquals("pastel sat*0.4 (VisualizerUtils:11-16)", 0.4f, RecordingBarDesign.PASTEL_SATURATION_FACTOR, epsilon)
        assertEquals("glow +0.35*level (BorderGlowAnimation:154)", 0.35f, RecordingBarDesign.GLOW_LEVEL_GAIN, epsilon)
        assertEquals("pause baseline +0.12 (RecordGlowFactory:38)", 0.12f, RecordingBarDesign.PAUSE_GLOW_BASELINE, epsilon)
        assertEquals("breathing pulse 1500 ms (RecordingAnimationController:322)", 1500, RecordingBarDesign.PULSE_PERIOD_MILLIS)
        assertEquals("dim = darken(peak, 0.18) (:324)", 0.18f, RecordingBarDesign.PULSE_DARKEN_FRACTION, epsilon)
        assertEquals("icon 0.45h (AmplitudeVisualizerDrawable:16-24)", 0.45f, RecordingBarDesign.ICON_SIZE_FRACTION, epsilon)
        assertEquals("h-padding 0.35h", 0.35f, RecordingBarDesign.H_PADDING_FRACTION, epsilon)
    }

    @Test
    fun ageFade_runsFrom04LeftTo10Right() {
        assertEquals(0.4f, RecordingBarDesign.ageFadeAlpha(0), epsilon)
        assertEquals(1.0f, RecordingBarDesign.ageFadeAlpha(29), epsilon)
        // Mid-buffer: α = 0.4 + 0.6 * (i / 29)
        assertEquals(0.4f + 0.6f * (10f / 29f), RecordingBarDesign.ageFadeAlpha(10), epsilon)
    }

    @Test
    fun barHeight_mapsAmplitudeBetweenMinAndMax() {
        assertEquals(0.06f, RecordingBarDesign.barHeightFraction(0f), epsilon)
        assertEquals(0.55f, RecordingBarDesign.barHeightFraction(1f), epsilon)
        assertEquals(0.06f + 0.49f * 0.5f, RecordingBarDesign.barHeightFraction(0.5f), epsilon)
        assertEquals("clamped", 0.55f, RecordingBarDesign.barHeightFraction(7f), epsilon)
    }

    @Test
    fun pushLevel_shiftsLeft_newestRight_clamped() {
        val start = RecordingBarDesign.emptyLevels()
        assertEquals(30, start.size)

        val one = RecordingBarDesign.pushLevel(start, 0.7f)
        assertEquals(30, one.size)
        assertEquals(0.7f, one.last(), epsilon)

        val two = RecordingBarDesign.pushLevel(one, 5f) // out-of-range input
        assertEquals("coerced into 0..1 (AmplitudeVisualizerDrawable:96-103)", 1f, two.last(), epsilon)
        assertEquals("previous newest shifted one slot left", 0.7f, two[28], epsilon)
    }

    @Test
    fun capCornerRadius_isHalfTheBarWidth() {
        assertEquals(3.5f, RecordingBarDesign.capCornerRadius(7f), epsilon)
    }

    @Test
    fun pastel_keepsHue_scalesSaturation_maxesValue() {
        val pastel = RecordingBarDesign.pastel(floatArrayOf(210f, 0.8f, 0.5f))
        assertEquals(210f, pastel[0], epsilon)
        assertEquals(0.8f * 0.4f, pastel[1], epsilon)
        assertEquals(1f, pastel[2], epsilon)
    }

    @Test
    fun glowValue_addsGainPerLevel_andClamps() {
        assertEquals(0.5f + 0.35f * 0.6f, RecordingBarDesign.glowValue(0.5f, 0.6f), epsilon)
        assertEquals(1f, RecordingBarDesign.glowValue(0.9f, 1f), epsilon)
    }

    @Test
    fun darken_scalesRgbChannels_keepsAlpha() {
        val dimmed = RecordingBarDesign.darken(0xFF646464.toInt(), 0.18f)
        val expectedChannel = (0x64 * 0.82f).toInt()
        assertEquals(0xFF, dimmed ushr 24 and 0xFF)
        assertEquals(expectedChannel, dimmed ushr 16 and 0xFF)
        assertEquals(expectedChannel, dimmed ushr 8 and 0xFF)
        assertEquals(expectedChannel, dimmed and 0xFF)
    }

    @Test
    fun timerFormat_isMinutesColonSeconds() {
        assertEquals("00:00", RecordingBarDesign.formatTimer(0))
        assertEquals("00:09", RecordingBarDesign.formatTimer(9_400))
        assertEquals("01:05", RecordingBarDesign.formatTimer(65_000))
        assertEquals("59:59", RecordingBarDesign.formatTimer(3_599_999))
    }

    @Test
    fun rgbHsvRoundTrip_isStableForRepresentativeColors() {
        listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF6750A4.toInt(), 0xFF808080.toInt())
            .forEach { color ->
                val roundTripped = RecordingBarDesign.hsvToRgb(RecordingBarDesign.rgbToHsv(color))
                // ±1 per channel: float→byte quantization, same tolerance Android's own conversion has.
                intArrayOf(16, 8, 0).forEach { shift ->
                    val expected = color ushr shift and 0xFF
                    val actual = roundTripped ushr shift and 0xFF
                    assertEquals("channel@$shift of ${Integer.toHexString(color)}", expected.toFloat(), actual.toFloat(), 1f)
                }
            }
    }
}
