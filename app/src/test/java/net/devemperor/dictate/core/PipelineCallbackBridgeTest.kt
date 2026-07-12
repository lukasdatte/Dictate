package net.devemperor.dictate.core

import net.devemperor.dictate.database.entity.InsertionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Unit tests for [PipelineCallbackBridge] — the IMPL-1-closure
 * indirection between the service-owned [PipelineOrchestrator] and the
 * IME-owned [PipelineOrchestrator.PipelineCallback] implementation.
 *
 * Coverage focus:
 *  - Delegate registration / clearance via [setDelegate].
 *  - Forwarding semantics: each `onX(...)` call lands on the active
 *    delegate with the expected args.
 *  - Null-delegate gap behaviour: calls during process boot (no IME
 *    bound) or after IME unbind drop silently — no NPE, no throw.
 *  - Delegate-throws isolation: a misbehaving IME-side delegate must
 *    NOT abort the pipeline thread.
 */
class PipelineCallbackBridgeTest {

    /**
     * Recording fake — captures every call's name + args so the test
     * can assert ordering + payload equality. K-1-conformant: pure
     * Kotlin, no Mockito.
     */
    private class RecordingDelegate : PipelineOrchestrator.PipelineCallback {
        data class Call(val method: String, val args: List<Any?>)

        val calls = mutableListOf<Call>()

        override fun onStepStarted(stepName: String) {
            calls += Call("onStepStarted", listOf(stepName))
        }

        override fun onStepCompleted(stepName: String, durationMs: Long) {
            calls += Call("onStepCompleted", listOf(stepName, durationMs))
        }

        override fun onStepFailed(stepName: String) {
            calls += Call("onStepFailed", listOf(stepName))
        }

        override fun onPipelineCompleted(text: String, source: InsertionSource, review: net.devemperor.dictate.ai.conversation.PostProcessingReview?) {
            calls += Call("onPipelineCompleted", listOf(text, source))
        }

        override fun onPipelineError(errorInfoKey: String, vibrate: Boolean, providerName: String?) {
            calls += Call("onPipelineError", listOf(errorInfoKey, vibrate, providerName))
        }

        override fun onPipelineFinished() {
            calls += Call("onPipelineFinished", emptyList())
        }

        override fun onShowResend() {
            calls += Call("onShowResend", emptyList())
        }

        override fun onAutoSwitch() {
            calls += Call("onAutoSwitch", emptyList())
        }

        override fun onAudioPersisted(audioFile: File, sessionId: String) {
            calls += Call("onAudioPersisted", listOf(audioFile, sessionId))
        }
    }

    private class ThrowingDelegate : PipelineOrchestrator.PipelineCallback {
        override fun onStepStarted(stepName: String) {
            throw RuntimeException("boom")
        }

        override fun onStepCompleted(stepName: String, durationMs: Long) = Unit
        override fun onStepFailed(stepName: String) = Unit
        override fun onPipelineCompleted(text: String, source: InsertionSource, review: net.devemperor.dictate.ai.conversation.PostProcessingReview?) = Unit
        override fun onPipelineError(errorInfoKey: String, vibrate: Boolean, providerName: String?) = Unit
        override fun onPipelineFinished() = Unit
        override fun onShowResend() = Unit
        override fun onAutoSwitch() = Unit
        override fun onAudioPersisted(audioFile: File, sessionId: String) = Unit
    }

    // ── Delegate registration ────────────────────────────────────────

    @Test
    fun `initial state has no delegate`() {
        val bridge = PipelineCallbackBridge()
        assertNull(bridge.currentDelegate())
    }

    @Test
    fun `setDelegate stores the reference and currentDelegate returns it`() {
        val bridge = PipelineCallbackBridge()
        val delegate = RecordingDelegate()
        bridge.setDelegate(delegate)
        assertEquals(delegate, bridge.currentDelegate())
    }

    @Test
    fun `setDelegate null clears the reference`() {
        val bridge = PipelineCallbackBridge()
        bridge.setDelegate(RecordingDelegate())
        bridge.setDelegate(null)
        assertNull(bridge.currentDelegate())
    }

    // ── Forwarding ───────────────────────────────────────────────────

    @Test
    fun `onStepStarted with delegate forwards the call`() {
        val bridge = PipelineCallbackBridge()
        val delegate = RecordingDelegate()
        bridge.setDelegate(delegate)

        bridge.onStepStarted("transcribe")

        assertEquals(1, delegate.calls.size)
        assertEquals("onStepStarted", delegate.calls[0].method)
        assertEquals(listOf<Any?>("transcribe"), delegate.calls[0].args)
    }

    @Test
    fun `onStepCompleted forwards stepName and duration`() {
        val bridge = PipelineCallbackBridge()
        val delegate = RecordingDelegate()
        bridge.setDelegate(delegate)

        bridge.onStepCompleted("transcribe", 1234L)

        assertEquals(listOf<Any?>("transcribe", 1234L), delegate.calls[0].args)
    }

    @Test
    fun `onPipelineCompleted forwards text and source`() {
        val bridge = PipelineCallbackBridge()
        val delegate = RecordingDelegate()
        bridge.setDelegate(delegate)

        bridge.onPipelineCompleted("hello world", InsertionSource.TRANSCRIPTION, null)

        assertEquals(
            listOf<Any?>("hello world", InsertionSource.TRANSCRIPTION),
            delegate.calls[0].args,
        )
    }

    @Test
    fun `onPipelineError forwards all three args`() {
        val bridge = PipelineCallbackBridge()
        val delegate = RecordingDelegate()
        bridge.setDelegate(delegate)

        bridge.onPipelineError("auth_failed", true, "openai")

        assertEquals(
            listOf<Any?>("auth_failed", true, "openai"),
            delegate.calls[0].args,
        )
    }

    @Test
    fun `onAudioPersisted forwards file and sessionId`() {
        val bridge = PipelineCallbackBridge()
        val delegate = RecordingDelegate()
        bridge.setDelegate(delegate)

        val file = File("/tmp/x.m4a")
        bridge.onAudioPersisted(file, "sess-123")

        assertEquals(listOf<Any?>(file, "sess-123"), delegate.calls[0].args)
    }

    @Test
    fun `multiple sequential calls land in order`() {
        val bridge = PipelineCallbackBridge()
        val delegate = RecordingDelegate()
        bridge.setDelegate(delegate)

        bridge.onStepStarted("a")
        bridge.onStepCompleted("a", 100L)
        bridge.onShowResend()
        bridge.onPipelineFinished()

        assertEquals(4, delegate.calls.size)
        assertEquals("onStepStarted", delegate.calls[0].method)
        assertEquals("onStepCompleted", delegate.calls[1].method)
        assertEquals("onShowResend", delegate.calls[2].method)
        assertEquals("onPipelineFinished", delegate.calls[3].method)
    }

    // ── Null-delegate gap (process boot / IME unbound) ──────────────

    @Test
    fun `onStepStarted with no delegate drops silently`() {
        val bridge = PipelineCallbackBridge()
        // Must not throw — pipeline thread continues.
        bridge.onStepStarted("x")
    }

    @Test
    fun `all callbacks with no delegate drop silently`() {
        val bridge = PipelineCallbackBridge()

        bridge.onStepStarted("x")
        bridge.onStepCompleted("x", 1)
        bridge.onStepFailed("x")
        bridge.onPipelineCompleted("x", InsertionSource.TRANSCRIPTION, null)
        bridge.onPipelineError("k", false, null)
        bridge.onPipelineFinished()
        bridge.onShowResend()
        bridge.onAutoSwitch()
        bridge.onAudioPersisted(File("/tmp/x"), "s")

        // No exception => no NPE in the dispatch path.
    }

    @Test
    fun `delegate cleared mid-pipeline drops further calls`() {
        val bridge = PipelineCallbackBridge()
        val delegate = RecordingDelegate()
        bridge.setDelegate(delegate)

        bridge.onStepStarted("first")
        bridge.setDelegate(null)
        bridge.onStepStarted("second-dropped")

        assertEquals(
            "Only the pre-clear call must reach the delegate",
            1, delegate.calls.size,
        )
        assertEquals("first", delegate.calls[0].args[0])
    }

    // ── Delegate-throws isolation (pipeline-thread safety) ──────────

    @Test
    fun `delegate throwing does not propagate to the caller`() {
        val bridge = PipelineCallbackBridge()
        bridge.setDelegate(ThrowingDelegate())

        // The bridge MUST swallow — the pipeline thread cannot afford
        // to abort on a misbehaving IME-side callback.
        bridge.onStepStarted("x")
        // Reaching this line is the assertion (no exception thrown).
    }

    @Test
    fun `delegate throw on one call does not break subsequent calls on a fresh delegate`() {
        val bridge = PipelineCallbackBridge()
        bridge.setDelegate(ThrowingDelegate())
        bridge.onStepStarted("breaker")

        // Swap delegate after the throw — fresh delegate must still
        // receive its calls (the throw didn't corrupt the bridge).
        val recording = RecordingDelegate()
        bridge.setDelegate(recording)
        bridge.onStepStarted("recovery")

        assertEquals(1, recording.calls.size)
        assertEquals("recovery", recording.calls[0].args[0])
    }
}
