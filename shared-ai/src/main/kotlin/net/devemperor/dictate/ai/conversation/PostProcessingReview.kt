package net.devemperor.dictate.ai.conversation

/**
 * The transient verdict inputs a completed conversation turn carries to the IME
 * (ADR-0013), so the IME can decide insert-vs-review. Never persisted — the
 * review decision is made once, at completion time, from the fresh wire answer.
 *
 * - [message] — the model's explanation, shown in the review panel.
 * - [needsClarification] — the model's structured verdict.
 * - [ambiguityMode] — the mode SNAPSHOTTED at send-tap that decided whether to
 *   run a turn (K11). The IME applies the same mode to the verdict instead of
 *   re-reading the live pref, so a settings toggle between send and completion
 *   can no longer make `forceTurn` (orchestrator) and the insert-vs-review
 *   decision (IME) disagree. `null` → the IME falls back to the live pref (paths
 *   with no snapshot, e.g. resume).
 *
 * `null` at the pipeline callback means "no turn ran / headless / reconciliation"
 * — those paths never open a review panel.
 */
data class PostProcessingReview(
    val message: String?,
    val needsClarification: Boolean,
    val ambiguityMode: net.devemperor.dictate.preferences.AmbiguityMode? = null
)
