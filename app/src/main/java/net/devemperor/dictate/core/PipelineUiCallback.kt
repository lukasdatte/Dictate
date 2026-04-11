package net.devemperor.dictate.core

/**
 * Callback interface for pipeline UI state changes and timer updates.
 *
 * Mirrors the shape of `RecordingStateController.Callback`: an interface with default-method
 * members so implementing classes only override what they need. Replaces the previous
 * lambda-pair (`onPipelineUiStateChanged`/`onPipelineTimerTick`) that required `kotlin.Unit`
 * boilerplate on the Java side.
 *
 * Registered on [KeyboardUiController] via [KeyboardUiController.setCallback].
 */
@JvmDefaultWithCompatibility
interface PipelineUiCallback {
    /** Fires on actual state changes (step completed, auto-enter toggled, pipeline start/stop). */
    fun onPipelineUiStateChanged(oldState: PipelineUiState, newState: PipelineUiState) {}

    /** Fires every 100ms while the pipeline runs — for QWERTZ timer updates. */
    fun onPipelineTimerTick(state: PipelineUiState.Running, elapsedMs: Long) {}
}
