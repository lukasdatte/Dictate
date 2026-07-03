package net.devemperor.dictate.state.render.overlay

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the [OverlayCardFill] policy — the single source of
 * truth for the overlay card's translucent fill (2026-07-03 contract:
 * real alpha channel, identical ARGB applied in every mode).
 *
 * Pure colour maths; Robolectric only supplies the real
 * `android.graphics.Color` / `ColorUtils` implementations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayCardFillTest {

    private val surface = 0xFFAABBCC.toInt()

    @Test
    fun `opacity 100 paints the plain opaque surface colour`() {
        assertEquals(surface, OverlayCardFill.effectiveFill(surface, 100))
    }

    @Test
    fun `alpha follows the slider - RGB channels stay untouched`() {
        // The "gar keine Opacity mehr" regression guard: below 100 %
        // the fill must carry a REAL alpha channel (opacity * 255 / 100)
        // so host content genuinely shines through.
        listOf(20 to 51, 40 to 102, 55 to 140).forEach { (opacity, expectedAlpha) ->
            val fill = OverlayCardFill.effectiveFill(surface, opacity)
            assertEquals("alpha for opacity=$opacity", expectedAlpha, Color.alpha(fill))
            assertTrue(
                "fill must be genuinely translucent at opacity=$opacity",
                Color.alpha(fill) < 255,
            )
            assertEquals("red channel untouched", 0xAA, Color.red(fill))
            assertEquals("green channel untouched", 0xBB, Color.green(fill))
            assertEquals("blue channel untouched", 0xCC, Color.blue(fill))
        }
    }

    @Test
    fun `opacity is clamped to the settings range`() {
        // The SeekBar enforces 20..100, but the SP value is writable via
        // backup restore / adb — out-of-range input must not underflow
        // into an invisible card or overflow the alpha byte.
        assertEquals(
            OverlayCardFill.effectiveFill(surface, OverlayCardFill.MIN_OPACITY_PERCENT),
            OverlayCardFill.effectiveFill(surface, 0),
        )
        assertEquals(
            OverlayCardFill.effectiveFill(surface, OverlayCardFill.MAX_OPACITY_PERCENT),
            OverlayCardFill.effectiveFill(surface, 150),
        )
    }
}
