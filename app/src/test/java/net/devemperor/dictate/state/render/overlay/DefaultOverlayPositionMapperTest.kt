package net.devemperor.dictate.state.render.overlay

import android.content.Context
import android.util.DisplayMetrics
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [DefaultOverlayPositionMapper] (Spec 3 §4.7 +
 * §11.5.4 + §11.5.5).
 *
 * # Why Robolectric
 *
 * The mapper reads `ctx.resources.displayMetrics`; the test pins the
 * screen size via Robolectric's `ShadowDisplayMetrics` so the
 * normalisation math is deterministic. A plain JVM test can't supply
 * a `View` with a measured `width`.
 *
 * # Coverage focus
 *
 * 1. Round-trip identity (normalised → pixels → normalised) at the
 *    corners + centre.
 * 2. Clamp behaviour (out-of-range normalised in, pixels beyond the
 *    free area in).
 * 3. View-not-measured short-circuit (`null` both directions).
 * 4. Zero-free-area edge (view == screen).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultOverlayPositionMapperTest {

    private lateinit var ctx: Context
    private lateinit var mapper: DefaultOverlayPositionMapper

    /** A 200×100 measured view on a 1000×2000 screen → free area 800×1900. */
    private fun measuredView(w: Int = 200, h: Int = 100): View {
        val v = View(ctx)
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, w, h)
        return v
    }

    private fun unmeasuredView(): View = View(ctx)

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        val dm: DisplayMetrics = ctx.resources.displayMetrics
        // Robolectric's DisplayMetrics is a plain mutable object — set
        // the screen size directly so the normalisation math is
        // deterministic regardless of the emulated device default.
        dm.widthPixels = 1000
        dm.heightPixels = 2000
        mapper = DefaultOverlayPositionMapper(ctx)
    }

    @Test
    fun `normalizedToPixels maps 0,0 to screen origin`() {
        val (px, py) = mapper.normalizedToPixels(0f, 0f, measuredView())!!
        assertEquals(0, px)
        assertEquals(0, py)
    }

    @Test
    fun `normalizedToPixels maps 1,1 to the free-area maximum`() {
        // free area = (1000-200, 2000-100) = (800, 1900)
        val (px, py) = mapper.normalizedToPixels(1f, 1f, measuredView())!!
        assertEquals(800, px)
        assertEquals(1900, py)
    }

    @Test
    fun `normalizedToPixels maps centre`() {
        val (px, py) = mapper.normalizedToPixels(0.5f, 0.5f, measuredView())!!
        assertEquals(400, px)
        assertEquals(950, py)
    }

    @Test
    fun `normalizedToPixels clamps out-of-range input into 0,1`() {
        val (px, py) = mapper.normalizedToPixels(2.0f, -1.0f, measuredView())!!
        assertEquals(800, px) // clamped to 1.0 → maxX
        assertEquals(0, py) // clamped to 0.0
    }

    @Test
    fun `pixelsToNormalized is the inverse at the corners`() {
        val v = measuredView()
        assertEquals(0f to 0f, mapper.pixelsToNormalized(0, 0, v))
        assertEquals(1f to 1f, mapper.pixelsToNormalized(800, 1900, v))
    }

    @Test
    fun `pixelsToNormalized clamps pixels beyond the free area`() {
        val v = measuredView()
        // System clamped the params off-screen — normalised must stay 1.0.
        assertEquals(1f to 1f, mapper.pixelsToNormalized(99999, 99999, v))
        assertEquals(0f to 0f, mapper.pixelsToNormalized(-50, -50, v))
    }

    @Test
    fun `round-trip identity at an arbitrary interior point`() {
        val v = measuredView()
        val (px, py) = mapper.normalizedToPixels(0.3f, 0.7f, v)!!
        val (nx, ny) = mapper.pixelsToNormalized(px, py, v)!!
        // Allow integer-truncation rounding error of one pixel / free-area.
        assertEquals(0.3f, nx, 1f / 800f)
        assertEquals(0.7f, ny, 1f / 1900f)
    }

    @Test
    fun `unmeasured view yields null both directions`() {
        val v = unmeasuredView()
        assertNull(mapper.normalizedToPixels(0.5f, 0.5f, v))
        assertNull(mapper.pixelsToNormalized(10, 10, v))
    }

    @Test
    fun `zero-free-area view does not divide by zero`() {
        // view == screen → free area 0; mapper must still return a
        // value. F-6 (B5): both directions now share the SAME zero-
        // guard (`freeArea = (screen-view).coerceAtLeast(1)`), so the
        // mapper never divides by zero AND the round-trip is identity
        // at this degenerate boundary.
        val full = measuredView(w = 1000, h = 2000)
        val (px, py) = mapper.normalizedToPixels(1f, 1f, full)!!
        // F-6: symmetric denominator → 1.0 maps to px=1 (was 0 under
        // the old asymmetric `coerceAtLeast(0)` floor).
        assertEquals(1, px)
        assertEquals(1, py)
        // Inverse with the SAME denominator: px=1 → 1.0 (round-trip
        // identity; previously px=0 → 0.0 silently rewrote a right-edge
        // anchor to the left edge — the F-6 bug).
        val (nx, ny) = mapper.pixelsToNormalized(px, py, full)!!
        assertEquals(1f, nx)
        assertEquals(1f, ny)
    }

    @Test
    fun `F-6 round-trip identity at the zero-free-area right-edge anchor`() {
        // Regression guard for F-6: the OverlayState default anchor is
        // 1.0f (right edge). A drag ending with a screen-filling view
        // must NOT silently rewrite that anchor to 0.0 (left edge).
        val full = measuredView(w = 1000, h = 2000)
        val (px, py) = mapper.normalizedToPixels(1.0f, 1.0f, full)!!
        val (nx, ny) = mapper.pixelsToNormalized(px, py, full)!!
        assertEquals("right-edge anchor must survive the round-trip", 1.0f, nx)
        assertEquals(1.0f, ny)
    }
}
