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
 * cleanest way to drive it deterministically is
 * `MotionEvent.obtain(...)` (needs the Android framework). The
 * [OverlayWindow] + [OverlayPositionMapper] are hand-rolled fakes
 * (K-1).
 *
 * # Coverage focus
 *
 * 1. **Tap (below threshold)** — `onTouch` returns `false` so the
 *    button-click propagates; no persist.
 * 2. **Drag (above threshold)** — `update` fires per move, `ACTION_UP`
 *    persists the normalised position and suppresses the click.
 * 3. **isDragging()** transitions across the gesture.
 * 4. **Mid-drag detach** — persists the final position before
 *    releasing the listener (R.18).
 * 5. **No-params guard** — a null `paramsHolder` short-circuits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayDragControllerTest {

    private lateinit var ctx: Context
    private lateinit var view: View
    private lateinit var window: FakeOverlayWindow
    private lateinit var mapper: RecordingPositionMapper
    private val persisted: MutableList<Pair<Float, Float>> = mutableListOf()
    private var params: WindowManager.LayoutParams? = WindowManager.LayoutParams()

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        view = View(ctx).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 200, 100)
        }
        window = FakeOverlayWindow()
        mapper = RecordingPositionMapper()
        persisted.clear()
        params = WindowManager.LayoutParams().apply { x = 0; y = 0 }
    }

    private fun newController(): OverlayDragController = OverlayDragController(
        ctx = ctx,
        view = view,
        window = window,
        paramsHolder = { params },
        positionMapper = mapper,
        onPositionPersist = { nx, ny -> persisted += nx to ny },
    )

    private fun event(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, x, y, 0)

    @Test
    fun `tap below threshold does not drag, does not persist`() {
        val c = newController()
        c.attach()
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 100f))
        // 2px move — well below the 8dp threshold.
        val moveConsumed = view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 102f, 101f))
        val upConsumed = view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 102f, 101f))

        assertFalse("Move below threshold must not be consumed.", moveConsumed)
        assertFalse("Tap UP must not be consumed (click passes through).", upConsumed)
        assertFalse(c.isDragging())
        assertTrue("No persist on a tap.", persisted.isEmpty())
        assertTrue("No window.update on a tap.", "update" !in window.events)
    }

    @Test
    fun `drag above threshold updates window and persists on UP`() {
        val c = newController()
        c.attach()
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 100f))
        // Big move — beyond any reasonable touch slop.
        val moveConsumed = view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 400f, 500f))
        assertTrue("Move beyond threshold must be consumed.", moveConsumed)
        assertTrue("isDragging() must be true mid-drag.", c.isDragging())
        assertTrue("window.update must fire per ACTION_MOVE.", "update" in window.events)
        assertEquals("params.x must be initial + dx.", 300, params!!.x)
        assertEquals("params.y must be initial + dy.", 400, params!!.y)

        val upConsumed = view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 400f, 500f))
        assertTrue("Drag UP must be consumed (suppresses click).", upConsumed)
        assertFalse("isDragging() must reset after UP.", c.isDragging())
        assertEquals(1, persisted.size)
        // Mapper records what it was asked to convert.
        assertEquals(300 to 400, mapper.lastPixelsIn)
    }

    @Test
    fun `mid-drag detach persists the final position (R 18)`() {
        val c = newController()
        c.attach()
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 100f))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 400f, 500f))
        assertTrue(c.isDragging())

        // No ACTION_UP — a mode-transition tears the overlay down.
        c.detach()

        assertEquals("detach must flush the in-flight drag.", 1, persisted.size)
        assertFalse(c.isDragging())
    }

    @Test
    fun `detach without an active drag does not persist`() {
        val c = newController()
        c.attach()
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 100f))
        // No move — still a potential tap.
        c.detach()
        assertTrue("Idle detach must not persist.", persisted.isEmpty())
    }

    @Test
    fun `null params short-circuits the listener`() {
        params = null
        val c = newController()
        c.attach()
        val consumed = view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 100f))
        assertFalse("Null params → listener returns false.", consumed)
    }

    /**
     * Recording [OverlayPositionMapper] — echoes a fixed normalised
     * pair and records the pixel input so the test can assert what
     * the controller asked to convert.
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
