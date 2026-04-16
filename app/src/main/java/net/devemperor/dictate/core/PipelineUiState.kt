package net.devemperor.dictate.core

/**
 * UI state for the pipeline progress display on record button and QWERTZ button.
 *
 * Pattern: Sealed class with a single owner-controller that holds the state, offers mutation
 * methods, and notifies consumers via callback. Click handlers check the current state instead
 * of swapping listeners.
 * Modeled after [RecordingState] + [RecordingStateController].
 *
 * Additions to this class must update [KeyboardUiController.refreshRecordButtonFromState].
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
        val autoEnterActive: Boolean,
        val hasFailure: Boolean = false
    ) : PipelineUiState()

    /**
     * The user has triggered long-press on resend, a previous keyboard session
     * has been loaded, and the user is now editing the prompt queue and/or
     * language before starting a reprocess.
     *
     * In this state:
     * - The large record button shows "Audio X:YY · Senden" and acts as a send trigger
     * - The pause button in the input row is disabled ("blind")
     * - The trash button in the input row cancels the reprocess and returns to Idle
     * - The prompt bar shows the editable queue with a language chip
     *
     * Forward-compatibility note: [selectedModel] is persisted in the state but
     * not currently editable in the UI. The data path (state → JobRequest →
     * PipelineConfig → PipelineOrchestrator) is prepared so a future UI can
     * expose a model-selector chip without touching any plumbing. Default is
     * null, which means "use the provider's default from Prefs".
     */
    data class ReprocessStaging(
        val targetSessionId: String,
        val audioDurationSeconds: Long,
        val editableQueue: List<Int>,       // prompt entity IDs
        val selectedLanguage: String?,
        val selectedModel: String? = null,  // future-proofing — not editable in UI yet
        val isStarting: Boolean = false     // becomes true after Send pressed, before Registry-Running
    ) : PipelineUiState()
}
