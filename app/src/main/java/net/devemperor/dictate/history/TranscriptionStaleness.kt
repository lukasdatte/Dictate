package net.devemperor.dictate.history

/**
 * Pure, JVM-testable predicate for the transcription-card staleness warning
 * (spec §3.4, D3).
 *
 * A transcription re-run (or a version switch) creates a new current
 * transcription version but deliberately does NOT re-run the processing chain.
 * The first processing step snapshotted its `input_text` from whatever
 * transcription was current when it ran; if the now-current transcription text
 * differs from that snapshot, the downstream steps are based on a *different*
 * transcription version and the card must warn the user (routing them to the
 * reprocess buttons — re-run/switch never mutates downstream).
 *
 * Extracting the comparison as a pure function keeps the rule unit-testable
 * without an Activity or Room (spec §6, D9).
 */
object TranscriptionStaleness {

    /**
     * Returns true when a processing chain exists whose first step was based on
     * a transcription text that differs from [currentTranscriptionText].
     *
     * @param currentTranscriptionText the text of the session's current
     *   transcription version, or null if the session has no transcription.
     * @param firstStepInputText the snapshotted `input_text` of the first
     *   processing step (chain index-ordered), or null when there is no
     *   processing chain. When null, there is nothing downstream to go stale.
     */
    fun isStale(
        currentTranscriptionText: String?,
        firstStepInputText: String?,
    ): Boolean {
        // No processing chain → nothing downstream can be stale.
        if (firstStepInputText == null) return false
        // No current transcription → cannot establish a mismatch.
        if (currentTranscriptionText == null) return false
        return currentTranscriptionText != firstStepInputText
    }
}
