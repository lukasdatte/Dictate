package net.devemperor.dictate.core

/**
 * Represents the recording lifecycle as a sealed class.
 *
 * Replaces the three boolean flags (isPreparingRecording, recordingPending,
 * recordingUsesBluetooth) with a single type-safe state. Impossible combinations
 * (e.g., preparing + paused) are structurally excluded.
 */
sealed class RecordingState {
    object Idle : RecordingState()
    data class Preparing(val useBluetooth: Boolean) : RecordingState()
    data class Active(val useBluetooth: Boolean) : RecordingState()
    object Paused : RecordingState()

    val isRecordingOrPaused: Boolean
        get() = this is Active || this is Paused
}
