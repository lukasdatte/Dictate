package net.devemperor.dictate.core

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.RecordingState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Robolectric regression tests for the **service-owned** recording
 * ticker (2026-07-11 external-start incident, symptom "no amplitude
 * bars / no timer in the overlay widget").
 *
 * Pre-fix the [RecordingActivityTickerObserver] was constructed only in
 * `DictateInputMethodService` — on a fresh process where the external
 * entry point (`ACTION_START_DICTATION`) started a recording without
 * the IME ever binding, NO tick producer existed: the overlay widget
 * recorded with no bars, no glow, and a frozen timer. The service now
 * owns the single ticker (it owns the recorder, the state flow, and the
 * `OverlayBackend`); the IME registers per-tick sinks via
 * [DictatePipelineService.LocalBinder.registerRecordingTickSinks].
 *
 * The tests here boot the REAL service and deliberately never simulate
 * an IME beyond registering probe sinks — exactly the incident's
 * process shape.
 *
 * Test-pollution discipline mirrors
 * [DictatePipelineServiceRecordingDriveTest] (full-service boots share
 * process-wide singletons).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictatePipelineServiceRecordingTickerTest {

    private val controller = Robolectric.buildService(DictatePipelineService::class.java)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @After
    fun tearDown() {
        try {
            controller.destroy()
        } catch (ignored: Throwable) {
        }
        nm.cancelAll()
        JobExecutor.resetForTest()
        ActiveJobRegistry.resetForTest()
        // The ticker's cross-instance timer persistence would leak the
        // session anchor into the next test's fresh boot.
        RecordingActivityTickerObserver.clearPersistedState()
        net.devemperor.dictate.database.DurationHealingScheduler.resetForTest()
        net.devemperor.dictate.database.DictateDatabase.resetForTest(
            ApplicationProvider.getApplicationContext(),
        )
    }

    private fun boot(): DictatePipelineService.LocalBinder {
        controller.create()
        val b = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        ShadowLooper.idleMainLooper()
        return b
    }

    @Test
    fun `service-owned ticker produces timer and amplitude ticks without any IME`() {
        val b = boot()
        val timerTicks = CopyOnWriteArrayList<Long>()
        val ampSamples = CopyOnWriteArrayList<Int>()
        // Probe sinks via the same seam the IME uses — but note the
        // regression under test is that the TICK PRODUCER lives in the
        // service: pre-fix no ticks exist at all in this process shape.
        b.registerRecordingTickSinks(
            { elapsed -> timerTicks.add(elapsed) },
            { raw -> ampSamples.add(raw) },
        )

        val audio = File.createTempFile("svc-ticker", ".m4a", app.cacheDir)
        b.dispatch(
            Action.RecordingAction.StartRecording(
                target = InsertionTarget.INPUT_CONNECTION,
                audioFile = audio,
                sessionId = "svc-ticker-sid",
            ),
        )
        ShadowLooper.idleMainLooper()

        assertTrue(
            "pre-condition: recording FSM must reach Active, was ${b.state.value.recording}",
            b.state.value.recording is RecordingState.Active,
        )
        assertTrue(
            "service-owned ticker must emit timer ticks with no IME bound",
            timerTicks.isNotEmpty(),
        )
        assertTrue(
            "service-owned ticker must poll + forward raw amplitude with no IME bound",
            ampSamples.isNotEmpty(),
        )
    }

    @Test
    fun `pause freezes the service-owned ticker on a final frozen value`() {
        val b = boot()
        val timerTicks = CopyOnWriteArrayList<Long>()
        b.registerRecordingTickSinks({ elapsed -> timerTicks.add(elapsed) }, { })

        val audio = File.createTempFile("svc-ticker-p", ".m4a", app.cacheDir)
        b.dispatch(
            Action.RecordingAction.StartRecording(
                target = InsertionTarget.INPUT_CONNECTION,
                audioFile = audio,
                sessionId = "svc-ticker-sid-p",
            ),
        )
        ShadowLooper.idleMainLooper()
        b.dispatch(Action.RecordingAction.PauseRecording)
        ShadowLooper.idleMainLooper()

        assertTrue(
            "freeze emission must have reached the sink",
            timerTicks.isNotEmpty(),
        )
        val afterPause = timerTicks.size
        // No further ticks while Paused — drain the looper again and
        // assert the count is stable (the loop was stopped, not merely
        // slowed).
        ShadowLooper.idleMainLooper()
        assertTrue(
            "no ticks may be produced while Paused",
            timerTicks.size == afterPause,
        )
    }
}
