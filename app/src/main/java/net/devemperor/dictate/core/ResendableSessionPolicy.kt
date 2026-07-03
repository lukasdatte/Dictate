package net.devemperor.dictate.core

import net.devemperor.dictate.database.entity.SessionStatus

/**
 * Pure predicate: is the last keyboard session *resendable* — i.e. would
 * pressing RESEND actually do something?
 *
 * # Why this exists (F-005)
 *
 * The RESEND button's visibility axis (`ResendState.lastAudioExists`)
 * defaults to `false` and is flipped `true` only by post-pipeline events
 * (the PipelineModule cascade after `PipelineDone`, or the IME
 * `onShowResend` callback). After a process restart (keyboard switch,
 * OOM) nothing re-seeds the axis, so the button stays hidden even though
 * the last session's audio/text is still on disk — until the user
 * completes a *new* recording. [PipelineRecovery] seeds the axis at boot
 * by asking this policy whether a resendable session exists.
 *
 * # Single source of truth
 *
 * "Resendable" is defined by exactly the same matrix the short-press
 * resend uses at click time — [ResendStatusDispatcher.decide]. A session
 * is resendable iff that dispatcher returns anything other than
 * [ResendAction.NoOp]:
 *
 *  - **non-empty output** → Insert (text-first, mirrors the 2026-07-02
 *    `getFinalOutput` fix / commit 9637fc3 — the button should reappear
 *    when a resendable transcription exists, resolved via the
 *    authoritative fallback chain, not the empty denormalized column).
 *  - **RECORDED / CANCELLED without text** → Resume (audio still on disk,
 *    the pipeline can be re-run).
 *  - **COMPLETED-empty / FAILED / RECORDING_INTERRUPTED / in-flight** →
 *    NoOp (not resendable — button stays hidden).
 *
 * Keying the seed off the dispatcher rather than a second hand-rolled
 * matrix means the boot-seed and the click-time decision can never
 * drift.
 *
 * @see ResendStatusDispatcher
 * @see net.devemperor.dictate.state.PipelineRecovery
 */
object ResendableSessionPolicy {

    /**
     * True when a session with [status] + resolved [output] is
     * resendable (RESEND would insert or resume). The [output] must be
     * the authoritative resolved output (via
     * `SessionManager.getFinalOutput`), not the raw denormalized
     * `final_output_text` column.
     *
     * `sessionId` is irrelevant to the resend/no-resend decision, so the
     * dispatcher is fed a placeholder — the returned `sessionId` inside
     * `Insert`/`Resume` is never read here.
     */
    fun isResendable(status: SessionStatus, output: String?): Boolean =
        ResendStatusDispatcher.decide(status, output, sessionId = "") !is ResendAction.NoOp
}
