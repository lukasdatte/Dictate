package net.devemperor.dictate.keyboard

import net.devemperor.dictate.keyboard.VerticalDragResizeHandler.Companion.resolveHeight
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests for the delta→height math of [VerticalDragResizeHandler]. The
 * MotionEvent plumbing is a thin shell; the clamping + direction logic lives here.
 */
class VerticalDragResizeHandlerTest {

    private val min = 200
    private val max = 800

    @Test
    fun `bottom handle grows when dragging down`() {
        assertEquals(500, resolveHeight(400, +100f, growWhenDraggingDown = true, min, max))
    }

    @Test
    fun `bottom handle shrinks when dragging up`() {
        assertEquals(300, resolveHeight(400, -100f, growWhenDraggingDown = true, min, max))
    }

    @Test
    fun `top handle inverts direction (dragging down shrinks)`() {
        assertEquals(300, resolveHeight(400, +100f, growWhenDraggingDown = false, min, max))
        assertEquals(500, resolveHeight(400, -100f, growWhenDraggingDown = false, min, max))
    }

    @Test
    fun `clamps to max`() {
        assertEquals(max, resolveHeight(700, +1000f, growWhenDraggingDown = true, min, max))
    }

    @Test
    fun `clamps to min`() {
        assertEquals(min, resolveHeight(300, -1000f, growWhenDraggingDown = true, min, max))
    }

    @Test
    fun `zero delta keeps the start height`() {
        assertEquals(400, resolveHeight(400, 0f, growWhenDraggingDown = true, min, max))
    }
}
