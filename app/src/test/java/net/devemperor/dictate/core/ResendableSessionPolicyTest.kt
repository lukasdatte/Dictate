package net.devemperor.dictate.core

import net.devemperor.dictate.database.entity.SessionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [ResendableSessionPolicy.isResendable] — the F-005 cold-boot
 * seed predicate for the RESEND-button visibility axis
 * (`ResendState.lastAudioExists`).
 *
 * The policy delegates to [ResendStatusDispatcher.decide] (resendable ⟺
 * not NoOp), so this matrix mirrors the dispatcher's decision table —
 * the point of the tests is to pin the *derived* boolean surface the
 * boot-seed relies on, so a dispatcher change that silently flips a
 * seed outcome fails here with an F-005-labelled test.
 *
 * | Status                  | Output     | Resendable? | Why |
 * |-------------------------|------------|-------------|-----|
 * | *any non in-flight*     | non-empty  | true        | Insert (text-first) |
 * | RECORDED                | empty/null | true        | Resume (audio on disk) |
 * | CANCELLED               | empty/null | true        | Resume (audio on disk) |
 * | COMPLETED               | empty/null | false       | NoOp (data-shape bug guard) |
 * | FAILED                  | empty/null | false       | NoOp (no silent retry) |
 * | RECORDING_INTERRUPTED   | empty/null | false       | NoOp (continuation via Record) |
 * | RECORDING / TRANSCRIBING| any        | false       | NoOp (in-flight) |
 */
class ResendableSessionPolicyTest {

    // ─── Text-first: any non-in-flight status with output is resendable ─

    @Test
    fun `COMPLETED with output is resendable`() {
        assertTrue(ResendableSessionPolicy.isResendable(SessionStatus.COMPLETED, "hello"))
    }

    @Test
    fun `FAILED with output is resendable (upstream step output survives)`() {
        assertTrue(ResendableSessionPolicy.isResendable(SessionStatus.FAILED, "partial"))
    }

    @Test
    fun `CANCELLED with output is resendable`() {
        assertTrue(ResendableSessionPolicy.isResendable(SessionStatus.CANCELLED, "partial"))
    }

    // ─── Audio-resume: RECORDED / CANCELLED without text ────────────────

    @Test
    fun `RECORDED without output is resendable (resume)`() {
        assertTrue(ResendableSessionPolicy.isResendable(SessionStatus.RECORDED, null))
    }

    @Test
    fun `CANCELLED without output is resendable (resume)`() {
        assertTrue(ResendableSessionPolicy.isResendable(SessionStatus.CANCELLED, null))
    }

    // ─── Not resendable: NoOp rows must keep the button hidden ─────────

    @Test
    fun `COMPLETED without output is not resendable`() {
        assertFalse(ResendableSessionPolicy.isResendable(SessionStatus.COMPLETED, null))
        assertFalse(ResendableSessionPolicy.isResendable(SessionStatus.COMPLETED, ""))
    }

    @Test
    fun `FAILED without output is not resendable`() {
        assertFalse(ResendableSessionPolicy.isResendable(SessionStatus.FAILED, null))
    }

    @Test
    fun `RECORDING_INTERRUPTED is not resendable (continuation owns it)`() {
        assertFalse(ResendableSessionPolicy.isResendable(SessionStatus.RECORDING_INTERRUPTED, null))
    }

    @Test
    fun `in-flight statuses are never resendable even with output`() {
        assertFalse(ResendableSessionPolicy.isResendable(SessionStatus.RECORDING, "text"))
        assertFalse(ResendableSessionPolicy.isResendable(SessionStatus.TRANSCRIBING, "text"))
    }
}
