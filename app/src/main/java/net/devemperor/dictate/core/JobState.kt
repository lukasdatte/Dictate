package net.devemperor.dictate.core

/**
 * Runtime state of a pipeline job for a single session.
 * Lives only in memory — not persisted to the database.
 */
sealed class JobState {
    abstract val sessionId: String

    data class Running(
        override val sessionId: String,
        val currentStepIndex: Int,
        val totalSteps: Int,
        val currentStepName: String,
        val startedAt: Long
    ) : JobState()
}

/** Type classifier for a job — used for analytics and UI decisions. */
enum class JobKind {
    RECORDING,              // Initial recording pipeline
    RESUME,                 // Short-press resend, continue from failure point
    REPROCESS_STAGING,      // Long-press, user-edited queue, full re-transcribe
    HISTORY_REPROCESS,      // From HistoryDetailActivity
    STEP_REGENERATE,        // Regenerate a single processing step
    POST_PROCESS            // Post-processing chain
}
