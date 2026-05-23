package net.devemperor.dictate.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AmplitudeStreamAdapter] — Phase-1 no-op adapter with
 * observable lifecycle flag.
 *
 * Why test a no-op? The flag is the contract that future blocks (B5
 * LayoutCatalog + RecordingAnimationController) rely on to verify the
 * orchestrator's effect handler reaches the adapter. Pinning
 * idempotency + correct flag transitions today prevents a regression
 * when the body becomes a real sampling thread.
 */
class AmplitudeStreamAdapterTest {

    @Test
    fun `initial state is not running`() {
        val adapter = AmplitudeStreamAdapter()
        assertFalse("Adapter must default to stopped", adapter.isRunning)
    }

    @Test
    fun `start sets isRunning true`() {
        val adapter = AmplitudeStreamAdapter()
        adapter.start()
        assertTrue(adapter.isRunning)
    }

    @Test
    fun `stop after start sets isRunning false`() {
        val adapter = AmplitudeStreamAdapter()
        adapter.start()
        adapter.stop()
        assertFalse(adapter.isRunning)
    }

    @Test
    fun `start is idempotent`() {
        val adapter = AmplitudeStreamAdapter()
        adapter.start()
        adapter.start()
        adapter.start()
        assertTrue(adapter.isRunning)
    }

    @Test
    fun `stop without start is a no-op`() {
        val adapter = AmplitudeStreamAdapter()
        adapter.stop()
        assertFalse(adapter.isRunning)
    }
}
