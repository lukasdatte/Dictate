package net.devemperor.dictate.core

import net.devemperor.dictate.database.entity.InsertionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for the **headless terminal fallback** added to
 * [PipelineCallbackBridge] (ADR-0011).
 *
 * These pin the guarded-terminal semantics that make exactly ONE
 * `PipelineDone` / `PipelineFailed` fire per session regardless of
 * whether an IME delegate is bound:
 *
 *  - no delegate → the headless sink fires (previously the callback was
 *    dropped, freezing `state.pipeline` in Running forever);
 *  - delegate present → the delegate is delivered, the sink stays silent,
 *    the guard is consumed so a later reconciliation cannot double-fire;
 *  - already-consumed guard → delivery is skipped defensively;
 *  - unresolvable sessionId → legacy drop, guard untouched.
 *
 * The `committed = false` semantics live in the service-side wiring lambda
 * (text commit stays IME-exclusive) — the completion sink here IS that
 * headless path, so "completion sink fired" == "PipelineDone(committed=false)".
 */
class PipelineCallbackBridgeHeadlessTest {

    private class RecordingDelegate : PipelineOrchestrator.PipelineCallback {
        val completed = mutableListOf<Pair<String, InsertionSource>>()
        val errors = mutableListOf<Triple<String, Boolean, String?>>()

        override fun onStepStarted(stepName: String) = Unit
        override fun onStepCompleted(stepName: String, durationMs: Long) = Unit
        override fun onStepFailed(stepName: String) = Unit
        override fun onPipelineCompleted(text: String, source: InsertionSource, review: net.devemperor.dictate.ai.conversation.PostProcessingReview?) {
            completed += text to source
        }
        override fun onPipelineError(errorInfoKey: String, vibrate: Boolean, providerName: String?) {
            errors += Triple(errorInfoKey, vibrate, providerName)
        }
        override fun onPipelineFinished() = Unit
        override fun onShowResend() = Unit
        override fun onAutoSwitch() = Unit
        override fun onAudioPersisted(audioFile: File, sessionId: String) = Unit
    }

    /** Captures the headless completion + failure sink invocations. */
    private class SinkRecorder {
        val completions = mutableListOf<Pair<String, String>>()
        val failures = mutableListOf<Pair<String, String>>()
    }

    private fun bridgeWith(
        guard: PipelineTerminalDispatchGuard,
        sink: SinkRecorder,
        sessionId: String?,
    ): PipelineCallbackBridge {
        val bridge = PipelineCallbackBridge(guard)
        bridge.setHeadlessTerminalSink(
            currentSessionIdProvider = { sessionId },
            onCompleted = { sid, text -> sink.completions += sid to text },
            onFailed = { sid, reason -> sink.failures += sid to reason },
        )
        return bridge
    }

    // ── (1) no delegate → headless completion fallback fires once ──────

    @Test
    fun `no delegate onPipelineCompleted invokes headless completion sink exactly once`() {
        val guard = PipelineTerminalDispatchGuard()
        val sink = SinkRecorder()
        val bridge = bridgeWith(guard, sink, sessionId = "sess-1")

        bridge.onPipelineCompleted("hello world", InsertionSource.TRANSCRIPTION, null)

        assertEquals(listOf("sess-1" to "hello world"), sink.completions)
        assertTrue(sink.failures.isEmpty())
        // Guard consumed → a second producer for the same session cannot fire.
        assertFalse(guard.tryConsume("sess-1"))
    }

    // ── (2) delegate present → deliver, sink silent, guard consumed ────

    @Test
    fun `delegate present onPipelineCompleted delivers to delegate and not to sink`() {
        val guard = PipelineTerminalDispatchGuard()
        val sink = SinkRecorder()
        val bridge = bridgeWith(guard, sink, sessionId = "sess-2")
        val delegate = RecordingDelegate()
        bridge.setDelegate(delegate)

        bridge.onPipelineCompleted("delivered", InsertionSource.TRANSCRIPTION, null)

        assertEquals(1, delegate.completed.size)
        assertEquals("delivered" to InsertionSource.TRANSCRIPTION, delegate.completed[0])
        assertTrue("Headless sink must stay silent when a delegate is bound", sink.completions.isEmpty())
        // Delegate delivery consumes the guard (delegate-delivery-consumes rule).
        assertFalse(guard.tryConsume("sess-2"))
    }

    // ── (3) guard already consumed + delegate present → skip delivery ──

    @Test
    fun `already consumed guard skips delegate delivery`() {
        val guard = PipelineTerminalDispatchGuard()
        val sink = SinkRecorder()
        val bridge = bridgeWith(guard, sink, sessionId = "sess-3")
        val delegate = RecordingDelegate()
        bridge.setDelegate(delegate)
        // Someone (e.g. bind-reconciliation) already terminally dispatched.
        assertTrue(guard.tryConsume("sess-3"))

        bridge.onPipelineCompleted("should-not-double-commit", InsertionSource.TRANSCRIPTION, null)

        assertTrue("A consumed session must not re-deliver to the IME", delegate.completed.isEmpty())
        assertTrue(sink.completions.isEmpty())
    }

    // ── (5) onPipelineError headless → failure sink fires once ─────────

    @Test
    fun `no delegate onPipelineError invokes headless failure sink exactly once`() {
        val guard = PipelineTerminalDispatchGuard()
        val sink = SinkRecorder()
        val bridge = bridgeWith(guard, sink, sessionId = "sess-5")

        bridge.onPipelineError("network_error", true, "openai")

        assertEquals(listOf("sess-5" to "network_error"), sink.failures)
        assertTrue(sink.completions.isEmpty())
        assertFalse(guard.tryConsume("sess-5"))
    }

    @Test
    fun `delegate present onPipelineError delivers to delegate and consumes guard`() {
        val guard = PipelineTerminalDispatchGuard()
        val sink = SinkRecorder()
        val bridge = bridgeWith(guard, sink, sessionId = "sess-5b")
        val delegate = RecordingDelegate()
        bridge.setDelegate(delegate)

        bridge.onPipelineError("auth_failed", false, null)

        assertEquals(1, delegate.errors.size)
        assertTrue(sink.failures.isEmpty())
        assertFalse(guard.tryConsume("sess-5b"))
    }

    // ── (6) unresolvable sessionId → legacy drop, guard untouched ──────

    @Test
    fun `null sessionId provider drops terminal callback and leaves guard untouched`() {
        val guard = PipelineTerminalDispatchGuard()
        val sink = SinkRecorder()
        val bridge = bridgeWith(guard, sink, sessionId = null)
        val delegate = RecordingDelegate()
        bridge.setDelegate(delegate)

        bridge.onPipelineCompleted("dropped", InsertionSource.TRANSCRIPTION, null)
        bridge.onPipelineError("dropped", true, null)

        assertTrue("Unresolvable session → no delegate delivery", delegate.completed.isEmpty())
        assertTrue(delegate.errors.isEmpty())
        assertTrue(sink.completions.isEmpty())
        assertTrue(sink.failures.isEmpty())
        // Guard never touched → a real session can still be consumed later.
        assertTrue(guard.tryConsume("some-other-session"))
    }

    // ── Complete/fail are one-shot per session across both terminals ───

    @Test
    fun `a completed session cannot also fail`() {
        val guard = PipelineTerminalDispatchGuard()
        val sink = SinkRecorder()
        val bridge = bridgeWith(guard, sink, sessionId = "sess-x")

        bridge.onPipelineCompleted("done", InsertionSource.TRANSCRIPTION, null)
        bridge.onPipelineError("late_error", true, null)

        assertEquals(1, sink.completions.size)
        assertTrue("A session that completed must not also emit a failure", sink.failures.isEmpty())
    }

    // ── Backward-compat: no sink wired → legacy delegate behaviour ─────

    @Test
    fun `without headless sink wiring the bridge keeps legacy delegate delivery`() {
        val bridge = PipelineCallbackBridge()
        val delegate = RecordingDelegate()
        bridge.setDelegate(delegate)

        bridge.onPipelineCompleted("legacy", InsertionSource.TRANSCRIPTION, null)

        assertEquals(1, delegate.completed.size)
    }

    @Test
    fun `without headless sink wiring and no delegate the terminal callback drops`() {
        val bridge = PipelineCallbackBridge()
        // No delegate, no sink → must not throw (legacy drop).
        bridge.onPipelineCompleted("legacy", InsertionSource.TRANSCRIPTION, null)
        assertNull(bridge.currentDelegate())
    }
}
