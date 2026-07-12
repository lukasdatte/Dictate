package net.devemperor.dictate.ai.conversation

/**
 * The transient verdict inputs a completed conversation turn carries to the IME
 * (ADR-0013), so the IME can decide insert-vs-review. Never persisted — the
 * review decision is made once, at completion time, from the fresh wire answer.
 *
 * - [message] — the model's explanation, shown in the review panel.
 * - [needsClarification] — the model's structured verdict.
 *
 * `null` at the pipeline callback means "no turn ran / headless / reconciliation"
 * — those paths never open a review panel.
 */
data class PostProcessingReview(
    val message: String?,
    val needsClarification: Boolean
)
