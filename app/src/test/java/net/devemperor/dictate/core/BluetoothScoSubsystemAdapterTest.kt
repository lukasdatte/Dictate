package net.devemperor.dictate.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [BluetoothScoSubsystemAdapter] — verifies the
 * `start → startSco` / `stop → release` delegation contract.
 *
 * Uses a handwritten [BluetoothScoControl] fake (K-1 conformant). No
 * Mockito, no Robolectric, no Android Context.
 */
class BluetoothScoSubsystemAdapterTest {

    private class FakeBluetoothScoControl : BluetoothScoControl {
        var startScoCallCount: Int = 0
            private set
        var releaseCallCount: Int = 0
            private set
        var lastTimeoutMs: Long? = null
            private set

        override val isScoStarted: Boolean = false

        override fun startSco(timeoutMs: Long): Boolean {
            startScoCallCount += 1
            lastTimeoutMs = timeoutMs
            return false
        }

        override fun release() {
            releaseCallCount += 1
        }

        override fun isBluetoothAvailable(useBluetoothMic: Boolean): Boolean = false
    }

    @Test
    fun `start delegates to startSco`() {
        val fake = FakeBluetoothScoControl()
        val adapter = BluetoothScoSubsystemAdapter(fake)

        adapter.start()

        assertEquals(1, fake.startScoCallCount)
        assertEquals(0, fake.releaseCallCount)
    }

    @Test
    fun `stop delegates to release`() {
        val fake = FakeBluetoothScoControl()
        val adapter = BluetoothScoSubsystemAdapter(fake)

        adapter.stop()

        assertEquals(1, fake.releaseCallCount)
        assertEquals(0, fake.startScoCallCount)
    }

    @Test
    fun `start uses the default timeout from BluetoothScoControl interface`() {
        // The default timeout (2500 ms) lives on the interface — the
        // adapter must NOT override it. This test pins the contract so a
        // future refactor that adds a custom timeout has to update this
        // assertion + a deviation note.
        val fake = FakeBluetoothScoControl()
        val adapter = BluetoothScoSubsystemAdapter(fake)

        adapter.start()

        assertEquals(2500L, fake.lastTimeoutMs)
    }

    @Test
    fun `multiple start_stop cycles forward correctly`() {
        val fake = FakeBluetoothScoControl()
        val adapter = BluetoothScoSubsystemAdapter(fake)

        adapter.start()
        adapter.stop()
        adapter.start()
        adapter.stop()
        adapter.start()

        assertEquals(3, fake.startScoCallCount)
        assertEquals(2, fake.releaseCallCount)
    }
}
