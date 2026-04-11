package net.devemperor.dictate.core

/**
 * UI state for the pipeline progress display on record button and QWERTZ button.
 *
 * Pattern: Sealed class with a single owner-controller that holds the state, offers mutation
 * methods, and notifies consumers via callback. Click handlers check the current state instead
 * of swapping listeners.
 * Modeled after [RecordingState] + [RecordingStateController].
 */
sealed class PipelineUiState {
    /** No pipeline active — normal prompt buttons mode. */
    object Idle : PipelineUiState()

    /** Audio being uploaded / pipeline not yet started. Button disabled, shows "Sending...". */
    object Preparing : PipelineUiState()

    /** Pipeline running — progress display active. */
    data class Running(
        val totalSteps: Int,
        val completedSteps: Int,
        val currentStepName: String,
        val autoEnterActive: Boolean
    ) : PipelineUiState()
}
