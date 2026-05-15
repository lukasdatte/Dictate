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
        // view == screen → free area 0; mapper must still return a value.
        val full = measuredView(w = 1000, h = 2000)
        val (px, py) = mapper.normalizedToPixels(1f, 1f, full)!!
        assertEquals(0, px) // maxX coerced to 0
        assertEquals(0, py)
        // Inverse: denominator coerced to 1, result clamped to [0,1].
        val (nx, ny) = mapper.pixelsToNormalized(0, 0, full)!!
        assertEquals(0f, nx)
        assertEquals(0f, ny)
    }
}
