package net.devemperor.dictate.companion.pipeline

import net.devemperor.dictate.ai.conversation.Verdict

/**
 * Every event the [DesktopDictationController] routes through the reducer (desktop-host.md §5.1).
 *
 * Two families: **user** intents (hotkey, panel buttons) and **pipeline-callback** intents dispatched
 * back by the effect handlers as the async job advances. Session ids are minted by the controller and
 * carried on the intent so the reducer stays pure (no UUID/clock inside it, §5.4).
 */
sealed interface DictationIntent {

    /** Hotkey / panel "record" pressed. [sessionId] is pre-minted by the controller. */
    data class StartHotkey(val sessionId: String) : DictationIntent

    data object PauseRecording : DictationIntent
    data object ResumeRecording : DictationIntent

    /** Stop the mic and hand the take to the pipeline. */
    data object StopRecording : DictationIntent

    /** Throw the current take away (from recording or a non-terminal pipeline stage). */
    data object Discard : DictationIntent

    /** Pipeline callback: transcription finished — post-processing begins. */
    data class TranscriptionCompleted(val sessionId: String) : DictationIntent

    /** Pipeline callback: post-processing finished with a shared [Verdict] (ADR-0013, §5.5 step 3). */
    data class PipelineVerdict(
        val sessionId: String,
        val verdict: Verdict,
        val output: String,
        val message: String?,
    ) : DictationIntent

    /** Pipeline callback: a step failed. [errorKey] is a stable UI/error classifier. */
    data class PipelineFailed(val sessionId: String, val errorKey: String) : DictationIntent

    /** Effect callback: the auto-insert of an INSERT verdict completed — the take is done. */
    data class InsertCompleted(val sessionId: String) : DictationIntent
}
