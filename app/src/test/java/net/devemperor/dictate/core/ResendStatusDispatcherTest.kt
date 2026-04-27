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
 * Decision table (ground truth — keep in sync with the plan's Phase-5
 * Status Matrix):
 *
 * | Status     | Output     | Action  |
 * |------------|------------|---------|
 * | COMPLETED  | non-empty  | Insert  |
 * | COMPLETED  | empty/null | NoOp    |
 * | CANCELLED  | non-empty  | Insert  |
 * | CANCELLED  | empty/null | Resume  |
 * | RECORDED   | n/a        | Resume  |
 * | FAILED     | n/a        | NoOp    |
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
    fun `RECORDED returns Resume regardless of output`() {
        // RECORDED never has output — but we test both paths anyway to
        // pin the contract that output is irrelevant for this status.
        val withNull = ResendStatusDispatcher.decide(
            SessionStatus.RECORDED, null, sessionId)
        val withEmpty = ResendStatusDispatcher.decide(
            SessionStatus.RECORDED, "", sessionId)
        val withText = ResendStatusDispatcher.decide(
            SessionStatus.RECORDED, "should be ignored", sessionId)

        assertTrue(withNull is ResendAction.Resume)
        assertTrue(withEmpty is ResendAction.Resume)
        assertTrue(withText is ResendAction.Resume)
    }

    @Test
    fun `FAILED returns NoOp - Phase 5 behaviour change`() {
        // Before Phase 5, FAILED triggered an automatic resume. The new
        // contract: no silent retry on API failures — the user must
        // long-press to enter ReprocessStaging.
        val withNull = ResendStatusDispatcher.decide(
            SessionStatus.FAILED, null, sessionId)
        val withEmpty = ResendStatusDispatcher.decide(
            SessionStatus.FAILED, "", sessionId)
        val withText = ResendStatusDispatcher.decide(
            SessionStatus.FAILED, "partial output", sessionId)

        assertTrue(withNull === ResendAction.NoOp)
        assertTrue(withEmpty === ResendAction.NoOp)
        assertTrue(withText === ResendAction.NoOp)
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
}
