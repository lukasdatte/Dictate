package net.devemperor.dictate.companion.pipeline

import net.devemperor.dictate.companion.capture.AudioDeviceRef

/**
 * The side effects a [DictationReducer] transition asks for (desktop-host.md §5.4). The reducer is
 * pure — it never records, transcribes, inserts or touches the clock; it only *names* the IO, and
 * [DictationEffects] executes it (all time/UUID/AI/insert live there).
 */
sealed interface Effect {

    /** Make the warm panel visible. */
    data object ShowPanel : Effect

    /** Hide the panel back to its warm-but-hidden resting state. */
    data object HidePanel : Effect

    /** Begin capturing a new take from [device] (or the persisted/default mic when `null`). */
    data class StartCapture(val device: AudioDeviceRef?) : Effect

    data object PauseCapture : Effect
    data object ResumeCapture : Effect

    /** Abort and delete the current take's audio. */
    data object DiscardCapture : Effect

    /**
     * Stop the mic, then run the full transcribe → post-process → verdict pipeline for [sessionId] on
     * the serial job queue (§5.5/§5.6). Emits [DictationIntent] callbacks as it advances.
     */
    data class StopCaptureAndRun(val sessionId: String) : Effect

    /** Auto-insert the INSERT-verdict [text] into the foreground window and stamp `inserted_at`. */
    data class InsertText(val sessionId: String, val text: String) : Effect

    /**
     * Discard from the panel's waiting state: stamp `inserted_at` as the acknowledge **without**
     * inserting — insert and discard share one acknowledge channel (ADR-0013 §4, spec §8.5).
     */
    data class AcknowledgeDiscard(val sessionId: String) : Effect

    // ── Re-dictate (spec §8.3) ─────────────────────────────────────────────────────────────────

    /**
     * Stop the S2 mic and transcribe it (transcription-only, no post-processing): persist a
     * `REVIEW_REFINEMENT` session ([refinementSessionId], parented to [reviewSessionId]) with its
     * transcription, then dispatch [DictationIntent.RefinementTranscribed] with the text (§8.3 step 1-2).
     */
    data class RunRefinementTranscription(val refinementSessionId: String, val reviewSessionId: String) : Effect

    /**
     * Run a `ConversationContinuation` (ADR-0013 §6): load [reviewSessionId]'s persisted turns + system
     * prompt, send them plus the follow-up [followUpText] to the model, append the answer as a new turn,
     * and dispatch [DictationIntent.ReviewTurnCompleted] (§8.3 step 3).
     */
    data class RunContinuation(
        val reviewSessionId: String,
        val followUpText: String,
        /** The `REVIEW_REFINEMENT` session the follow-up came from — persisted as the turn's audit link. */
        val refinementSessionId: String?,
    ) : Effect

    /** Abort an in-progress refinement recording (Discard during re-dictate, §8.4). */
    data object CancelRefinement : Effect
}
