package net.devemperor.dictate.core

import net.devemperor.dictate.database.entity.SessionStatus

/**
 * Action selected by [ResendStatusDispatcher.decide] based on the last
 * keyboard session's terminal status and output text.
 *
 * Sealed so the IME service can {@code when}-exhaustively dispatch and a
 * future status would surface as a compile error rather than a silent
 * miss.
 */
sealed class ResendAction {
    /**
     * Run the 3-stage [ResendInsertStrategy] with [output].
     * Used for COMPLETED + non-empty output and CANCELLED + non-empty
     * output.
     */
    data class Insert(val output: String, val sessionId: String) : ResendAction()

    /**
     * Launch a pipeline-resume job for [sessionId].
     * Used for RECORDED, and for CANCELLED without output.
     */
    data class Resume(val sessionId: String) : ResendAction()

    /**
     * No-op. Used for FAILED (Phase-5 behaviour change — no silent
     * re-runs on API failures) and for COMPLETED with an unexpectedly
     * empty output (defensive).
     */
    object NoOp : ResendAction()
}

/**
 * Pure status-matrix dispatcher for the short-press resend button.
 *
 * Extracted from {@code DictateInputMethodService.onResendClicked} so the
 * matrix can be exercised in JVM unit tests without standing up an
 * Android service. The IME composes this with its
 * {@link android.os.Handler} + {@code dbExecutor} + insertion strategy.
 *
 * Decision table:
 *
 * | Status     | Output     | Action       |
 * |------------|------------|--------------|
 * | COMPLETED  | non-empty  | [Insert][ResendAction.Insert]      |
 * | COMPLETED  | empty/null | [NoOp][ResendAction.NoOp] (defensive) |
 * | CANCELLED  | non-empty  | [Insert][ResendAction.Insert]      |
 * | CANCELLED  | empty/null | [Resume][ResendAction.Resume]      |
 * | RECORDED   | n/a        | [Resume][ResendAction.Resume]      |
 * | FAILED     | n/a        | [NoOp][ResendAction.NoOp] (Phase-5 behaviour change) |
 */
object ResendStatusDispatcher {

    fun decide(status: SessionStatus, output: String?, sessionId: String): ResendAction {
        return when (status) {
            SessionStatus.COMPLETED ->
                if (!output.isNullOrEmpty()) ResendAction.Insert(output, sessionId)
                else ResendAction.NoOp

            SessionStatus.CANCELLED ->
                if (!output.isNullOrEmpty()) ResendAction.Insert(output, sessionId)
                else ResendAction.Resume(sessionId)

            SessionStatus.RECORDED ->
                ResendAction.Resume(sessionId)

            SessionStatus.FAILED ->
                ResendAction.NoOp

            // M4 (Spec 1 §6.1.3): the pipeline is already running for
            // this session — the resend short-press is a no-op so we
            // don't double-start. The single-job-lock in
            // ActiveJobRegistry would catch a doubled `Resume` anyway,
            // but returning NoOp keeps the contract crisp (no spurious
            // toast, no race).
            SessionStatus.RECORDING,
            SessionStatus.TRANSCRIBING ->
                ResendAction.NoOp

            // B2 / ADR-0008: a RECORDING_INTERRUPTED session is the
            // Cold-Resume hook — the user resumes via the Record-button
            // (which `ActionResolvers.resolveRecordAction` routes into
            // Continuation), not via the Resend-button. Same NoOp
            // discipline as RECORDING/TRANSCRIBING — the resend
            // dispatcher must not surface a stale "resume" intent for
            // a session that's already eligible for live continuation.
            SessionStatus.RECORDING_INTERRUPTED ->
                ResendAction.NoOp
        }
    }
}
