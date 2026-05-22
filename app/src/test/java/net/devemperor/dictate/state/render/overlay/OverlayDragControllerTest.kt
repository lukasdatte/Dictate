package net.devemperor.dictate.state.render.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [OverlayDragController] (Spec 3 §4.6 + §11.5).
 *
 * # Why Robolectric
 *
 * The controller's state machine consumes real [MotionEvent]s — the
 * cleanest way to drive it deterministically is `MotionEvent.obtain(...)`
 * (needs the Android framework). The [OverlayWindow] +
 * [OverlayPositionMapper] are hand-rolled fakes (K-1).
 *
 * # Drive model
 *
 * The controller is now driven by [DraggableOverlayLayout]'s
 * `onInterceptTouchEvent` / `onTouchEvent` overrides — the tests call
 * [OverlayDragController.onInterceptTouchEvent] /
 * [OverlayDragController.onTouchEvent] directly, mirroring the two real
 * dispatch paths:
 *  - **button path** — `onInterceptTouchEvent` sees every event until it
 *    steals the gesture past the threshold, then `onTouchEvent` drives it.
 *  - **empty-region path** — the `ACTION_DOWN` lands on padding, so
 *    `onTouchEvent` handles the whole gesture directly.
 *
 * # Coverage focus
 *
 * 1. **Tap (below threshold)** — `onInterceptTouchEvent` never steals;
 *    no persist, no `update`.
 * 2. **The steal** — a move past the threshold makes
 *    `onInterceptTouchEvent` return `true` (the regression guard for
 *    "buttons swallow the drag").
 * 3. **Drag after the steal** — `onTouchEvent` issues `update` per move
 *    and persists on `ACTION_UP`.
 * 4. **Empty-region drag** — handled entirely via `onTouchEvent`.
 * 5. **F-7** — orientation snapshotted at `ACTION_DOWN`, not at persist.
 * 6. **Mid-drag detach** — persists the final position (R.18).
 * 7. **No-params guard** — a null `paramsHolder` short-circuits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayDragControllerTest {

    private lateinit var ctx: Context
    private lateinit var view: View
    private lateinit var window: FakeOverlayWindow
    private lateinit var mapper: RecordingPositionMapper

    // F-7: persist carries the orientation snapshot captured at
    // ACTION_DOWN. Triple is (portrait, normX, normY).
    private val persisted: MutableList<Triple<Boolean, Float, Float>> = mutableListOf()
    private var params: WindowManager.LayoutParams? = WindowManager.LayoutParams()
    private var portraitNow: Boolean = true

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        view = View(ctx)
        window = FakeOverlayWindow()
        mapper = RecordingPositionMapper()
        persisted.clear()
        portraitNow = true
        params = WindowManager.LayoutParams().apply { x = 0; y = 0 }
    }

    private fun newController(): OverlayDragController = OverlayDragController(
        ctx = ctx,
        view = view,
        window = window,
        paramsHolder = { params },
        positionMapper = mapper,
        orientationProvider = { portraitNow },
        onPositionPersist = { portrait, nx, ny -> persisted += Triple(portrait, nx, ny) },
    )

    private fun event(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, x, y, 0)

    // ── 1. Tap below threshold — never stolen, never persisted ──────────

    @Test
    fun `tap below threshold is not stolen and does not persist`() {
        val c = newController()
        assertFalse(
            "ACTION_DOWN must not be intercepted — buttons keep it.",
            c.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 100f)),
        )
        // 2-3px move — well below the 8dp threshold.
        assertFalse(
            "A move below the threshold must not be intercepted.",
            c.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE, 102f, 101f)),
        )
        assertFalse(
            "ACTION_UP of a tap must not be intercepted.",
            c.onInterceptTouchEvent(event(MotionEvent.ACTION_UP, 102f, 101f)),
        )
        assertFalse(c.isDragging())
        assertTrue("No persist on a tap.", persisted.isEmpty())
        assertTrue("No window.update on a tap.", "update" !in window.events)
    }

    // ── 2. The steal — regression guard for "buttons swallow the drag" ──

    @Test
    fun `move past the threshold makes onInterceptTouchEvent steal the gesture`() {
        val c = newController()
        c.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 100f))
        assertFalse(
            "A move still within slop must not steal yet.",
            c.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE, 103f, 102f)),
        )
        assertTrue(
            "A move beyond the threshold MUST be intercepted — this is what " +
                "re-routes a gesture a button claimed on ACTION_DOWN.",
            c.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE, 400f, 500f)),
        )
        assertTrue("isDragging() must be true once the gesture is stolen.", c.isDragging())
    }

    // ── 3. Drag after the steal — onTouchEvent drives the window ────────

    @Test
    fun `after the steal onTouchEvent moves the window and persists on UP`() {
        val c = newController()
        c.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 100f))
        c.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE, 400f, 500f)) // steal

        assertTrue(
            "Post-steal moves arrive at onTouchEvent and are consumed.",
            c.onTouchEvent(event(MotionEvent.ACTION_MOVE, 400f, 500f)),
        )
        assertTrue("window.update must fire per ACTION_MOVE.", "update" in window.events)
        assertEquals("params.x must be initial + dx.", 300, params!!.x)
        assertEquals("params.y must be initial + dy.", 400, params!!.y)

        assertTrue(
            "Drag UP must be consumed (suppresses the click).",
            c.onTouchEvent(event(MotionEvent.ACTION_UP, 400f, 500f)),
        )
        assertFalse("isDragging() must reset after UP.", c.isDragging())
        assertEquals(1, persisted.size)
        assertEquals(300 to 400, mapper.lastPixelsIn)
    }

    // ── 4. Empty-region drag — handled entirely via onTouchEvent ────────

    @Test
    fun `drag started on an empty region is handled via onTouchEvent`() {
        val c = newController()
        // ACTION_DOWN on padding → no child claims it → reaches onTouchEvent.
        assertTrue(
            "onTouchEvent must claim an ACTION_DOWN on an empty region.",
            c.onTouchEvent(event(MotionEvent.ACTION_DOWN, 5f, 5f)),
        )
        assertTrue(
            "The move past the threshold drags.",
            c.onTouchEvent(event(MotionEvent.ACTION_MOVE, 305f, 405f)),
        )
        assertTrue(c.isDragging())
        assertEquals(300, params!!.x)
        assertEquals(400, params!!.y)

        c.onTouchEvent(event(MotionEvent.ACTION_UP, 305f, 405f))
        assertEquals(1, persisted.size)
    }

    // ── 5. F-7 — orientation snapshotted at ACTION_DOWN ─────────────────

    @Test
    fun `F-7 orientation is snapshotted at ACTION_DOWN, not at persist`() {
        portraitNow = true
        val c = newController()
        c.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 100f))
        c.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE, 400f, 500f)) // steal
        // Orientation flips between the steal and UP (config-change race).
        portraitNow = false
        c.onTouchEvent(event(MotionEvent.ACTION_UP, 400f, 500f))

        assertEquals(1, persisted.size)
        assertTrue(
            "Persisted orientation must be the ACTION_DOWN snapshot (portrait), " +
                "not the flipped value.",
            persisted[0].first,
        )
    }

    // ── 6. Mid-drag detach — persists the final position (R.18) ─────────

    @Test
    fun `mid-drag detach persists the final position (R 18)`() {
        val c = newController()
        c.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 100f))
        c.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE, 400f, 500f)) // steal → dragging
        assertTrue(c.isDragging())

        // No ACTION_UP — a mode-transition tears the overlay down.
        c.detach()

        assertEquals("detach must flush the in-flight drag.", 1, persisted.size)
        assertFalse(c.isDragging())
    }

    @Test
    fun `detach without an active drag does not persist`() {
        val c = newController()
        c.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 100f))
        // No move — still a potential tap.
        c.detach()
        assertTrue("Idle detach must not persist.", persisted.isEmpty())
    }

    // ── 7. No-params guard ──────────────────────────────────────────────

    @Test
    fun `null params short-circuits both touch hooks`() {
        params = null
        val c = newController()
        assertFalse(
            "Null params → onInterceptTouchEvent returns false.",
            c.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 100f)),
        )
        assertFalse(
            "Null params → onTouchEvent returns false.",
            c.onTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 100f)),
        )
    }

    /**
     * Recording [OverlayPositionMapper] — echoes a fixed normalised pair
     * and records the pixel input so the test can assert what the
     * controller asked to convert.
     */
    private class RecordingPositionMapper : OverlayPositionMapper {
        var lastPixelsIn: Pair<Int, Int>? = null
            private set

        override fun normalizedToPixels(normX: Float, normY: Float, view: View): Pair<Int, Int> =
            0 to 0

        override fun pixelsToNormalized(px: Int, py: Int, view: View): Pair<Float, Float> {
            lastPixelsIn = px to py
            return 0.42f to 0.84f
        }
    }
}
