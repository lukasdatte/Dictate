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
 * **AudioFocus + Bluetooth-SCO emission (C6-IMPL-1 / B2-C6-W1 — the
 * real path).** This module's reducer emits `Effect.RequestAudioFocus`
 * / `ReleaseAudioFocus` / `StartBluetoothSco` / `StopBluetoothSco` in
 * reaction to [net.devemperor.dictate.state.RecordingState] FSM
 * transitions, observed via [onCrossModuleStateChange] (ADR-0002
 * **Mode-2** cross-module cascade → **Mode-1** own SideEffect):
 *
 * - `Idle → Preparing` and `Paused → Active` cascade
 *   [Action.AudioAction.RecordingStarted]; the reducer emits
 *   `RequestAudioFocus` (gated on `audioFocusEnabledPref`, mirroring
 *   legacy `RecordingStateController.proceedStartRecording:326`
 *   `if (audioFocusEnabled) gate.request()`; `Pref.AudioFocus` default
 *   `true`) and, when `useBluetoothMic`, `StartBluetoothSco`.
 * - `* → Idle` and `Active → Paused` cascade
 *   [Action.AudioAction.RecordingEnded]; the reducer emits
 *   `ReleaseAudioFocus` + `StopBluetoothSco` (legacy
 *   `stopRecording:150` / `cancelRecording:221` / `togglePause:168`).
 * - On the BT-mic `Preparing` wait, the SCO outcome
 *   (`OnBluetoothScoStateChanged`) is turned into an
 *   [Action.RecordingAction.ScoRouteResolved] cascade so RecordingModule
 *   fires its deferred `AllocateMediaRecorder` with the correct source.
 *
 * This restores the Spec 1 §15.1 row-3 observer arm
 * (`Recording.Preparing → AudioFocus-Request`). The earlier Phase-B S-4
 * KDoc claimed AudioFocus "is requested as part of
 * `RecordingModule.Effect.AllocateMediaRecorder` — the subsystem
 * adapter takes care of it" — that was a **stale dormant-layer
 * comment**: `RecordingHardwareAdapter.allocate` provably only sets the
 * MediaRecorder source + `prepare()`s; it requests neither audio-focus
 * nor SCO (C6-D2pre gate-RED-blocking finding C6-IMPL-1). Keeping the
 * audio-focus + SCO lifecycle entirely in this module (the `audio`
 * axis owner) is the SRP rationale the S-4 note correctly stated but
 * mis-applied.
 *
 * @see net.devemperor.dictate.state.AudioState
 * @see net.devemperor.dictate.state.Action.AudioAction
 * @see net.devemperor.dictate.state.Action.RecordingAction.ScoRouteResolved
 * @see docs/plans/2026-05-15 - dictate-cutover-completion/research/recording-audiofocus-btsco-handshake.md
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §15.1 §15.3
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

        // C6-IMPL-1 / B2-C6-W1 — recording entered an audio-capturing
        // phase. State is unchanged (the audio axis carries no
        // recording-derived field); the transition is the *effects*:
        // request audio-focus iff the user pref is on (legacy parity:
        // `Pref.AudioFocus` default true → 100% of users) and kick the
        // SCO handshake iff the BT-mic pref is on. Returning the same
        // `state` with a non-empty effect list is correct here — the
        // `null` (reducer-null → Rejected) contract is for "action not
        // relevant in this state", which is not the case: this action
        // is *always* relevant and its whole purpose is the side-effect.
        Action.AudioAction.RecordingStarted -> TransitionResult(
            nextState = state,
            sideEffects = buildList {
                if (state.audioFocusEnabledPref) add(Effect.RequestAudioFocus)
                if (state.useBluetoothMic) add(Effect.StartBluetoothSco)
            },
        )

        // C6-IMPL-1 / B2-C6-W1 — recording left the audio-capturing
        // phase (stop / cancel / pause). Release focus + stop SCO. Both
        // subsystem calls are idempotent (no-op if never acquired), so
        // emitting them unconditionally is safe and mirrors the legacy
        // unconditional `gate.abandon()` + `bluetoothScoManager.release()`
        // on stop/cancel/pause.
        Action.AudioAction.RecordingEnded -> TransitionResult(
            nextState = state,
            sideEffects = listOf(
                Effect.ReleaseAudioFocus,
                Effect.StopBluetoothSco,
            ),
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

        // ── C6-IMPL-1 / B2-C6-W1 — recording-lifecycle → audio-focus +
        // SCO (Spec 1 §15.1 row 3 observer arm, restored). ADR-0002
        // Mode-2: this module observes the RecordingState FSM transition
        // and cascades an AudioAction; the AudioModule reducer turns it
        // into the Mode-1 own SideEffect (RequestAudioFocus / etc.).
        val prevRec = prev.recording
        val nextRec = next.recording

        // Recording engaged a capturing-or-preparing phase. Detected on
        // the *engagement* edge rather than a single named transition
        // (`Idle → Preparing`) so it is robust to a synchronous,
        // re-entrant `Preparing → Active` collapsing the observed tuple
        // into `Idle → Active`: with `Dispatchers.Main.immediate` the
        // `AllocateMediaRecorder` effect's `emitAction(MediaRecorderReady)`
        // can re-enter the dispatch loop *before* this observer's
        // frozen `next` snapshot is taken, so `next` may already be
        // `Active`. The invariant we actually want is "recording was
        // not engaged before and is engaged now" — that covers
        // `Idle → Preparing`, `Idle → Active`, and the resume edge
        // `Paused → Active` (legacy `togglePause:172` re-acquires
        // focus). Cascade RecordingStarted → reducer emits
        // RequestAudioFocus (gated on pref) + StartBluetoothSco (gated
        // on useBluetoothMic).
        fun RecordingState.isEngaged(): Boolean =
            this.isActiveOrPaused || this is RecordingState.Preparing
        val recordingStarted =
            (!prevRec.isEngaged() && nextRec.isEngaged()) ||
                (prevRec is RecordingState.Paused && nextRec is RecordingState.Active)
        if (recordingStarted) {
            cascade += Action.AudioAction.RecordingStarted
        }

        // Recording disengaged: was engaged (Active/Paused/Preparing)
        // and is now Idle (stop / cancel), OR Active → Paused (pause
        // abandons focus, legacy `togglePause:168`). Cascade
        // RecordingEnded → reducer emits ReleaseAudioFocus +
        // StopBluetoothSco (idempotent at the subsystem level).
        val recordingEnded =
            (prevRec.isEngaged() && nextRec is RecordingState.Idle) ||
                (prevRec is RecordingState.Active && nextRec is RecordingState.Paused)
        if (recordingEnded) {
            cascade += Action.AudioAction.RecordingEnded
        }

        // BT-SCO Preparing handshake resolution. While a BT-mic
        // recording is awaiting SCO, the SCO outcome arrives on the
        // audio axis as OnBluetoothScoStateChanged → AudioModule.reduce
        // updates `audio.bluetoothSco.phase`. Translate the just-settled
        // phase into an Action.RecordingAction.ScoRouteResolved so
        // RecordingModule fires its deferred AllocateMediaRecorder with
        // the correct source. Connected → VOICE_COMMUNICATION,
        // Failed → MIC fallback (mirrors legacy onScoConnected /
        // onScoFailed). Fire only on the *transition* into a terminal
        // phase (Waiting/Disconnected → Connected/Failed) so a duplicate
        // broadcast does not re-cascade; the Preparing arm also guards
        // on `awaitingSco` for defence-in-depth.
        if (nextRec is RecordingState.Preparing && nextRec.awaitingSco) {
            val prevPhase = prev.audio.bluetoothSco.phase
            val nextPhase = next.audio.bluetoothSco.phase
            val justResolved = prevPhase != nextPhase &&
                (nextPhase == ScoPhase.Connected || nextPhase == ScoPhase.Failed)
            if (justResolved) {
                cascade += Action.RecordingAction.ScoRouteResolved(
                    useBluetooth = nextPhase == ScoPhase.Connected,
                )
            }
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
