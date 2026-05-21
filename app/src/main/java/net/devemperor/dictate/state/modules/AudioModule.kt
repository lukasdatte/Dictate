// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import kotlin.reflect.KClass
import net.devemperor.dictate.preferences.Pref

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
 * **B2-VAL-W1 F-1/F-2 refinement (the BT-mic Preparing-SCO-focus
 * lifecycle).** Two precise gaps in the C6-W1 BT path were closed
 * (refines, does not contradict, the above):
 *
 * - **F-1 (Critical) — SCO phase is primed to [ScoPhase.Waiting] on the
 *   `RecordingStarted` reducer arm when `useBluetoothMic`.**
 *   `StopBluetoothSco` → `BluetoothScoManager.release()` does not emit
 *   `OnBluetoothScoStateChanged(Disconnected)` synchronously, so the
 *   phase is left stale at `Connected`. Without the prime, a back-to-back
 *   BT recording whose `startSco()` takes the already-connected
 *   early-return re-emits `Connected`, which `reduce` rejects as a no-op
 *   ⇒ the observer's `prevPhase != nextPhase` edge never fires ⇒
 *   `ScoRouteResolved` never cascades ⇒ recording hangs forever in
 *   `Preparing(awaitingSco)`. Priming makes `ScoPhase` a real state
 *   machine — every handshake starts from `Waiting`, so the terminal
 *   broadcast is always a genuine edge and the existing
 *   duplicate-broadcast / stale-resolve-after-cancel defences keep
 *   working unchanged.
 * - **F-2 (Important) — audio-focus is re-asserted on the SCO-wait-
 *   resolved edge via [Action.AudioAction.ReacquireAudioFocus].** Focus
 *   is requested early (`Idle → Preparing`); if it is *lost* during the
 *   SCO wait nothing re-acquired it (Preparing is excluded from the
 *   focus-loss → pause cascade, and `Preparing → Active` is
 *   engaged → engaged). Re-asserting focus on the `awaitingSco
 *   true → false` deferred-allocate edge restores the exact legacy
 *   timing (focus held right before `MediaRecorder.start()`).
 *
 * See `research/recording-audiofocus-btsco-handshake.md` §"Update
 * 2026-05-15 — Block-Validate B2-VAL-W1, F-1 + F-2".
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

        /**
         * Persist the user's audio-focus pref to `SharedPreferences`
         * (2026-05-21 indirection-cleanup, Chunk 3.1 — A-3 Part 1).
         *
         * Routed through [PrefPersistenceService] so the State → SP
         * direction is the same canonical seam every module uses. The
         * `PipelinePrefMirror` mirror-listener re-reads the value back
         * into the store; the resulting `current.copy(...)` is
         * structurally identical and the `MutableStateFlow`
         * distinct-emission contract absorbs the no-op — no feedback
         * loop.
         */
        data class PersistAudioFocusPref(val value: Boolean) : Effect

        /**
         * Mid-recording audio-focus runtime apply
         * (2026-05-21 indirection-cleanup, Chunk 3.2 — A-3 Part 2).
         *
         * Emitted when the user toggles `audioFocusEnabledPref` **during**
         * an `Active` recording and the new value differs from the
         * already-held focus state. Replaces the legacy
         * `RecordingStateController.setAudioFocusRuntime(enabled)`
         * imperative path — the click-handler dispatches and this
         * module's cross-module observer translates the recording-state
         * coupling into a Mode-1 effect.
         *
         * - `enabled = true`: request audio-focus (idempotent at the
         *   `AudioManager` level — re-requesting the same focus-request
         *   is a safe no-op).
         * - `enabled = false`: release audio-focus (idempotent —
         *   abandon-after-abandon is a no-op).
         *
         * The effect is gated to `Recording.Active` by the observer that
         * emits it — `runEffect` itself is a pure forward to the
         * subsystem (no state inspection inside the effect handler).
         */
        data class ApplyAudioFocusRuntime(val enabled: Boolean) : Effect
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

        Action.AudioAction.ToggleAudioFocusPref -> {
            // 2026-05-21 indirection-cleanup A-3 — replace the legacy
            // imperative path inside `DictateInputMethodService.onAudioFocusToggled`
            // (SP-write + setAudioFocusRuntime + refreshAudioFocusIcon-Twin)
            // with reducer-emitted Effects. The click-handler dispatches;
            // this arm flips the pref bit and emits:
            //   1. PersistAudioFocusPref — `SharedPreferences.AudioFocus`
            //      gets the new value (Chunk 3.1).
            //   2. ApplyAudioFocusRuntime — but only when a recording is
            //      currently Active AND the new pref state differs from
            //      the already-held focus state, so an idle toggle does
            //      not touch the live AudioManager. Mirrors legacy
            //      `RecordingStateController.setAudioFocusRuntime` gating
            //      (Chunk 3.2).
            // The edit-bar audio-focus-icon twin renders reactively in
            // Chunk 3.3 — no Effect from the reducer.
            val nextPref = !state.audioFocusEnabledPref
            val live = ctx.global.recording is RecordingState.Active
            val effects: List<Effect> = buildList {
                add(Effect.PersistAudioFocusPref(nextPref))
                if (live && nextPref != state.audioFocusGranted) {
                    add(Effect.ApplyAudioFocusRuntime(nextPref))
                }
            }
            TransitionResult(
                nextState = state.copy(audioFocusEnabledPref = nextPref),
                sideEffects = effects,
            )
        }

        // C6-IMPL-1 / B2-C6-W1 — recording entered an audio-capturing
        // phase. The transition is the *effects*: request audio-focus
        // iff the user pref is on (legacy parity: `Pref.AudioFocus`
        // default true → 100% of users) and kick the SCO handshake iff
        // the BT-mic pref is on. Returning a non-`null` result is
        // correct here even though it is "effects only" for the non-BT
        // path — the `null` (reducer-null → Rejected) contract is for
        // "action not relevant in this state", which is not the case:
        // this action is *always* relevant and its whole purpose is the
        // side-effect.
        //
        // **B2-VAL-W1 F-1 — prime the SCO phase to `Waiting` on the
        // BT-mic path.** `StopBluetoothSco` → `BluetoothScoManager.
        // release()` does NOT synchronously emit
        // `OnBluetoothScoStateChanged(Disconnected)` (the system
        // broadcast is async and often never arrives in the recording's
        // lifetime / never in tests), so `bluetoothSco.phase` is left
        // **stale at `Connected`** from the prior BT session. Without
        // this prime, a back-to-back BT recording whose `startSco()`
        // takes the already-connected early-return
        // (`BluetoothScoManager.kt:122-126`, also skipping the 2500 ms
        // timeout) re-emits `OnBluetoothScoStateChanged(Connected)`,
        // which `reduce` rejects as a no-op (`Connected == Connected`)
        // ⇒ `prevPhase == nextPhase` ⇒ the observer's edge-trigger never
        // fires ⇒ `ScoRouteResolved` never cascades ⇒ recording hangs
        // forever in `Preparing(awaitingSco=true)` with no audio, no
        // error, no timeout recovery. Resetting the phase to `Waiting`
        // in this *same* reducer pass (synchronous, completes before
        // `runEffect(StartBluetoothSco)` runs) makes `ScoPhase` a
        // genuine state machine: every handshake provably starts from
        // `Waiting`, so the subsequent terminal broadcast is *always* a
        // real `Waiting → Connected|Failed` edge and the existing
        // edge-trigger (and its duplicate-broadcast /
        // stale-resolve-after-cancel defences) keep working unchanged.
        // Mode-1 (own-axis write + own effect); no Mode-3. The non-BT
        // path emits no SCO effect and must NOT touch the phase.
        Action.AudioAction.RecordingStarted -> TransitionResult(
            nextState = if (state.useBluetoothMic) {
                state.copy(
                    bluetoothSco = BluetoothScoPublicState(
                        phase = ScoPhase.Waiting,
                        failureReason = null,
                    ),
                )
            } else {
                state
            },
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

        // B2-VAL-W1 F-2 — re-assert audio-focus on the BT-mic
        // SCO-wait-resolved edge. Focus-only: NO StartBluetoothSco (the
        // handshake just resolved), NO phase prime (would corrupt the
        // resolved Connected/Failed phase). Gated on the pref exactly
        // like RecordingStarted (legacy `if (audioFocusEnabled)
        // gate.request()`). State unchanged — effect is the point.
        Action.AudioAction.ReacquireAudioFocus -> TransitionResult(
            nextState = state,
            sideEffects = buildList {
                if (state.audioFocusEnabledPref) add(Effect.RequestAudioFocus)
            },
        )
    }

    override fun runEffect(effect: Effect, services: ModuleServices): Unit = when (effect) {
        Effect.RequestAudioFocus -> services.audioFocus.request()
        Effect.ReleaseAudioFocus -> services.audioFocus.release()
        Effect.StartBluetoothSco -> services.bluetoothSco.start()
        Effect.StopBluetoothSco -> services.bluetoothSco.stop()
        // 2026-05-21 indirection-cleanup Chunk 3.1 — route the State → SP
        // write through the canonical PrefPersistenceService seam. The
        // mirror-listener will re-read the value back into the store
        // (StateFlow distinct-emission absorbs the no-op).
        is Effect.PersistAudioFocusPref ->
            services.prefs.persist(Pref.AudioFocus, effect.value)

        // 2026-05-21 indirection-cleanup Chunk 3.2 — mid-recording focus
        // apply, replaces `RecordingStateController.setAudioFocusRuntime`.
        // `request()` / `release()` are idempotent at the AudioManager
        // layer; the reducer gates emission to the cases where the live
        // focus state actually needs to change.
        is Effect.ApplyAudioFocusRuntime ->
            if (effect.enabled) services.audioFocus.request()
            else services.audioFocus.release()
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

        // B2-VAL-W1 F-2 — the SCO-wait-resolved edge. A BT-mic recording
        // requests focus on `Idle → Preparing(awaitingSco=true)`, then
        // *waits* (up to 2500 ms) for the SCO handshake. If audio-focus
        // is LOST during that wait, nothing re-acquires it: the
        // focus-loss → pause cascade is (correctly) gated on
        // `isActiveOrPaused` which excludes `Preparing`, and the later
        // `Preparing → Active` is engaged → engaged so the
        // `recordingStarted` engagement-edge clause does not re-fire.
        // Net (pre-fix): a BT recording could reach Active having lost
        // focus — other apps duck over it. Legacy did NOT have this
        // window: it requested focus in `proceedStartRecording`, i.e.
        // *after* the SCO wait, right before `MediaRecorder.start()`.
        // On the `awaitingSco true → false` deferred-allocate transition
        // (the SCO-wait-resolved edge that `ScoRouteResolved` produces)
        // we re-assert focus via the focus-only
        // [Action.AudioAction.ReacquireAudioFocus] — restoring the exact
        // legacy timing (focus held right before capture) while NOT
        // re-kicking the SCO handshake or re-priming the SCO phase
        // (which `RecordingStarted` does and which would corrupt the
        // just-resolved `Connected`/`Failed` phase). `request()` is
        // idempotent (delegates to `AudioManager.requestAudioFocus` —
        // re-requesting the same `AudioFocusRequest` is a safe Android
        // no-op / re-grant), so the kept early `Idle → Preparing`
        // request is harmless. Mutually exclusive with `recordingStarted`
        // (`Preparing → Preparing` vs `!engaged → engaged`).
        val scoWaitResolved =
            prevRec is RecordingState.Preparing && prevRec.awaitingSco &&
                nextRec is RecordingState.Preparing && !nextRec.awaitingSco
        if (scoWaitResolved) {
            cascade += Action.AudioAction.ReacquireAudioFocus
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
