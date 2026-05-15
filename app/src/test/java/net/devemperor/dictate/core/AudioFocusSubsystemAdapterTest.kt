package net.devemperor.dictate.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [AudioFocusSubsystemAdapter] — the orchestrator-side
 * adapter that wraps the IME-side [AudioFocusGate] seam.
 *
 * The adapter is a 4-line thin delegate; the tests pin the
 * `request → request`, `release → abandon` mapping so a refactor that
 * accidentally swaps the calls or drops one of them is caught by CI.
 *
 * Uses [FakeAudioFocusGate] (already in the project for
 * `RecordingStateController` tests) — K-1-conformant handwritten fake,
 * no Mockito.
 */
class AudioFocusSubsystemAdapterTest {

    @Test
    fun `request delegates to gate request and increments counter`() {
        val gate = FakeAudioFocusGate()
        val adapter = AudioFocusSubsystemAdapter(gate)

        adapter.request()

        assertEquals("request must reach the underlying gate", 1, gate.requestCount)
        assertEquals(0, gate.abandonCount)
    }

    @Test
    fun `release delegates to gate abandon`() {
        val gate = FakeAudioFocusGate()
        val adapter = AudioFocusSubsystemAdapter(gate)

        adapter.release()

        assertEquals("release must call gate abandon (not request)", 1, gate.abandonCount)
        assertEquals(0, gate.requestCount)
    }

    @Test
    fun `multiple requests are forwarded independently`() {
        val gate = FakeAudioFocusGate()
        val adapter = AudioFocusSubsystemAdapter(gate)

        adapter.request()
        adapter.request()
        adapter.release()
        adapter.request()

        // Pin the call-multiplicity: the adapter MUST forward every call
        // 1:1 to the gate — no batching, no deduplication.
        assertEquals(3, gate.requestCount)
        assertEquals(1, gate.abandonCount)
    }
}
