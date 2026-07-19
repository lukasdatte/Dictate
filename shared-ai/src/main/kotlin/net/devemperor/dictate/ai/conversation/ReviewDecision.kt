package net.devemperor.dictate.ai.conversation

import net.devemperor.dictate.preferences.AmbiguityMode

/**
 * The single, pure rule that maps an ambiguity mode + a turn's verdict to
 * "insert the output" vs "open the review panel" (ADR-0013). Android-free and
 * exhaustively testable.
 *
 * This decides only the *verdict*; the IME layers the visibility gate on top
 * (a review panel opens only when the IME view is visible — otherwise the text
 * falls back to a pending part, ADR-0011).
 */
enum class Verdict { INSERT, REVIEW }

object ReviewDecision {

    /**
     * - [AmbiguityMode.ALWAYS_INSERT] — never review.
     * - [AmbiguityMode.AUTO] — review only when the model flagged
     *   [needsClarification] AND produced a non-blank [message]. A blank/null
     *   message can never trigger a phantom review (the "message == null ⇒ no
     *   ambiguity" safety net); a fallback provider that omits the verdict
     *   yields `needsClarification=false` and thus inserts.
     * - [AmbiguityMode.ALWAYS_REVIEW] — always review (the panel renders
     *   output-only when the message is blank).
     */
    fun decide(mode: AmbiguityMode, needsClarification: Boolean, message: String?): Verdict =
        when (mode) {
            AmbiguityMode.ALWAYS_INSERT -> Verdict.INSERT
            AmbiguityMode.AUTO ->
                if (needsClarification && !message.isNullOrBlank()) Verdict.REVIEW else Verdict.INSERT
            AmbiguityMode.ALWAYS_REVIEW -> Verdict.REVIEW
        }
}
