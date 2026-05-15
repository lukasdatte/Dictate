package net.devemperor.dictate.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BorderGlowAdapter] — Phase-1 no-op adapter with
 * observable lifecycle flag. Same shape as [AmplitudeStreamAdapterTest]
 * for the same reasons (pinning the contract until B5 supplies the
 * real animation driver).
 */
class BorderGlowAdapterTest {

    @Test
    fun `initial state is not running`() {
        val adapter = BorderGlowAdapter()
        assertFalse(adapter.isRunning)
    }

    @Test
    fun `start sets isRunning true`() {
        val adapter = BorderGlowAdapter()
        adapter.start()
        assertTrue(adapter.isRunning)
    }

    @Test
    fun `stop after start sets isRunning false`() {
        val adapter = BorderGlowAdapter()
        adapter.start()
        adapter.stop()
        assertFalse(adapter.isRunning)
    }

    @Test
    fun `start is idempotent`() {
        val adapter = BorderGlowAdapter()
        adapter.start()
        adapter.start()
        assertTrue(adapter.isRunning)
    }
}
