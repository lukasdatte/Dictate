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
        /** Each individual commitText payload — for grapheme-chunk assertions (F-020). */
        val payloads = mutableListOf<String>()
        var calls = 0
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            calls += 1
            if (calls >= failFromCall) return false
            committed.append(text)
            payloads += text.toString()
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

    @Test
    fun `empty text propagates an IC rejection`() {
        val ic = RecordingIc(failFromCall = 1)
        val started = SlowOutputAnimator(ImmediateScheduler(), { 0L }).run(ic, "")
        assertFalse("empty-text commit must report the IC rejection", started)
    }

    @Test
    fun `single character text commits the first char with no tail`() {
        val ic = RecordingIc()
        var tailDropped: String? = null
        val started = SlowOutputAnimator(
            ImmediateScheduler(), { 0L }, TailFailureSink { tailDropped = it },
        ).run(ic, "x")

        assertTrue(started)
        assertEquals("x", ic.committed.toString())
        assertEquals(1, ic.calls)
        assertEquals(null, tailDropped)
    }

    @Test
    fun `delayForIndex is queried with ascending tail indices`() {
        val ic = RecordingIc()
        val indices = mutableListOf<Int>()
        SlowOutputAnimator(
            ImmediateScheduler(),
            DelayProvider { index -> indices.add(index); 0L },
        ).run(ic, "hello")

        // First char is synchronous (no delay query); tail chars query 1..4.
        assertEquals(listOf(1, 2, 3, 4), indices)
    }

    // ── F-020 — grapheme-cluster chunking (no lone surrogates) ─────────

    @Test
    fun `surrogate-pair emoji is committed as one whole payload`() {
        // Regression for F-020: the pre-fix animator indexed text[i] and
        // committed the high and low surrogate as two separate commitText
        // calls — an invalid intermediate state that hosts sanitising per
        // commit (Chromium/WebView) turn into two permanent U+FFFD chars.
        val ic = RecordingIc()
        SlowOutputAnimator(ImmediateScheduler(), { 0L }).run(ic, "a😀b")

        assertEquals(listOf("a", "😀", "b"), ic.payloads)
        assertEquals("a😀b", ic.committed.toString())
    }

    @Test
    fun `ZWJ family emoji is committed as a single payload`() {
        val ic = RecordingIc()
        SlowOutputAnimator(ImmediateScheduler(), { 0L }).run(ic, "hi👨‍👩‍👧")

        assertEquals(listOf("h", "i", "👨‍👩‍👧"), ic.payloads)
    }

    @Test
    fun `no payload ever starts or ends on a lone surrogate`() {
        val ic = RecordingIc()
        SlowOutputAnimator(ImmediateScheduler(), { 0L })
            .run(ic, "x👍🏽 y🇩🇪 z😀")

        for (payload in ic.payloads) {
            assertFalse(
                "payload '$payload' starts with a lone low surrogate",
                payload.isNotEmpty() && payload.first().isLowSurrogate(),
            )
            assertFalse(
                "payload '$payload' ends with a lone high surrogate",
                payload.isNotEmpty() && payload.last().isHighSurrogate(),
            )
        }
    }

    @Test
    fun `tail failure after an emoji reports the whole remaining clusters`() {
        // "a","😀" land (calls 1-2), "b" (call 3) fails → remainder "bc".
        val ic = RecordingIc(failFromCall = 3)
        var tailDropped: String? = null
        SlowOutputAnimator(
            ImmediateScheduler(), { 0L }, TailFailureSink { tailDropped = it },
        ).run(ic, "a😀bc")

        assertEquals("a😀", ic.committed.toString())
        assertEquals("bc", tailDropped)
    }
}
