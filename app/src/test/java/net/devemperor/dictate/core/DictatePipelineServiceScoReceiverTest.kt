package net.devemperor.dictate.core

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * F-013 regression test (2026-07-02): the service-owned
 * [BluetoothScoManager]'s SCO BroadcastReceiver must be registered on
 * the documented lifecycle points — `onCreate` registers, `onDestroy`
 * unregisters.
 *
 * Before the fix, `DictatePipelineService.onCreate` constructed the
 * manager but never called `registerReceiver()`, so
 * `ACTION_SCO_AUDIO_STATE_UPDATED` never reached it: `startSco()`
 * always waited out the 2500 ms timeout → `onScoFailed` →
 * `ScoRouteResolved(useBluetooth = false)` → every BT-mic recording
 * started ~2.5 s late and silently captured the phone mic. This test
 * fails on the unfixed wiring (no receiver for the SCO action after
 * boot) and pins the unregister so a future refactor cannot leak the
 * receiver past service teardown.
 *
 * **Quality-Gate K-4 exception (justified opt-out):** this IS service
 * lifecycle wiring — the receiver (un)registration is observable only
 * through a booted service + the shadow application's receiver table.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictatePipelineServiceScoReceiverTest {

    private val controller = Robolectric.buildService(DictatePipelineService::class.java)
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        try {
            controller.destroy()
        } catch (ignored: Throwable) {
        }
        // Test-pollution discipline (Epic R-7 / b5-ime-activation-wiring
        // §8) — this class boots the full Service; reset the process-wide
        // singletons so co-locating sibling tests start clean. Ordering:
        // scheduler reset precedes the DB reset.
        JobExecutor.resetForTest()
        ActiveJobRegistry.resetForTest()
        net.devemperor.dictate.database.DurationHealingScheduler.resetForTest()
        net.devemperor.dictate.database.DictateDatabase.resetForTest(
            ApplicationProvider.getApplicationContext(),
        )
    }

    private fun scoReceiverRegistered(): Boolean =
        shadowOf(app).registeredReceivers.any { wrapper ->
            wrapper.intentFilter.hasAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        }

    @Test
    fun `onCreate registers the SCO receiver — F-013 regression`() {
        assertFalse("precondition: no SCO receiver before boot", scoReceiverRegistered())
        controller.create()
        assertTrue(
            "F-013: DictatePipelineService.onCreate must register the " +
                "BluetoothScoManager receiver for ACTION_SCO_AUDIO_STATE_UPDATED",
            scoReceiverRegistered(),
        )
    }

    @Test
    fun `onDestroy unregisters the SCO receiver`() {
        controller.create()
        assertTrue("precondition: receiver registered after boot", scoReceiverRegistered())
        controller.destroy()
        assertFalse(
            "onDestroy must unregister the SCO receiver (no leak past teardown)",
            scoReceiverRegistered(),
        )
    }
}
