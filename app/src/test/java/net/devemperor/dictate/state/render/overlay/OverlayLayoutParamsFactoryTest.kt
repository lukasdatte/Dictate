package net.devemperor.dictate.state.render.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [DefaultOverlayLayoutParamsFactory].
 *
 * # Why Robolectric
 *
 * `WindowManager.LayoutParams` is a system class — instantiating one
 * needs the Android framework on the classpath. Robolectric is the
 * narrower workaround (K-4 exception, same as `ImeViewBackendTest`).
 *
 * # Coverage focus
 *
 * Each flag in the Spec 3 §4.4 truth-table gets one positive assertion
 * (flag IS set) or one negative assertion (flag is NOT set). Window
 * type, format, gravity and animation init values are also asserted.
 *
 * @see DefaultOverlayLayoutParamsFactory
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md §4.3 + §4.4 + §4.5
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayLayoutParamsFactoryTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val factory = DefaultOverlayLayoutParamsFactory(ctx)

    @Test
    fun `window type is TYPE_APPLICATION_OVERLAY on API 26+`() {
        // Robolectric default API >= 26.
        assertTrue("Test runs on API >= 26", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val params = factory.create()
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
    }

    @Test
    fun `flag FLAG_NOT_FOCUSABLE is set`() {
        val params = factory.create()
        assertFlagSet(params.flags, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    @Test
    fun `flag FLAG_NOT_TOUCH_MODAL is set`() {
        val params = factory.create()
        assertFlagSet(params.flags, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    }

    @Test
    fun `flag FLAG_LAYOUT_IN_SCREEN is set`() {
        val params = factory.create()
        assertFlagSet(params.flags, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
    }

    @Test
    fun `flag FLAG_HARDWARE_ACCELERATED is set`() {
        val params = factory.create()
        assertFlagSet(params.flags, WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
    }

    @Test
    fun `flag FLAG_KEEP_SCREEN_ON is NOT set (FGS holds the wake-lock)`() {
        val params = factory.create()
        assertFlagNotSet(params.flags, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Test
    @Suppress("DEPRECATION")  // FLAG_SHOW_WHEN_LOCKED itself is deprecated in API 27+; we still assert it is NOT set on us.
    fun `flag FLAG_SHOW_WHEN_LOCKED is NOT set`() {
        val params = factory.create()
        assertFlagNotSet(params.flags, WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
    }

    @Test
    fun `flag FLAG_LAYOUT_NO_LIMITS is NOT set`() {
        val params = factory.create()
        assertFlagNotSet(params.flags, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    @Test
    fun `flag FLAG_DIM_BEHIND is NOT set`() {
        val params = factory.create()
        assertFlagNotSet(params.flags, WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    @Test
    fun `format is TRANSLUCENT to honour rounded-corner alpha`() {
        val params = factory.create()
        assertEquals(PixelFormat.TRANSLUCENT, params.format)
    }

    @Test
    fun `gravity is TOP-or-START — drag math computes from top-left`() {
        val params = factory.create()
        assertEquals(Gravity.TOP or Gravity.START, params.gravity)
    }

    @Test
    fun `width is WRAP_CONTENT`() {
        val params = factory.create()
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.width)
    }

    @Test
    fun `height is WRAP_CONTENT`() {
        val params = factory.create()
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.height)
    }

    @Test
    fun `initial x and y are zero — position is applied post-attach`() {
        val params = factory.create()
        assertEquals(0, params.x)
        assertEquals(0, params.y)
    }

    @Test
    fun `windowAnimations is zero — no slide-in animation`() {
        val params = factory.create()
        assertEquals(0, params.windowAnimations)
    }

    @Test
    fun `each create() returns a fresh instance — caller may mutate`() {
        val first = factory.create()
        val second = factory.create()
        assertNotSame("Factory must hand out a new instance per call.", first, second)

        // Mutating `first` does NOT leak into `second`.
        first.x = 500
        assertNotEquals(first.x, second.x)
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private fun assertFlagSet(flags: Int, flag: Int) {
        assertTrue(
            "Expected flag $flag set in $flags (binary ${Integer.toBinaryString(flags)})",
            (flags and flag) == flag,
        )
    }

    private fun assertFlagNotSet(flags: Int, flag: Int) {
        assertEquals(
            "Expected flag $flag NOT set in $flags (binary ${Integer.toBinaryString(flags)})",
            0, flags and flag,
        )
    }
}
