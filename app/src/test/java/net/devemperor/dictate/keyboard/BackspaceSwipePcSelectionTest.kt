package net.devemperor.dictate.keyboard

import net.devemperor.dictate.state.insertion.ControlOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PC-mode backspace-swipe word selection (§4.5, D1, Akzeptanzkriterium 10): step sequence →
 * command sequence, direction change, release and cancel. Pure — no MotionEvent, no InputConnection.
 */
class BackspaceSwipePcSelectionTest {

    private val emitted = mutableListOf<ControlOp>()
    private val selection = BackspaceSwipePcSelection { emitted += it }

    private fun back(n: Int) = List(n) { ControlOp.SelectWord(-1) }
    private fun forward(n: Int) = List(n) { ControlOp.SelectWord(1) }

    @Test
    fun growingTheSwipe_selectsOneWordBackPerStep() {
        selection.toStep(1)
        selection.toStep(2)
        selection.toStep(3)

        assertEquals(back(3), emitted)
        assertEquals(3, selection.steps)
    }

    @Test
    fun aJumpOfSeveralSteps_emitsOnePerWord() {
        selection.toStep(4)

        assertEquals(back(4), emitted)
    }

    @Test
    fun recedingTheSwipe_reducesTheSelectionForward() {
        selection.toStep(3)
        emitted.clear()

        selection.toStep(1) // reduce by two

        assertEquals(forward(2), emitted)
        assertEquals(1, selection.steps)
    }

    @Test
    fun aDirectionChange_backThenForward_preservesOrder() {
        selection.toStep(2) // back, back
        selection.toStep(1) // forward
        selection.toStep(2) // back

        assertEquals(back(2) + forward(1) + back(1), emitted)
    }

    @Test
    fun release_withASelection_deletesIt_andReportsTrue() {
        selection.toStep(2)
        emitted.clear()

        val deleted = selection.release()

        assertTrue(deleted)
        assertEquals(listOf<ControlOp>(ControlOp.DeleteSelection), emitted)
    }

    @Test
    fun release_withoutASelection_isANoOp() {
        val deleted = selection.release()

        assertFalse(deleted)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun cancel_withASelection_collapsesToTheRight() {
        selection.toStep(2)
        emitted.clear()

        selection.cancel()

        assertEquals(listOf<ControlOp>(ControlOp.CursorMove(1)), emitted)
    }

    @Test
    fun cancel_withoutASelection_isANoOp() {
        selection.cancel()
        assertTrue(emitted.isEmpty())
    }
}
