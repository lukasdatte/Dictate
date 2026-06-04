package net.devemperor.dictate.state.insertion

import android.view.inputmethod.InputConnection
import net.devemperor.dictate.core.FakeInputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the W1 slow-output failure mode: the pre-refactor
 * animation fire-and-forgot every tail `commitText` and silently dropped the
 * remainder when the editor lost focus mid-animation. The hardened
 * [SlowOutputAnimator] must stop on the first tail failure and report the
 * dropped remainder.
 */
class SlowOutputAnimatorTest {

    /** Runs scheduled actions immediately, in submission order (FIFO drain). */
    private class ImmediateScheduler : DelayedScheduler {
        private val queue = ArrayDeque<() -> Unit>()
        private var draining = false
        override fun postDelayed(delayMs: Long, action: () -> Unit) {
            queue.addLast(action)
            if (draining) return
            draining = true
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
            draining = false
        }
    }

    /** Records every commitText; can be told to fail starting at the Nth call. */
    private class RecordingIc(private val failFromCall: Int = Int.MAX_VALUE) : FakeInputConnection() {
        val committed = StringBuilder()
        var calls = 0
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            calls += 1
            if (calls >= failFromCall) return false
            committed.append(text)
            return true
        }
    }

    private fun animator(ic: InputConnection, onTail: (String) -> Unit) =
        SlowOutputAnimator(
            scheduler = ImmediateScheduler(),
            delayForIndex = { 0L },
            onTailFailure = TailFailureSink { onTail(it) },
        ).run(ic, "hello")

    @Test
    fun `all characters commit in order`() {
        val ic = RecordingIc()
        var tailDropped: String? = null
        val started = animator(ic) { tailDropped = it }

        assertTrue("first char must commit", started)
        assertEquals("hello", ic.committed.toString())
        assertEquals(5, ic.calls)
        assertEquals(null, tailDropped)
    }

    @Test
    fun `first char rejected — insert reports not started, no tail scheduled`() {
        val ic = RecordingIc(failFromCall = 1)
        var tailDropped: String? = null
        val started = animator(ic) { tailDropped = it }

        assertFalse("dead IC on first char must report not-started", started)
        assertEquals("", ic.committed.toString())
        assertEquals(1, ic.calls)
        // First-char failure is signalled via the return value, not onTailFailure.
        assertEquals(null, tailDropped)
    }

    @Test
    fun `tail char rejected mid-animation — aborts and reports remainder`() {
        // Fail starting at the 3rd commit: "h","e" land, "l" (index 2) fails.
        val ic = RecordingIc(failFromCall = 3)
        var tailDropped: String? = null
        val started = animator(ic) { tailDropped = it }

        assertTrue(started)
        assertEquals("he", ic.committed.toString())
        // W1 fix: the remaining "llo" is reported, NOT silently dropped, and
        // no further commits are attempted on the stale IC.
        assertEquals("llo", tailDropped)
        assertEquals(3, ic.calls) // h, e, failed-l — then stop
    }

    @Test
    fun `empty text commits empty once`() {
        val ic = RecordingIc()
        SlowOutputAnimator(ImmediateScheduler(), { 0L }).run(ic, "")
        assertEquals(1, ic.calls)
        assertEquals("", ic.committed.toString())
    }
}
