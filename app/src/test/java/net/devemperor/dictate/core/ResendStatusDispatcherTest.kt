package net.devemperor.dictate.core

import net.devemperor.dictate.database.entity.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [ResendStatusDispatcher.decide] — the status matrix for
 * {@code DictateInputMethodService.onResendClicked}. The Service then
 * dispatches the resulting [ResendAction] to the 3-stage insertion
 * strategy or to the resume-job launcher.
 *
 * Service-integration (IC capture, double-click cooldown, dispatch
 * wiring) is verified manually — see plan §5.7.
 *
 * Decision table — text-first contract (2026-06-04):
 *
 * | Status                  | Output     | Action  |
 * |-------------------------|------------|---------|
 * | *any non in-flight*     | non-empty  | Insert  |
 * | COMPLETED               | empty/null | NoOp    |
 * | CANCELLED               | empty/null | Resume  |
 * | RECORDED                | n/a        | Resume  |
 * | FAILED                  | empty/null | NoOp    |
 * | RECORDING / TRANSCRIBING| n/a        | NoOp    |
 * | RECORDING_INTERRUPTED   | empty/null | NoOp    |
 */
class ResendStatusDispatcherTest {

    private val sessionId = "test-session-id"

    @Test
    fun `COMPLETED with output returns Insert`() {
        val action = ResendStatusDispatcher.decide(
            SessionStatus.COMPLETED, "the output", sessionId)

        assertTrue(action is ResendAction.Insert)
        action as ResendAction.Insert
        assertEquals("the output", action.output)
        assertEquals(sessionId, action.sessionId)
    }

    @Test
    fun `COMPLETED with null output returns NoOp (defensive)`() {
        val action = ResendStatusDispatcher.decide(
            SessionStatus.COMPLETED, null, sessionId)

        assertTrue(action === ResendAction.NoOp)
    }

    @Test
    fun `COMPLETED with empty output returns NoOp (defensive)`() {
        val action = ResendStatusDispatcher.decide(
            SessionStatus.COMPLETED, "", sessionId)

        assertTrue(action === ResendAction.NoOp)
    }

    @Test
    fun `CANCELLED with output returns Insert`() {
        val action = ResendStatusDispatcher.decide(
            SessionStatus.CANCELLED, "interrupted text", sessionId)

        assertTrue(action is ResendAction.Insert)
        action as ResendAction.Insert
        assertEquals("interrupted text", action.output)
        assertEquals(sessionId, action.sessionId)
    }

    @Test
    fun `CANCELLED without output returns Resume`() {
        val action = ResendStatusDispatcher.decide(
            SessionStatus.CANCELLED, null, sessionId)

        assertTrue(action is ResendAction.Resume)
        action as ResendAction.Resume
        assertEquals(sessionId, action.sessionId)
    }

    @Test
    fun `CANCELLED with empty output returns Resume`() {
        val action = ResendStatusDispatcher.decide(
            SessionStatus.CANCELLED, "", sessionId)

        assertTrue(action is ResendAction.Resume)
        action as ResendAction.Resume
        assertEquals(sessionId, action.sessionId)
    }

    @Test
    fun `RECORDED without output returns Resume`() {
        // RECORDED in practice never has output (pipeline never ran).
        val withNull = ResendStatusDispatcher.decide(
            SessionStatus.RECORDED, null, sessionId)
        val withEmpty = ResendStatusDispatcher.decide(
            SessionStatus.RECORDED, "", sessionId)

        assertTrue(withNull is ResendAction.Resume)
        assertTrue(withEmpty is ResendAction.Resume)
    }

    @Test
    fun `RECORDED with output returns Insert - text-first overrides status`() {
        // Defensive — if a hypothetical recovery path populates
        // finalOutputText on a RECORDED row, prefer giving the user
        // their text back over re-running the pipeline.
        val action = ResendStatusDispatcher.decide(
            SessionStatus.RECORDED, "stored text", sessionId)
        assertTrue(action is ResendAction.Insert)
    }

    @Test
    fun `FAILED without text returns NoOp - no silent retry on API failure`() {
        // Phase-5 policy: don't auto-resume on API failure — the user
        // must long-press to enter ReprocessStaging.
        val withNull = ResendStatusDispatcher.decide(
            SessionStatus.FAILED, null, sessionId)
        val withEmpty = ResendStatusDispatcher.decide(
            SessionStatus.FAILED, "", sessionId)

        assertTrue(withNull === ResendAction.NoOp)
        assertTrue(withEmpty === ResendAction.NoOp)
    }

    @Test
    fun `FAILED with text returns Insert - text-first salvages partial output`() {
        // 2026-06-04: an upstream step (Whisper) succeeded; a
        // downstream step failed. The user's transcription is still
        // recoverable — short-press inserts it instead of swallowing
        // it. Long-press is the path to re-run the pipeline.
        val action = ResendStatusDispatcher.decide(
            SessionStatus.FAILED, "partial output", sessionId)

        assertTrue(action is ResendAction.Insert)
        action as ResendAction.Insert
        assertEquals("partial output", action.output)
    }

    @Test
    fun `RECORDING_INTERRUPTED without text returns NoOp - continuation via Record-button`() {
        val withNull = ResendStatusDispatcher.decide(
            SessionStatus.RECORDING_INTERRUPTED, null, sessionId)
        val withEmpty = ResendStatusDispatcher.decide(
            SessionStatus.RECORDING_INTERRUPTED, "", sessionId)

        assertTrue(withNull === ResendAction.NoOp)
        assertTrue(withEmpty === ResendAction.NoOp)
    }

    @Test
    fun `RECORDING_INTERRUPTED with text returns Insert - text-first wins`() {
        // RECORDING_INTERRUPTED rarely carries text (pipeline never
        // ran), but if a future recovery path populates finalOutputText
        // the text-first contract still applies. The ADR-0008
        // continuation hook stays on the Record-button.
        val action = ResendStatusDispatcher.decide(
            SessionStatus.RECORDING_INTERRUPTED, "recovered text", sessionId)

        assertTrue(action is ResendAction.Insert)
    }

    @Test
    fun `Insert action carries the lastSession id, not the tracker id`() {
        // Quality-Gate W-3 — resend audit log must bind to the *last*
        // keyboard session, never to whatever the SessionTracker thinks
        // is "current" by the time the click reaches the main thread.
        val action = ResendStatusDispatcher.decide(
            SessionStatus.COMPLETED, "out", "concrete-last-session-id")

        action as ResendAction.Insert
        assertEquals("concrete-last-session-id", action.sessionId)
    }

    @Test
    fun `Resume action carries the lastSession id`() {
        val action = ResendStatusDispatcher.decide(
            SessionStatus.RECORDED, null, "concrete-last-session-id")

        action as ResendAction.Resume
        assertEquals("concrete-last-session-id", action.sessionId)
    }

    // ── M4 (Spec 1 §6.1.3): live-state branches ──
    // RECORDING / TRANSCRIBING should never reach the short-press
    // dispatcher in practice — the recovery path promotes them
    // BEFORE history loads — but if they did, the dispatcher must
    // return NoOp so the keyboard does not double-start a job.

    @Test
    fun `RECORDING returns NoOp regardless of output - live-state guard`() {
        val withNull = ResendStatusDispatcher.decide(
            SessionStatus.RECORDING, null, sessionId)
        val withEmpty = ResendStatusDispatcher.decide(
            SessionStatus.RECORDING, "", sessionId)
        val withText = ResendStatusDispatcher.decide(
            SessionStatus.RECORDING, "should be ignored", sessionId)

        assertTrue(withNull === ResendAction.NoOp)
        assertTrue(withEmpty === ResendAction.NoOp)
        assertTrue(withText === ResendAction.NoOp)
    }

    @Test
    fun `TRANSCRIBING returns NoOp regardless of output - live-state guard`() {
        val withNull = ResendStatusDispatcher.decide(
            SessionStatus.TRANSCRIBING, null, sessionId)
        val withEmpty = ResendStatusDispatcher.decide(
            SessionStatus.TRANSCRIBING, "", sessionId)
        val withText = ResendStatusDispatcher.decide(
            SessionStatus.TRANSCRIBING, "should be ignored", sessionId)

        assertTrue(withNull === ResendAction.NoOp)
        assertTrue(withEmpty === ResendAction.NoOp)
        assertTrue(withText === ResendAction.NoOp)
    }
}
