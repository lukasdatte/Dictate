package net.devemperor.dictate.preferences

/**
 * How the post-processing conversation turn handles an ambiguous result
 * (ADR-0013). Persisted as the [persistKey] string in [Pref.AmbiguityMode];
 * the tri-state enum + [fromPersistKey] fallback mirrors the
 * `AIProvider.fromPersistKey` idiom.
 *
 * - [ALWAYS_INSERT] — the Paket-1 behaviour: a plain transcription runs no
 *   turn, and a turn's `output` is inserted regardless of the model's verdict.
 *   Zero extra cost.
 * - [AUTO] — a turn always runs (a bare transcription too, via
 *   `PostProcessingInputs.forceTurn`); the structured `needsClarification`
 *   verdict decides insert-vs-review.
 * - [ALWAYS_REVIEW] — a turn always runs and the review panel is always shown
 *   (when the IME is visible).
 *
 * Android-free (no imports) so the domain layer (`ReviewDecision`) can depend
 * on it, mirroring the `MessageRole` precedent.
 */
enum class AmbiguityMode(val persistKey: String) {
    ALWAYS_INSERT("ALWAYS_INSERT"),
    AUTO("AUTO"),
    ALWAYS_REVIEW("ALWAYS_REVIEW");

    /** Whether this mode forces a conversation turn on a bare transcription. */
    val forcesTurn: Boolean get() = this != ALWAYS_INSERT

    companion object {
        @JvmStatic
        fun fromPersistKey(key: String?): AmbiguityMode =
            entries.find { it.persistKey == key } ?: ALWAYS_INSERT
    }
}
