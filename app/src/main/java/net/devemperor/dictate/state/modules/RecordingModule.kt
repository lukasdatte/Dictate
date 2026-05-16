// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package. The sub-directory keeps modules grouped together
// in the file tree without paying the per-file package cost.
package net.devemperor.dictate.state

import java.io.File
import kotlin.reflect.KClass

/**
 * Owns the [RecordingState] FSM (Idle / Preparing / Active / Paused) and
 * orchestrates the `MediaRecorder` lifecycle via [ModuleServices.recordingHardware],
 * [ModuleServices.recordingTimer], [ModuleServices.amplitudeStream], and
 * [ModuleServices.borderGlow].
 *
 * **Pure-reducer invariant (F1+F2, ADR-0001):** [reduce] never touches
 * hardware; every Allocate/Start/Stop/Pause/Resume/Delete is emitted as
 * a [Effect] and executed in [runEffect] afterwards.
 *
 * **Pre-Dispatch-Allocator pattern (R.2 / Spec 1 §4.11):** the caller of
 * `Action.RecordingAction.StartRecording(target, audioFile)` builds the
 * `audioFile` via [ModuleServices.audioFileFactory] before dispatching;
 * the reducer carries the file into [RecordingState.Preparing] /
 * [RecordingState.Active] / [RecordingState.Paused] so subsequent
 * effects (and the cancel-cascade audio-file cleanup) operate on the
 * same path without re-reading `cacheDir`.
 *
 * **Cross-module cascade (Spec 1 §15.2 + Coupling-Matrix §15.1.x row
 * "Recording"):** the only cascade this module emits is
 * [Action.OverlayAction.ResetSuppressBit] on the
 * `Idle → Preparing` boundary — clearing the user's previous
 * "suppress auto-overlay" choice at the start of a *new* recording
 * session. Cancel-on-Preparing does NOT reset (the boundary check is
 * strictly forward).
 *
 * **Self-cascade is required** (KG-RSB-2-Fix, see [DictateOrchestrator]
 * Step 5 ASCII-box): RecordingModule observes its own `Idle → Preparing`
 * transition and emits a cross-module action to OverlayModule.
 *
 * **Failure handling ([reduceFailure]):** `AllocateMediaRecorder` may
 * fail (cache-wipe between allocate and prepare, MIC-permission revoked
 * mid-prepare, etc.). Without a failure arm the Preparing state would
 * persist forever. The arm rolls back to [RecordingState.Idle] and
 * emits a release + best-effort file delete. `StopMediaRecorder` may
 * fail (`MediaRecorder.stop()` throws on too-short streams); the arm
 * also rolls back to Idle but **keeps** the audio file (might be
 * partially valid).
 *
 * **Effect-identifier matching (Spec 2 §3.3, Phase-C C-3):** the
 * orchestrator captures `effect.toString()` for the
 * [Action.EffectFailure.effect] field. `AllocateMediaRecorder` is a
 * `data class` — its `toString()` includes the args, so the failure
 * arm uses `startsWith("AllocateMediaRecorder(")`. `StopMediaRecorder`
 * is an `object` — its `toString()` is the simple-name, exact-equality
 * match is correct.
 *
 * @see net.devemperor.dictate.state.RecordingState
 * @see net.devemperor.dictate.state.Action.RecordingAction
 * @see net.devemperor.dictate.state.Action.OverlayAction.ResetSuppressBit
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"Pure-Reducer Invariant"
 * @see docs/decisions/0002-state-cross-module-cascade.md §"Self-cascade"
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §15.2
 */
object RecordingModule : DictateModule<RecordingState, Action.RecordingAction, RecordingModule.Effect> {

    override val id: ModuleId = ModuleId.Recording
    override val actionClass: KClass<Action.RecordingAction> = Action.RecordingAction::class

    override fun read(global: DictateUiState): RecordingState = global.recording
    override fun write(global: DictateUiState, sub: RecordingState): DictateUiState =
        global.copy(recording = sub)

    override fun initialState(): RecordingState = RecordingState.Idle

    /**
     * Module-local side-effect surface. Sealed for compile-time
     * exhaustivity in [runEffect].
     *
     * `data class` variants (e.g. [AllocateMediaRecorder], [DeleteAudioFile],
     * [StartTimer]) include their args in `toString()` — matching them in
     * [reduceFailure] uses `startsWith("Name(")`.
     */
    sealed interface Effect : SideEffect {

        /**
         * Allocate the MediaRecorder + storage for a new recording. Asynchronous
         * — the subsystem reports completion via
         * `Action.RecordingAction.MediaRecorderReady`. Until then the
         * state stays in [RecordingState.Preparing].
         *
         * @property target the insertion destination captured at start time.
         * @property useBluetooth whether to wire the SCO mic route.
         * @property audioFile pre-allocated cache file path (R.2).
         */
        data class AllocateMediaRecorder(
            val target: InsertionTarget,
            val useBluetooth: Boolean,
            val audioFile: File,
        ) : Effect

        /**
         * Begin recording on an already-allocated MediaRecorder
         * (B3-VAL-W1 F-10 + RecordingHardwareSubsystem KDoc). Emitted
         * on the Preparing → Active reducer arm together with the
         * timer/amplitude/glow start effects. Without it the
         * subsystem-level `start()` never runs in the orchestrator-
         * driven flow (Phase 1 IME side-path still drives
         * MediaRecorder directly; this seam is dormant until B5/B6
         * LayoutCatalog routes through orchestrator, at which point
         * the omission would surface as silent empty audio files).
         */
        data object StartMediaRecorder : Effect

        data object ReleaseMediaRecorder : Effect
        data object PauseMediaRecorder : Effect
        data object ResumeMediaRecorder : Effect
        data object StopMediaRecorder : Effect

        /** Best-effort delete of an audio file (cancel / failure cleanup). */
        data class DeleteAudioFile(val file: File) : Effect

        /**
         * Start the recording-timer.
         *
         * **Note (Phase-C C-5 plan deviation, small):** Spec 1 §15.2's
         * sketch passed `initialElapsedMs` here for a Pause→Resume
         * round-trip restart. The C4-pinned [RecordingTimerSubsystem]
         * has `pause()` + `resume()` semantics, so the resume path
         * carries elapsed-state inside the subsystem — no parameter is
         * needed on the start effect. Kept as a `data object` for
         * symmetry with the other timer effects.
         */
        data object StartTimer : Effect
        data object PauseTimer : Effect
        data object ResumeTimer : Effect
        data object StopTimer : Effect

        data object StartAmplitudeStream : Effect
        data object StopAmplitudeStream : Effect

        data object StartBorderGlow : Effect
        data object PauseBorderGlow : Effect
        data object ResumeBorderGlow : Effect
        data object StopBorderGlow : Effect

        /**
         * F-2 + F-10 — re-entrant trigger of the pipeline after
         * `StopRecordingAndSend`.
         *
         * Emitted on the `Active/Paused → Idle` reducer arm for
         * `StopRecordingAndSend` (which carries no payload). The
         * `sessionId` is read off the live FSM
         * (`Active.sessionId` / `Paused.sessionId`) — the same id minted
         * at `StartRecording` (F-10, Epic §4 Block A2). The effect
         * handler calls
         * `services.emitAction(Action.PipelineAction.TriggerPipeline(sessionId, audioFile))`
         * — re-entering the dispatch loop asynchronously via [emitAction]
         * (which posts to the orchestrator's scope, not synchronously
         * re-entering the current dispatch).
         *
         * This replaces the documented-but-never-wired cross-module
         * cascade-via-observer pattern. The observer route was rejected
         * because an `onCrossModuleStateChange` hook only sees prev/next
         * state, not the triggering action — fine now that the sessionId
         * lives in `RecordingState` (F-10), but the Effect+emitAction
         * route is still the documented async re-entry mechanism per
         * ADR-0001 §"Required mechanics" #6 (it also carries the
         * `audioFile`, which a pure prev/next observer would have to
         * re-derive).
         */
        data class EmitPipelineTrigger(
            val sessionId: String,
            val audioFile: File,
        ) : Effect

        /**
         * Drive the persistent FGS notification for the **recording**
         * phase (Spec 1 §7.6 Recording-Active / Recording-Paused rows;
         * C5 / B2-block-report C4-IMPL-1).
         *
         * **Why RecordingModule emits notification effects at all.**
         * Before C5 only [PipelineModule] drove the FGS notification
         * (it owned the only on-path lifecycle). C4 built the coordinator
         * to render `NotificationStatus.Recording`, but no module emitted
         * it — the recording-phase notification only existed on the
         * legacy path. C5 flips the IME recording trigger to dispatch, so
         * the recording FSM is now the on-path owner of the
         * recording-phase notification and must drive the coordinator
         * directly (the same `notificationCoordinator` subsystem
         * [PipelineModule] uses — single coordinator, two FSM owners for
         * their respective phases). The Recording→Pipeline hand-off is
         * seamless: `StopRecordingAndSend` does NOT dismiss; the
         * `EmitPipelineTrigger → TriggerPipeline` cascade has
         * [PipelineModule] immediately re-`show()` a `Pipeline` status on
         * the same NOTIF_ID (no dismiss/re-post flicker).
         *
         * `data class` — its `toString()` includes the status arg; no
         * `reduceFailure` arm matches it (notification updates are
         * best-effort and the coordinator already swallows `notify`
         * failures, C4-B2).
         */
        data class UpdateNotification(val status: NotificationStatus) : Effect

        /**
         * Dismiss the FGS recording notification — emitted when the
         * recording ends **without** handing off to the pipeline
         * (`StopRecording` discard, `CancelRecording`). The
         * `StopRecordingAndSend` arm deliberately does NOT emit this:
         * the pipeline trigger re-`show()`s on the same id (see
         * [UpdateNotification] KDoc).
         */
        data object DismissNotification : Effect
    }

    override fun reduce(
        state: RecordingState,
        action: Action.RecordingAction,
        ctx: ReducerContext,
    ): TransitionResult<RecordingState, Effect>? = when (state) {
        is RecordingState.Idle -> when (action) {
            is Action.RecordingAction.StartRecording -> {
                // F-7: fail-fast on a blank sessionId. B1 callers all mint
                // a UUID via `newSessionId()`, but B3 will route the IME's
                // `preAllocatedId` in instead — a regression there (blank
                // id) would silently re-introduce the exact F-10
                // empty-string sentinel this block removes, propagating
                // through the whole FSM into `EmitPipelineTrigger` with no
                // fail-fast. The FSM is the single source of the id
                // (F-10) — enforce the non-empty invariant at its entry
                // point rather than relying on every future caller's
                // discipline. B3 (recording-trigger cutover) is the
                // contract owner for supplying a non-blank id.
                require(action.sessionId.isNotBlank()) {
                    "F-10: StartRecording.sessionId must be non-blank"
                }
                if (ctx.global.audio.useBluetoothMic) {
                    // C6-IMPL-1 / B2-C6-W1 — BT-mic path: DEFER the
                    // MediaRecorder allocation until the SCO route
                    // resolves. Allocating with `useBluetooth=true`
                    // (→ VOICE_COMMUNICATION) before SCO is connected
                    // silently records the *phone* mic, not the BT
                    // headset (gate-RED silent-quality-loss). The SCO
                    // handshake is kicked by AudioModule's observer
                    // (it emits Effect.StartBluetoothSco on this
                    // Idle → Preparing transition); when SCO connects
                    // or fails/times-out, AudioModule cascades
                    // Action.RecordingAction.ScoRouteResolved, whose
                    // Preparing arm fires the now-correctly-sourced
                    // AllocateMediaRecorder. Mirrors legacy
                    // RecordingStateController.startRecording:134-139 →
                    // onScoConnected/onScoFailed:300-321.
                    TransitionResult(
                        nextState = RecordingState.Preparing(
                            useBluetooth = true,
                            audioFile = action.audioFile,
                            // F-10 — FSM is the single source of the id.
                            sessionId = action.sessionId,
                            awaitingSco = true,
                            // Carried through the SCO wait for the
                            // deferred AllocateMediaRecorder.
                            target = action.target,
                        ),
                        // No AllocateMediaRecorder yet — it fires on
                        // ScoRouteResolved. AudioModule's RecordingStarted
                        // cascade emits Effect.StartBluetoothSco +
                        // Effect.RequestAudioFocus.
                        sideEffects = emptyList(),
                    )
                } else {
                    // Non-BT path — unchanged: allocate immediately
                    // (MIC source), no SCO wait. Audio-focus is still
                    // requested via AudioModule's RecordingStarted
                    // cascade on this Idle → Preparing transition.
                    TransitionResult(
                        nextState = RecordingState.Preparing(
                            useBluetooth = false,
                            audioFile = action.audioFile,
                            // F-10 — the FSM is the single source of the
                            // session id; carry the caller-minted UUID.
                            sessionId = action.sessionId,
                            awaitingSco = false,
                            target = null,
                        ),
                        sideEffects = listOf(
                            Effect.AllocateMediaRecorder(
                                target = action.target,
                                useBluetooth = false,
                                audioFile = action.audioFile,
                            ),
                        ),
                    )
                }
            }
            // F1 / ADR-0001 §"Pure-Reducer Invariant": other actions are
            // not meaningful when no recording is in flight (e.g. user
            // tapping Stop while Idle). Returning `null` becomes
            // DispatchOutcome.Rejected("reducer-null") — that's the
            // correct semantic outcome, not a bug.
            else -> null
        }

        is RecordingState.Preparing -> when (action) {
            is Action.RecordingAction.MediaRecorderReady -> TransitionResult(
                nextState = RecordingState.Active(
                    useBluetooth = state.useBluetooth,
                    audioFile = state.audioFile,
                    sessionId = state.sessionId,
                ),
                sideEffects = listOf(
                    // B3-VAL-W1 F-10 — start MediaRecorder before
                    // peripheral effects. Order matters: timer +
                    // amplitude + glow are UI-only; the actual audio
                    // capture begins with `start()`.
                    Effect.StartMediaRecorder,
                    Effect.StartTimer,
                    Effect.StartAmplitudeStream,
                    Effect.StartBorderGlow,
                    // C5 / C4-IMPL-1 — recording is now actually
                    // capturing audio; surface the §7.6 Recording-Active
                    // FGS notification. Emitted on Preparing → Active (not
                    // on StartRecording → Preparing) so the notification
                    // appears only once the recorder is confirmed alive
                    // (a prepare failure rolls back to Idle via
                    // reduceFailure before this fires).
                    Effect.UpdateNotification(
                        NotificationStatus.Recording(state.sessionId),
                    ),
                ),
            )
            // C6-IMPL-1 / B2-C6-W1 — the SCO route resolved while we
            // were waiting (BT-mic path only). Fire the deferred
            // MediaRecorder allocation with the source that matches the
            // actual SCO outcome: VOICE_COMMUNICATION iff SCO connected
            // (`action.useBluetooth == true`), MIC fallback on
            // SCO-fail/timeout. Mirrors legacy
            // RecordingStateController.onScoConnected/onScoFailed →
            // proceedStartRecording(VOICE_COMMUNICATION | MIC).
            //
            // Guard `awaitingSco`: a stale/duplicate ScoRouteResolved
            // (e.g. a late SCO broadcast after we already allocated)
            // must be a no-op, not a second AllocateMediaRecorder.
            //
            // B2-VAL-W1 F-7 — `target` is non-null exactly on the
            // awaitingSco path (set at StartRecording's BT-mic branch;
            // see DictateUiState.Preparing.target KDoc). The earlier
            // `?: InsertionTarget.INPUT_CONNECTION` silently masked any
            // future regression that reaches this arm with
            // `awaitingSco=true && target==null` — a convention-only
            // invariant. Make it load-bearing with `requireNotNull`,
            // consistent with this file's established F-7 fail-fast
            // philosophy on the analogous `sessionId` invariant
            // (StartRecording `require(sessionId.isNotBlank())`).
            is Action.RecordingAction.ScoRouteResolved ->
                if (state.awaitingSco) {
                    TransitionResult(
                        nextState = RecordingState.Preparing(
                            useBluetooth = action.useBluetooth,
                            audioFile = state.audioFile,
                            sessionId = state.sessionId,
                            awaitingSco = false,
                            target = null,
                        ),
                        sideEffects = listOf(
                            Effect.AllocateMediaRecorder(
                                target = requireNotNull(state.target) {
                                    "ScoRouteResolved on awaitingSco " +
                                        "Preparing requires non-null target"
                                },
                                useBluetooth = action.useBluetooth,
                                audioFile = state.audioFile,
                            ),
                        ),
                    )
                } else {
                    null
                }
            Action.RecordingAction.CancelRecording -> TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.ReleaseMediaRecorder,
                    Effect.DeleteAudioFile(state.audioFile),
                    // Idempotent — Preparing never showed the recording
                    // notification (it appears on Preparing → Active), but
                    // cancelling mid-prepare must leave no orphan FGS
                    // notification if a prior cycle's one lingered.
                    Effect.DismissNotification,
                ),
            )
            else -> null
        }

        is RecordingState.Active -> when (action) {
            Action.RecordingAction.PauseRecording -> TransitionResult(
                nextState = RecordingState.Paused(
                    useBluetooth = state.useBluetooth,
                    audioFile = state.audioFile,
                    sessionId = state.sessionId,
                ),
                sideEffects = listOf(
                    Effect.PauseMediaRecorder,
                    Effect.PauseTimer,
                    Effect.PauseBorderGlow,
                    Effect.StopAmplitudeStream,
                    // C5 — §7.6 Recording-Paused row: swap the action set
                    // to [Resume][Stopp][Senden] (same NOTIF_ID, no
                    // dismiss/re-post).
                    Effect.UpdateNotification(
                        NotificationStatus.Paused(state.sessionId),
                    ),
                ),
            )
            Action.RecordingAction.StopRecording -> TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.StopMediaRecorder,
                    Effect.StopTimer,
                    Effect.StopBorderGlow,
                    Effect.StopAmplitudeStream,
                    // Stop-without-send: recording discarded, no pipeline
                    // hand-off — tear the notification down.
                    Effect.DismissNotification,
                ),
            )
            // G2 / A1 — RECORD long-press from Active (the legacy
            // `onRecordLongClicked` Active/Paused branch:
            // `autoSwitchKeyboard = true; stopRecording()`,
            // DictateInputMethodService.java:3264-3266). The FSM half is a
            // *discard* stop, identical to StopRecording — the legacy
            // `stopRecording()` it called discards rather than sends. The
            // `autoSwitchKeyboard` one-shot is an IME-side affordance (not
            // FSM state); it is wired IME-side in CR4 when the new
            // long-press path goes live (render-path-cutover.md §7 A1, the
            // OnRecordLongPress KDoc). Reusing the StopRecording effect set
            // keeps the cleanup semantics in one place (DRY).
            Action.RecordingAction.OnRecordLongPress -> TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.StopMediaRecorder,
                    Effect.StopTimer,
                    Effect.StopBorderGlow,
                    Effect.StopAmplitudeStream,
                    Effect.DismissNotification,
                ),
            )
            Action.RecordingAction.StopRecordingAndSend -> TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.StopMediaRecorder,
                    Effect.StopTimer,
                    Effect.StopBorderGlow,
                    Effect.StopAmplitudeStream,
                    // F-2 + F-10 — emit TriggerPipeline async so the
                    // pipeline takes over once recording is stopped. The
                    // sessionId comes from the live FSM (the same id
                    // minted at StartRecording), not an action payload.
                    Effect.EmitPipelineTrigger(
                        sessionId = state.sessionId,
                        audioFile = state.audioFile,
                    ),
                ),
            )
            Action.RecordingAction.CancelRecording -> TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.StopMediaRecorder,
                    Effect.StopTimer,
                    Effect.StopBorderGlow,
                    Effect.StopAmplitudeStream,
                    Effect.DeleteAudioFile(state.audioFile),
                    // Cancel from Active: discard + remove the notification.
                    Effect.DismissNotification,
                ),
            )
            else -> null
        }

        is RecordingState.Paused -> when (action) {
            Action.RecordingAction.ResumeRecording -> TransitionResult(
                nextState = RecordingState.Active(
                    useBluetooth = state.useBluetooth,
                    audioFile = state.audioFile,
                    sessionId = state.sessionId,
                ),
                sideEffects = listOf(
                    Effect.ResumeMediaRecorder,
                    Effect.ResumeTimer,
                    Effect.ResumeBorderGlow,
                    Effect.StartAmplitudeStream,
                    // C5 — back to §7.6 Recording-Active ([Pause]…) on
                    // resume.
                    Effect.UpdateNotification(
                        NotificationStatus.Recording(state.sessionId),
                    ),
                ),
            )
            // Issue 2.0.8 — Paused.Stop / Paused.Cancel are real reducer arms,
            // not TODO stubs. Both transition through `MediaRecorder.stop()`
            // (paused recorders still need stop + release before reuse).
            Action.RecordingAction.StopRecording -> TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.StopMediaRecorder,
                    Effect.StopTimer,
                    Effect.StopBorderGlow,
                    // Stop-without-send from Paused: discard + dismiss.
                    Effect.DismissNotification,
                ),
            )
            // G2 / A1 — RECORD long-press from Paused. Same discard-stop
            // semantics as the Active arm (see its KDoc); mirrors the
            // Paused StopRecording effect set (no StopAmplitudeStream —
            // Paused already stopped the amplitude stream on the
            // Active → Paused transition).
            Action.RecordingAction.OnRecordLongPress -> TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.StopMediaRecorder,
                    Effect.StopTimer,
                    Effect.StopBorderGlow,
                    Effect.DismissNotification,
                ),
            )
            Action.RecordingAction.StopRecordingAndSend -> TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.StopMediaRecorder,
                    Effect.StopTimer,
                    Effect.StopBorderGlow,
                    // F-2 + F-10 — emit TriggerPipeline async (Paused
                    // path). The sessionId comes from the live FSM.
                    Effect.EmitPipelineTrigger(
                        sessionId = state.sessionId,
                        audioFile = state.audioFile,
                    ),
                ),
            )
            Action.RecordingAction.CancelRecording -> TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.StopMediaRecorder,
                    Effect.StopTimer,
                    Effect.StopBorderGlow,
                    Effect.DeleteAudioFile(state.audioFile),
                    // Cancel from Paused: discard + remove the notification.
                    Effect.DismissNotification,
                ),
            )
            else -> null
        }
    }

    override fun runEffect(effect: Effect, services: ModuleServices): Unit = when (effect) {
        is Effect.AllocateMediaRecorder ->
            services.recordingHardware.allocate(effect.target, effect.useBluetooth, effect.audioFile)
        // B3-VAL-W1 F-10 — bridge the new Effect.StartMediaRecorder.
        Effect.StartMediaRecorder -> services.recordingHardware.start()
        Effect.ReleaseMediaRecorder -> services.recordingHardware.release()
        Effect.PauseMediaRecorder -> services.recordingHardware.pause()
        Effect.ResumeMediaRecorder -> services.recordingHardware.resume()
        Effect.StopMediaRecorder -> services.recordingHardware.stop()
        is Effect.DeleteAudioFile -> {
            effect.file.delete()
            Unit
        }
        Effect.StartTimer -> services.recordingTimer.start()
        Effect.PauseTimer -> services.recordingTimer.pause()
        Effect.ResumeTimer -> services.recordingTimer.resume()
        Effect.StopTimer -> services.recordingTimer.reset()
        Effect.StartAmplitudeStream -> services.amplitudeStream.start()
        Effect.StopAmplitudeStream -> services.amplitudeStream.stop()
        Effect.StartBorderGlow -> services.borderGlow.start()
        // BorderGlowSubsystem (C4 contract) has only start/stop — pause /
        // resume map to stop / start at the subsystem boundary so the
        // module-effect surface still expresses the user-visible
        // semantics (Pause animation flat, Resume animation glow back).
        Effect.PauseBorderGlow -> services.borderGlow.stop()
        Effect.ResumeBorderGlow -> services.borderGlow.start()
        Effect.StopBorderGlow -> services.borderGlow.stop()
        // F-2 — async re-entry; orchestrator dispatches TriggerPipeline
        // on the orchestrator's scope (Main.immediate in production).
        is Effect.EmitPipelineTrigger -> services.emitAction(
            Action.PipelineAction.TriggerPipeline(
                sessionId = effect.sessionId,
                audioFile = effect.audioFile,
            ),
        )
        // C5 — recording-phase FGS notification (Spec 1 §7.6). Same
        // coordinator subsystem PipelineModule drives for the pipeline
        // phase; the coordinator swallows `notify` failures (C4-B2) so a
        // missing POST_NOTIFICATIONS grant cannot surface as an
        // EffectFailure that cascade-cancels an active recording.
        is Effect.UpdateNotification -> services.notificationCoordinator.show(effect.status)
        Effect.DismissNotification -> services.notificationCoordinator.dismiss()
    }

    /**
     * Failure recovery for [Effect.AllocateMediaRecorder] (cache-wipe,
     * MIC-permission revoked mid-prepare, etc.) and [Effect.StopMediaRecorder]
     * (too-short stream throw).
     *
     * Other effects are idempotent system calls or best-effort file deletes —
     * no rollback semantics needed, default `null` (→ `Rejected`) is correct.
     *
     * @see Action.EffectFailure
     */
    override fun reduceFailure(
        state: RecordingState,
        failure: Action.EffectFailure,
        ctx: ReducerContext,
    ): TransitionResult<RecordingState, Effect>? = when {
        // Allocate failure during Preparing: roll back to Idle. The audio
        // file is either absent (allocate didn't reach the file-creation
        // step) or a 0-byte placeholder (prepare threw mid-write); the
        // cache-cleanup path handles both as "orphan".
        failure.effect.startsWith("AllocateMediaRecorder(") && state is RecordingState.Preparing ->
            TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    // Idempotent — no-op if allocate never returned a recorder.
                    Effect.ReleaseMediaRecorder,
                    Effect.DeleteAudioFile(state.audioFile),
                ),
            )

        // Stop failure during Active / Paused: roll back to Idle, keep the
        // file (it may be partially valid; user can decide what to do via
        // recovery UI). `StopMediaRecorder` is an `object` — exact equality.
        failure.effect == "StopMediaRecorder" &&
                (state is RecordingState.Active || state is RecordingState.Paused) ->
            TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.ReleaseMediaRecorder,
                    Effect.StopTimer,
                    Effect.StopBorderGlow,
                ),
            )

        // B3-VAL-W1 F-11 — Start failure during Active: roll back to
        // Idle, release the recorder, delete the (likely 0-byte) file.
        // `MediaRecorder.start()` throws on a stale prepare / native
        // resource conflict; without this arm the FSM gets stuck in
        // Active while the hardware is broken.
        failure.effect == "StartMediaRecorder" && state is RecordingState.Active ->
            TransitionResult(
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.ReleaseMediaRecorder,
                    Effect.DeleteAudioFile(state.audioFile),
                    Effect.StopTimer,
                    Effect.StopBorderGlow,
                    Effect.StopAmplitudeStream,
                ),
            )

        else -> null
    }

    /**
     * Cross-module observer: emits [Action.OverlayAction.ResetSuppressBit]
     * on the `Idle → Preparing` session-start boundary.
     *
     * **Self-cascade contract:** RecordingModule observes its OWN state
     * transition and emits a cross-module action. The KG-RSB-2-Fix
     * (2026-05-11) deliberately removed the orchestrator's self-filter
     * to allow this — see [DictateOrchestrator] Step 5 ASCII-box.
     *
     * **Why here and not in OverlayModule?** The trigger is a
     * RecordingState transition; placing the observer in OverlayModule
     * would add a Recording-read to OverlayModule's coupling-matrix row
     * (SRP slide). Keeping it here matches the Coupling-Matrix §15.1.x
     * row `Recording × Overlay = C(OverlayAction.ResetSuppressBit)`.
     */
    override fun onCrossModuleStateChange(
        prev: DictateUiState,
        next: DictateUiState,
    ): List<Action> {
        val cascade = mutableListOf<Action>()
        if (prev.recording is RecordingState.Idle && next.recording is RecordingState.Preparing) {
            cascade += Action.OverlayAction.ResetSuppressBit
        }
        return cascade
    }

    /**
     * Synchronous hardware release on service-onDestroy (Spec 1 §7.3).
     * Idempotent — safe to call even if no recording was active.
     */
    override fun terminate(services: ModuleServices) {
        services.recordingHardware.release()
    }
}
