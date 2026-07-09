package net.devemperor.dictate.core

import android.content.Intent
import net.devemperor.dictate.state.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [PipelineActionRouter.dispatch] — the intent→Action
 * back-channel entering `DictatePipelineService.onStartCommand`.
 *
 * Robolectric (K-4 exception): `Intent.action` set/get needs the real
 * Android class, and the START_DICTATION arm is exercised with a real
 * Intent exactly as onStartCommand receives it.
 *
 * Focus: the external-start arm (`ACTION_START_DICTATION`, 2026-07-09
 * external-dictation-entry-points). The notification-button arms have
 * indirect coverage via PipelineNotificationCoordinatorTest; two
 * representative arms are pinned here so a future `when`-refactor
 * cannot silently drop them.
 */
@RunWith(RobolectricTestRunner::class)
class PipelineActionRouterTest {

    private class Recorder {
        val actions = mutableListOf<Action>()
        var externalStartCalls = 0

        fun router() = PipelineActionRouter(
            dispatchAction = { actions += it },
            onExternalDictationStart = { externalStartCalls++ },
        )
    }

    @Test
    fun `START_DICTATION intent invokes the external-start hook and dispatches no direct action`() {
        val rec = Recorder()

        rec.router().dispatch(Intent(PipelineActionRouter.ACTION_START_DICTATION))

        assertEquals(1, rec.externalStartCalls)
        assertTrue(rec.actions.isEmpty())
    }

    @Test
    fun `null intent is ignored`() {
        val rec = Recorder()

        rec.router().dispatch(null)

        assertEquals(0, rec.externalStartCalls)
        assertTrue(rec.actions.isEmpty())
    }

    @Test
    fun `unknown action is ignored`() {
        val rec = Recorder()

        rec.router().dispatch(Intent("net.devemperor.dictate.UNKNOWN"))

        assertEquals(0, rec.externalStartCalls)
        assertTrue(rec.actions.isEmpty())
    }

    @Test
    fun `redelivered START_DICTATION is suppressed — no spontaneous mic arm after OOM-kill`() {
        // The service returns START_REDELIVER_INTENT; after a crash the
        // system re-delivers the last start intent with
        // START_FLAG_REDELIVERY set. Re-running the external start would
        // arm the microphone without a user trigger.
        val rec = Recorder()

        rec.router().dispatch(
            Intent(PipelineActionRouter.ACTION_START_DICTATION),
            android.app.Service.START_FLAG_REDELIVERY,
        )

        assertEquals(0, rec.externalStartCalls)
        assertTrue(rec.actions.isEmpty())
    }

    @Test
    fun `redelivered notification action stays routed (only the external start is suppressed)`() {
        val rec = Recorder()

        rec.router().dispatch(
            Intent(PipelineActionRouter.ACTION_PAUSE),
            android.app.Service.START_FLAG_REDELIVERY,
        )

        assertEquals(listOf<Action>(Action.RecordingAction.PauseRecording), rec.actions)
    }

    @Test
    fun `PAUSE intent still maps to PauseRecording`() {
        val rec = Recorder()

        rec.router().dispatch(Intent(PipelineActionRouter.ACTION_PAUSE))

        assertEquals(listOf<Action>(Action.RecordingAction.PauseRecording), rec.actions)
        assertEquals(0, rec.externalStartCalls)
    }

    @Test
    fun `SEND intent still maps to StopRecordingAndSend`() {
        val rec = Recorder()

        rec.router().dispatch(Intent(PipelineActionRouter.ACTION_SEND))

        assertEquals(listOf<Action>(Action.RecordingAction.StopRecordingAndSend), rec.actions)
    }
}
