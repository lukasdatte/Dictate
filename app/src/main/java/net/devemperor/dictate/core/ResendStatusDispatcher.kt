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
 * # Decision philosophy (2026-06-04 rework)
 *
 * Short-press is **text-first**: whenever a non-empty `finalOutputText`
 * is on file, we insert it — regardless of the persisted status. The
 * user's mental model is "give me the last transcription back"; we
 * should not silently swallow a present text just because the pipeline
 * later marked the session FAILED at a downstream step. Re-running the
 * pipeline (Resume / Reprocess-Staging) belongs to the **long-press**
 * affordance (`onResendLongClicked` → ReprocessStaging UI).
 *
 * Decision table:
 *
 * | Status                  | Output     | Action  | Rationale |
 * |-------------------------|------------|---------|-----------|
 * | *any*                   | non-empty  | Insert  | text-first — user wants the transcription back |
 * | COMPLETED               | empty/null | NoOp    | defensive — COMPLETED w/o text is a data bug |
 * | CANCELLED               | empty/null | Resume  | aborted before result — re-run salvages the audio |
 * | RECORDED                | empty/null | Resume  | pipeline never ran — short-press kicks it off |
 * | FAILED                  | empty/null | NoOp    | no silent retry on API failure (Phase-5 policy) |
 * | RECORDING / TRANSCRIBING| n/a        | NoOp    | in-flight — `ActiveJobRegistry` would lock anyway |
 * | RECORDING_INTERRUPTED   | empty/null | NoOp    | continuation routes through the Record-button (ADR-0008) |
 */
object ResendStatusDispatcher {

    fun decide(status: SessionStatus, output: String?, sessionId: String): ResendAction {
        // In-flight guard FIRST — before the text-first branch — so an
        // intermediate output written during TRANSCRIBING cannot be
        // surfaced as a "final" insert while the pipeline is still
        // running. M4 (Spec 1 §6.1.3): the `ActiveJobRegistry` lock
        // would catch a doubled Resume anyway, but returning NoOp
        // keeps the contract crisp (no spurious toast, no race).
        if (status == SessionStatus.RECORDING || status == SessionStatus.TRANSCRIBING) {
            return ResendAction.NoOp
        }

        // Text-first: if the pipeline produced any output, hand it back
        // to the user — regardless of the persisted status. This covers
        // (a) COMPLETED+text (always), (b) CANCELLED+text (partially
        // transcribed before cancel), and (c) FAILED+text (an upstream
        // step succeeded before a downstream step failed — the user
        // should not lose the upstream-step output).
        if (!output.isNullOrEmpty()) {
            return ResendAction.Insert(output, sessionId)
        }

        // No text available — fall back to the per-status decision.
        return when (status) {
            // Pipeline was never started or aborted before result: the
            // short-press re-runs it. The user's audio is still on disk.
            SessionStatus.RECORDED,
            SessionStatus.CANCELLED ->
                ResendAction.Resume(sessionId)

            // Defensive: COMPLETED without text is a data-shape bug;
            // don't surface a confused intent.
            SessionStatus.COMPLETED ->
                ResendAction.NoOp

            // No silent retry on API failures — the user must
            // long-press to enter ReprocessStaging (Phase-5 policy).
            SessionStatus.FAILED ->
                ResendAction.NoOp

            // B2 / ADR-0008: a RECORDING_INTERRUPTED session is the
            // Cold-Resume hook — the user resumes via the Record-button
            // (which `ActionResolvers.resolveRecordAction` routes into
            // Continuation), not via the Resend-button.
            SessionStatus.RECORDING_INTERRUPTED ->
                ResendAction.NoOp

            // Unreachable — the in-flight guard above handles these.
            // Required by the sealed-when exhaustiveness rule.
            SessionStatus.RECORDING,
            SessionStatus.TRANSCRIBING ->
                ResendAction.NoOp
        }
    }
}
