package net.devemperor.dictate.state.render.overlay

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [AndroidOverlayWindow] — the production wrapper around
 * `WindowManager`.
 *
 * Each test substitutes a hand-rolled [RecordingWindowManager] that
 * records every `addView` / `updateViewLayout` / `removeView` call.
 * Robolectric is needed only because [View] and
 * [WindowManager.LayoutParams] are framework types.
 *
 * # Coverage focus
 *
 * - Basic add/update/remove forwarding.
 * - `attached` idempotency: double-attach is a no-op; detach without
 *   attach is a no-op.
 * - BadTokenException catch leaves wrapper in detached state.
 * - IllegalArgumentException on `updateViewLayout` flips to detached.
 * - IllegalArgumentException on `removeView` is swallowed.
 *
 * @see AndroidOverlayWindow
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidOverlayWindowTest {

    private lateinit var ctx: Context
    private lateinit var view: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var wm: RecordingWindowManager
    private lateinit var wrapper: AndroidOverlayWindow

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        view = View(ctx)
        params = WindowManager.LayoutParams()
        wm = RecordingWindowManager()
        wrapper = AndroidOverlayWindow(wm)
    }

    @Test
    fun `initial state is detached`() {
        assertFalse(wrapper.isAttached())
    }

    @Test
    fun `attach calls addView and flips attached`() {
        wrapper.attach(view, params)
        assertTrue(wrapper.isAttached())
        assertEquals(1, wm.addCalls.size)
        assertSame(view, wm.addCalls[0].first)
        assertSame(params, wm.addCalls[0].second)
    }

    @Test
    fun `double attach is a no-op (idempotent)`() {
        wrapper.attach(view, params)
        wrapper.attach(view, params)
        assertTrue(wrapper.isAttached())
        assertEquals("addView must run exactly once.", 1, wm.addCalls.size)
    }

    @Test
    fun `update forwards to updateViewLayout when attached`() {
        wrapper.attach(view, params)
        val newParams = WindowManager.LayoutParams().also { it.x = 100 }
        wrapper.update(view, newParams)
        assertEquals(1, wm.updateCalls.size)
        assertSame(newParams, wm.updateCalls[0].second)
    }

    @Test
    fun `update before attach is a no-op`() {
        wrapper.update(view, params)
        assertEquals(0, wm.updateCalls.size)
    }

    @Test
    fun `detach calls removeView and flips attached`() {
        wrapper.attach(view, params)
        wrapper.detach(view)
        assertFalse(wrapper.isAttached())
        assertEquals(1, wm.removeCalls.size)
        assertSame(view, wm.removeCalls[0])
    }

    @Test
    fun `detach before attach is a no-op`() {
        wrapper.detach(view)
        assertEquals(0, wm.removeCalls.size)
        assertFalse(wrapper.isAttached())
    }

    @Test
    fun `attach BadTokenException leaves attached=false (permission revoked)`() {
        wm.throwOnAdd = WindowManager.BadTokenException("simulated permission-revoke")
        wrapper.attach(view, params)
        assertFalse(
            "BadToken must leave the wrapper detached so the backend re-routes via permission gate.",
            wrapper.isAttached(),
        )
    }

    @Test
    fun `update IllegalArgumentException flips attached to false`() {
        wrapper.attach(view, params)
        wm.throwOnUpdate = IllegalArgumentException("view not attached")
        wrapper.update(view, params)
        assertFalse(
            "OS-side detach must flip wrapper.attached to false so the next render re-attaches cleanly.",
            wrapper.isAttached(),
        )
    }

    @Test
    fun `detach IllegalArgumentException is swallowed`() {
        wrapper.attach(view, params)
        wm.throwOnRemove = IllegalArgumentException("view not attached")
        // Must not throw.
        wrapper.detach(view)
        assertFalse(wrapper.isAttached())
    }

    @Test
    fun `attach after permission-revoke recovers (subsequent attach succeeds)`() {
        // First attempt: BadToken (permission revoked).
        wm.throwOnAdd = WindowManager.BadTokenException("first call")
        wrapper.attach(view, params)
        assertFalse(wrapper.isAttached())

        // Permission re-granted, retry succeeds.
        wm.throwOnAdd = null
        wrapper.attach(view, params)
        assertTrue("Subsequent attach must work once permission is back.", wrapper.isAttached())
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    /**
     * Hand-rolled [WindowManager] that records every call and lets
     * tests inject runtime exceptions.
     */
    private class RecordingWindowManager : WindowManager {
        val addCalls: MutableList<Pair<View, WindowManager.LayoutParams>> = mutableListOf()
        val updateCalls: MutableList<Pair<View, WindowManager.LayoutParams>> = mutableListOf()
        val removeCalls: MutableList<View> = mutableListOf()

        var throwOnAdd: Throwable? = null
        var throwOnUpdate: Throwable? = null
        var throwOnRemove: Throwable? = null

        override fun addView(view: View?, params: android.view.ViewGroup.LayoutParams?) {
            throwOnAdd?.let { throw it }
            addCalls += (view!! to (params as WindowManager.LayoutParams))
        }

        override fun updateViewLayout(view: View?, params: android.view.ViewGroup.LayoutParams?) {
            throwOnUpdate?.let { throw it }
            updateCalls += (view!! to (params as WindowManager.LayoutParams))
        }

        override fun removeView(view: View?) {
            throwOnRemove?.let { throw it }
            removeCalls += view!!
        }

        // Unused for these tests — return harmless defaults.
        @Deprecated("part of the legacy WindowManager surface", level = DeprecationLevel.WARNING)
        @Suppress("DEPRECATION")
        override fun getDefaultDisplay(): android.view.Display? = null

        override fun removeViewImmediate(view: View?) {
            // Not exercised by AndroidOverlayWindow.
        }

        override fun getCurrentWindowMetrics(): android.view.WindowMetrics =
            error("not used in AndroidOverlayWindowTest")

        override fun getMaximumWindowMetrics(): android.view.WindowMetrics =
            error("not used in AndroidOverlayWindowTest")
    }
}
