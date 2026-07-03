package net.devemperor.dictate.state.render.overlay

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the [OverlayCardFill] policy — the single source of
 * truth for the overlay card's opaque, backdrop-independent fill
 * (2026-07-03 opacity-consistency fix).
 *
 * Pure colour maths; Robolectric only supplies the real
 * `android.graphics.Color` / `ColorUtils` implementations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayCardFillTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun `opacity 100 paints the plain surface colour`() {
        assertEquals(white, OverlayCardFill.effectiveFill(white, black, 100))
    }

    @Test
    fun `opacity 50 is the midpoint blend toward the base`() {
        // blendARGB(black, white, 0.5) — each channel 0 + 0.5*255 = 127.
        assertEquals(
            0xFF7F7F7F.toInt(),
            OverlayCardFill.effectiveFill(white, black, 50),
        )
    }

    @Test
    fun `result is always fully opaque - even for translucent inputs`() {
        // Backdrop independence is the whole point of the policy: no
        // alpha channel may survive, whatever the theme resolves to.
        val translucentSurface = 0x33FFFFFF
        val fill = OverlayCardFill.effectiveFill(translucentSurface, black, 60)
        assertEquals(255, Color.alpha(fill))
    }

    @Test
    fun `opacity is clamped to the settings range`() {
        // The SeekBar enforces 20..100, but the SP value is writable via
        // backup restore / adb — out-of-range input must not underflow
        // into an invisible card or overflow the blend ratio.
        assertEquals(
            OverlayCardFill.effectiveFill(white, black, OverlayCardFill.MIN_OPACITY_PERCENT),
            OverlayCardFill.effectiveFill(white, black, 0),
        )
        assertEquals(
            OverlayCardFill.effectiveFill(white, black, OverlayCardFill.MAX_OPACITY_PERCENT),
            OverlayCardFill.effectiveFill(white, black, 150),
        )
    }
}
