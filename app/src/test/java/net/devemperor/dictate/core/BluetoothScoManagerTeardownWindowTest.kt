package net.devemperor.dictate.core

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Widget-cancel-restart fix (2026-07-09) — regression tests for the
 * SCO **async-teardown window** in [BluetoothScoManager].
 *
 * Android tears the SCO link down asynchronously after
 * `AudioManager.stopBluetoothSco()`: `isBluetoothScoOn` keeps
 * reporting `true` until the `SCO_AUDIO_STATE_DISCONNECTED` broadcast
 * lands. A recording cancelled and immediately restarted from the
 * overlay widget therefore hit `startSco()`'s already-connected
 * early-return against the *dying* link: the manager reported
 * `onScoConnected` synchronously, `RecordingModule` allocated the
 * `MediaRecorder` with `VOICE_COMMUNICATION`, and the capture route
 * died underneath it moments later — a recording that visually runs
 * (the state-driven timer keeps ticking) but records silence.
 *
 * The fix tracks the teardown window: after the manager itself calls
 * `stopBluetoothSco()`, the early-return is disabled until the next
 * terminal SCO broadcast (CONNECTED or DISCONNECTED) proves the flag
 * is fresh again. Inside the window `startSco()` runs the full
 * handshake (waiting → CONNECTED broadcast, or the timeout → MIC
 * fallback), which self-heals in both directions.
 *
 * **Quality-Gate K-4 exception (justified opt-out):** the contract
 * under test is the interplay of `AudioManager.isBluetoothScoOn` and
 * the manager's `ACTION_SCO_AUDIO_STATE_UPDATED` BroadcastReceiver —
 * both Android framework surfaces; Robolectric shadows are the
 * cheapest faithful stand-in (mirrors
 * [DictatePipelineServiceScoReceiverTest]'s justification).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BluetoothScoManagerTeardownWindowTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val audioManager: AudioManager =
        app.getSystemService(AudioManager::class.java)

    private var connectedCount = 0
    private var disconnectedCount = 0
    private var failedCount = 0

    private lateinit var manager: BluetoothScoManager

    @Before
    fun setUp() {
        manager = BluetoothScoManager(
            app,
            audioManager,
            object : BluetoothScoManager.BluetoothScoCallback {
                override fun onScoConnected() { connectedCount++ }
                override fun onScoDisconnected() { disconnectedCount++ }
                override fun onScoFailed() { failedCount++ }
            },
        )
        manager.registerReceiver()
    }

    @After
    fun tearDown() {
        manager.unregisterReceiver()
    }

    /** Emulate the platform SCO flag (what `isBluetoothScoOn` reads). */
    private fun setScoOnFlag(on: Boolean) {
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = on
    }

    private fun deliverScoBroadcast(state: Int) {
        app.sendBroadcast(
            Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                .putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, state),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `startSco inside the teardown window must not take the stale early-return`() {
        // Recording #1 — SCO up, early-return connects synchronously.
        setScoOnFlag(true)
        assertTrue("baseline: connected link early-returns", manager.startSco(2500))
        assertEquals(1, connectedCount)

        // Cancel — the manager stops SCO. Android's teardown is async:
        // the platform flag still reads `true` for a moment.
        manager.release()
        setScoOnFlag(true) // stale flag inside the teardown window

        // Restart within the window: the manager must NOT trust the
        // stale flag — no synchronous Connected, full handshake instead.
        val alreadyConnected = manager.startSco(2500)
        assertFalse(
            "startSco during the SCO teardown window must run the full " +
                "handshake, not report the dying link as connected",
            alreadyConnected,
        )
        assertEquals(
            "no synchronous onScoConnected against the dying link",
            1,
            connectedCount,
        )
        assertTrue("the manager must be waiting for the handshake", manager.isWaitingForSco)
    }

    @Test
    fun `terminal DISCONNECTED broadcast closes the teardown window`() {
        setScoOnFlag(true)
        manager.startSco(2500)
        manager.release()

        // The teardown completes: platform broadcasts DISCONNECTED.
        deliverScoBroadcast(AudioManager.SCO_AUDIO_STATE_DISCONNECTED)

        // A genuinely fresh link afterwards may early-return again.
        setScoOnFlag(true)
        connectedCount = 0
        assertTrue(
            "post-teardown fresh link early-returns again",
            manager.startSco(2500),
        )
        assertEquals(1, connectedCount)
    }

    @Test
    fun `CONNECTED broadcast during the window resolves the pending handshake`() {
        setScoOnFlag(true)
        manager.startSco(2500)
        manager.release()
        setScoOnFlag(true) // stale window

        // Restart → full handshake pending.
        assertFalse(manager.startSco(2500))
        connectedCount = 0

        // The new link comes up: CONNECTED broadcast resolves the wait.
        deliverScoBroadcast(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        assertEquals("handshake resolves via the broadcast", 1, connectedCount)
        assertFalse(manager.isWaitingForSco)
        assertTrue(manager.isScoStarted)
    }
}
