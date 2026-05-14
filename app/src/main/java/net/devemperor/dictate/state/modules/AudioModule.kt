// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import kotlin.reflect.KClass

/**
 * Owns the [AudioState] axis — AudioFocus, BluetoothSco, and the
 * `useBluetoothMic` user-pref mirror.
 *
 * Side-effects route through [ModuleServices.audioFocus] (request /
 * release) and [ModuleServices.bluetoothSco] (SCO mic-route start /
 * stop).
 *
 * **Cross-module cascade (Coupling-Matrix §15.1.x row "Audio"):**
 *
 * - `Audio → Recording`: when AudioFocus is **lost** mid-recording
 *   (`prev.audio.audioFocusGranted == true && next.audio.audioFocusGranted == false`
 *   AND `next.recording.isActiveOrPaused`), cascade
 *   [Action.RecordingAction.PauseRecording]. Resume on focus-regain is
 *   intentionally **not** automatic — Spec 1 §15.3 explicitly leaves
 *   that to the user (Resume button) because focus regains can be
 *   transient and an auto-resume to recording without UI feedback is
 *   surprising.
 *
 * **Note on AudioFocus-Request:** Phase-B S-4 removed the dead-code
 * `if (Idle → Preparing) { ... }`-block that previously lived here.
 * AudioFocus is requested as part of `RecordingModule.Effect.AllocateMediaRecorder`
 * (the subsystem adapter takes care of it), so there is **no**
 * Audio-side cascade for the recording-start boundary. See spec §15.3
 * "Pure-Function-Vertrag (Phase-B S-4)".
 *
 * @see net.devemperor.dictate.state.AudioState
 * @see net.devemperor.dictate.state.Action.AudioAction
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §15.3
 */
object AudioModule : DictateModule<AudioState, Action.AudioAction, AudioModule.Effect> {

    override val id: ModuleId = ModuleId.Audio
    override val actionClass: KClass<Action.AudioAction> = Action.AudioAction::class

    override fun read(global: DictateUiState): AudioState = global.audio
    override fun write(global: DictateUiState, sub: AudioState): DictateUiState =
        global.copy(audio = sub)

    override fun initialState(): AudioState = AudioState()

    sealed interface Effect : SideEffect {
        data object RequestAudioFocus : Effect
        data object ReleaseAudioFocus : Effect
        data object StartBluetoothSco : Effect
        data object StopBluetoothSco : Effect
    }

    override fun reduce(
        state: AudioState,
        action: Action.AudioAction,
        ctx: ReducerContext,
    ): TransitionResult<AudioState, Effect>? = when (action) {
        is Action.AudioAction.OnAudioFocusGrantChanged ->
            if (action.granted != state.audioFocusGranted) {
                TransitionResult(
                    nextState = state.copy(audioFocusGranted = action.granted),
                    sideEffects = emptyList(),
                )
            } else null

        is Action.AudioAction.OnBluetoothScoStateChanged -> {
            val newSco = BluetoothScoPublicState(
                phase = action.phase,
                failureReason = action.reason,
            )
            if (newSco != state.bluetoothSco) {
                TransitionResult(
                    nextState = state.copy(bluetoothSco = newSco),
                    sideEffects = emptyList(),
                )
            } else null
        }

        Action.AudioAction.ToggleAudioFocusPref -> TransitionResult(
            nextState = state.copy(audioFocusEnabledPref = !state.audioFocusEnabledPref),
            sideEffects = emptyList(),
        )
    }

    override fun runEffect(effect: Effect, services: ModuleServices): Unit = when (effect) {
        Effect.RequestAudioFocus -> services.audioFocus.request()
        Effect.ReleaseAudioFocus -> services.audioFocus.release()
        Effect.StartBluetoothSco -> services.bluetoothSco.start()
        Effect.StopBluetoothSco -> services.bluetoothSco.stop()
    }

    override fun onCrossModuleStateChange(
        prev: DictateUiState,
        next: DictateUiState,
    ): List<Action> {
        val cascade = mutableListOf<Action>()

        // AudioFocus-Loss during an active/paused recording → automatic
        // pause. Resume is user-driven (Spec 1 §15.3).
        if (prev.audio.audioFocusGranted &&
            !next.audio.audioFocusGranted &&
            next.recording.isActiveOrPaused
        ) {
            cascade += Action.RecordingAction.PauseRecording
        }

        return cascade
    }

    /**
     * Release audio focus + stop SCO on service-onDestroy. Both calls are
     * idempotent at the subsystem level.
     */
    override fun terminate(services: ModuleServices) {
        services.audioFocus.release()
        services.bluetoothSco.stop()
    }
}
