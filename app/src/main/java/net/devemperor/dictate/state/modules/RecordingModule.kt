// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package. The sub-directory keeps modules grouped together
// in the file tree without paying the per-file package cost.
package net.devemperor.dictate.state

import java.io.File
import kotlin.reflect.KClass
import kotlinx.coroutines.launch
import net.devemperor.dictate.preferences.Pref

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
         * @property codecParams audio codec params to configure the
         *   MediaRecorder with. `null` selects [net.devemperor.dictate.audio.CodecParams.DEFAULT_AAC_M4A]
         *   (the historic defaults — used for fresh sessions, the only
         *   case the orchestrator-side path supports today). The
         *   B2 Cold-Resume path will populate this from the previous
         *   segment via [net.devemperor.dictate.audio.AudioCodecReader].
         */
        data class AllocateMediaRecorder(
            val target: InsertionTarget,
            val useBluetooth: Boolean,
            val audioFile: File,
            val codecParams: net.devemperor.dictate.audio.CodecParams? = null,
            val sessionId: String? = null,
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
         * Persist the just-allocated audio-file name to
         * `Pref.LastFileName` for the RESEND-recovery flow
         * (indirection-cleanup 2026-05-21, Chunk 4.4 — A-4 / A-5).
         *
         * Emitted on the `Idle → Preparing` reducer arm (both BT and
         * non-BT branches) and on the imported-audio-file path.
         * Reads `Pref.LastFileName` (consumed by
         * `KeyboardVisibilityPredicates.resolveResendVisibility` to
         * decide whether the RESEND button is shown — the file's
         * presence in `cacheDir/audio/` is the RESEND eligibility
         * predicate).
         *
         * **Why a separate effect (not a generic `PersistPref<String>`):**
         * the imported-audio-file branch uses a sibling effect
         * ([PersistImportedAudioFileName]) that also clears
         * `Pref.TranscriptionAudioFile` — see that effect's KDoc for the
         * SP-vs-State atomicity semantics (review-fix G6, 2026-05-21).
         * Bundling the two writes into a sibling effect keeps the
         * sequencing local to one handler (vs. emitting two
         * `PersistPref` effects whose ordering would have to be
         * preserved by the orchestrator).
         */
        data class PersistLastFileName(val fileName: String) : Effect

        /**
         * Persist `Pref.LastFileName` (the imported file name) AND clear
         * `Pref.TranscriptionAudioFile` (indirection-cleanup 2026-05-21,
         * Chunk 4.4 — A-5 import-audio-file branch). The Settings
         * Activity writes the imported audio file to
         * `Pref.TranscriptionAudioFile`; on the next keyboard view the
         * IME picks it up, threads it through the orchestrator, and
         * MUST clear the SP slot so the import does not loop.
         *
         * **Atomicity semantics (review-fix G6, 2026-05-21):** the earlier
         * KDoc claimed an "atomic pair" — that framing is misleading
         * because the atomicity is **State-side only**, not SP-side. Two
         * precise facts:
         *
         *  1. **State is updated atomically.** The action that emits this
         *     effect (`Action.RecordingAction.OnAudioFileImported`)
         *     produces a single reducer pass; if the import flow ever
         *     observed `RecordingState` it would see one transition, not
         *     two. (Today the import-path does not mutate
         *     `RecordingState`; it is effect-only — but the principle
         *     stands for any future state coupling.)
         *  2. **SP writes are sequential, not atomic.** This effect's
         *     `runEffect` handler calls `services.prefs.persist(...)`
         *     twice in sequence (once for `Pref.LastFileName`, once for
         *     `Pref.TranscriptionAudioFile`). Each call fires the
         *     `PipelinePrefMirror` listener for `Pref.LastFileName`
         *     (which is mirrored) and writes the empty-string canonical
         *     form for `Pref.TranscriptionAudioFile` (which is **not**
         *     mirrored — purely IME-side persistence). Because the
         *     second write targets a non-mirrored key, consumer-side
         *     observability is unaffected by the lack of SP-side
         *     atomicity: a reader that races between the two writes
         *     either sees the old-or-new mirrored-state (governed by
         *     the first write's mirror cascade) and the
         *     non-mirrored key's value is only consumed at
         *     `onStartInputView`-start, which is gated by the action
         *     having already completed.
         *
         * Bundling both writes into a single effect (vs. emitting two
         * `PersistPref<...>` effects whose ordering would have to be
         * preserved by the orchestrator) keeps this sequencing local to
         * one handler — a reader can see the two `services.prefs.persist`
         * calls in one place rather than tracing two effect emissions
         * across the reducer.
         */
        data class PersistImportedAudioFileName(val fileName: String) : Effect

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

        /**
         * Sync `SessionEntity.audio_file_paths` with the live segment list
         * on disk (recording-stack-completion Block A1).
         *
         * Emitted on three boundaries:
         *
         *  1. `Preparing → Active` (MediaRecorderReady) — first segment is
         *     now live, persist the single-element list.
         *  2. `Active` stays (SegmentRolled) — rolling-segments handover
         *     completed, append the new file to the column.
         *  3. `Idle → Preparing` (StartRecordingContinuation) — Cold-Resume
         *     minted a new segment via `allocateNext` before the action
         *     was dispatched; this is the first sync that picks it up so
         *     the eventual MediaMuxer-concat sees every segment.
         *
         * **Effect-handler** dispatches to `Dispatchers.IO` via
         * `services.scope` — the segment-list `listFiles` call is IO. The
         * adapter swallows DAO failures (fail-soft, see KDoc on
         * `PipelineSessionRepoSubsystem.syncAudioFilePaths`).
         */
        data class SyncAudioSegments(val sessionId: String) : Effect

        /**
         * Insert the session row at recording-start with
         * `status = RECORDING` — the **first link of the recovery
         * chain** (2026-05-22).
         *
         * Emitted on the `StartRecording` `Idle → Preparing` arm. Until
         * this effect existed nothing wrote a `RECORDING` row while a
         * recording was in flight: `SessionManager.createSession` only
         * runs at `StopRecordingAndSend` time. A process death
         * mid-recording (the FGS is torn down when the user switches
         * keyboards — see `DictatePipelineService.onDestroy`) therefore
         * left **no DB row** for [net.devemperor.dictate.state.PipelineRecovery]
         * to promote to `RECORDING_INTERRUPTED`, and the audio was
         * silently unrecoverable. This row is the anchor that ties the
         * on-disk audio segments to the continuation-lookup.
         *
         * **Effect-handler** launches into `services.scope`; the insert
         * is fail-soft (the adapter swallows DAO failures — recording
         * must never crash because the row-create failed). The row's
         * `audio_file_paths` is provisionally the first segment and is
         * kept fresh by the [SyncAudioSegments] effects as segments roll.
         */
        data class CreateRecordingSession(
            val sessionId: String,
            val audioFile: File,
        ) : Effect

        /**
         * Re-arm an existing crash-interrupted session row back to
         * `status = RECORDING` — emitted on the
         * `StartRecordingContinuation` arm.
         *
         * The continuation row already exists as `RECORDING_INTERRUPTED`
         * (the `ContinuationLookup` found it). Transitioning it back to
         * `RECORDING` means a **second** interruption mid-continuation is
         * caught by [net.devemperor.dictate.state.PipelineRecovery]
         * exactly like the first — the recovery chain stays armed across
         * an arbitrary number of resume cycles. Fail-soft, same as
         * [CreateRecordingSession].
         */
        data class MarkSessionRecording(val sessionId: String) : Effect

        /**
         * recording-stack-completion §4.5.3 — atomic user-driven
         * discard of a session: delete every segment + transient merged
         * file from the cache AND promote the DB row to FAILED with
         * reason `"discarded_by_user"` so the row leaves the
         * pending-list and the continuation-lookup skips it.
         *
         * Emitted from two places:
         *  1. `DiscardInterruptedSession` (Idle / Interrupted arms) —
         *     the original §4.5.3 discard of a RECORDING_INTERRUPTED
         *     session.
         *  2. Every `CancelRecording` arm (Preparing / Active / Paused,
         *     widget-cancel-restart fix 2026-07-09) — cancelling a LIVE
         *     recording must also retire the RECORDING row that
         *     `CreateRecordingSession` inserted at start, and clean the
         *     rolled segments 2..N that the point-delete
         *     `DeleteAudioFile(state.audioFile)` misses (the FSM's
         *     `audioFile` stays the first segment across
         *     `SegmentRolled`). `markFailed` transitions the row
         *     RECORDING → FAILED directly — the same fail-soft adapter
         *     path recovery uses.
         *
         * **Why one effect, not two.** Splitting into separate
         * `DeleteSessionAudio` + `MarkSessionFailed` effects would
         * make the partial-failure state observable: if the executor
         * dies between the two calls, the cache is half-cleaned. The
         * single-effect form lets us order the calls (delete files
         * first, then mark row failed) inside a single
         * `services.scope.launch` block — if either call fails, the
         * next cleanup-job + recovery pass closes the loop
         * idempotently.
         */
        data class DiscardAudioForSession(val sessionId: String) : Effect
    }

    override fun reduce(
        state: RecordingState,
        action: Action.RecordingAction,
        ctx: ReducerContext,
    ): TransitionResult<RecordingState, Effect>? {
        val transition = reduceInner(state, action, ctx)
        android.util.Log.i(
            "DictateTrace",
            "RecordingModule.reduce ${state::class.simpleName} + " +
                "${action::class.simpleName} -> " +
                if (transition == null) "null"
                else transition.nextState::class.simpleName +
                    " effects=" + transition.sideEffects.joinToString { it::class.simpleName ?: "?" }
        )
        return transition
    }

    private fun reduceInner(
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
                        // 2026-05-21 indirection-cleanup Chunk 4.4 (A-4)
                        // — RESEND-recovery file-name persist also on
                        // the BT branch (the file is allocated up-front;
                        // SCO-wait does not gate persistence).
                        sideEffects = listOf(
                            // Recovery-chain first link (2026-05-22) —
                            // persist the RECORDING row before the SCO
                            // wait so a crash mid-handshake is still
                            // recoverable.
                            Effect.CreateRecordingSession(
                                sessionId = action.sessionId,
                                audioFile = action.audioFile,
                            ),
                            Effect.PersistLastFileName(action.audioFile.name),
                        ),
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
                                sessionId = action.sessionId,
                            ),
                            // Recovery-chain first link (2026-05-22) —
                            // insert the RECORDING row so a process
                            // death mid-recording leaves a row for
                            // PipelineRecovery to promote.
                            Effect.CreateRecordingSession(
                                sessionId = action.sessionId,
                                audioFile = action.audioFile,
                            ),
                            // 2026-05-21 indirection-cleanup Chunk 4.4
                            // (A-4) — RESEND-recovery file-name persist.
                            Effect.PersistLastFileName(action.audioFile.name),
                        ),
                    )
                }
            }

            is Action.RecordingAction.StartRecordingContinuation ->
                // Shared with the Interrupted arm — see [continuationTransition].
                continuationTransition(action, ctx)

            is Action.RecordingAction.OnAudioFileImported -> {
                // 2026-05-21 indirection-cleanup Chunk 4.4 (A-5) —
                // Audio-file import path: persist the file name for
                // RESEND-recovery and clear the transient
                // TranscriptionAudioFile slot. No FSM transition — the
                // import is a one-shot SP-write pair that hands the file
                // off to the pipeline directly (the IME service then
                // dispatches `PipelineAction.TriggerPipeline` for the
                // import).
                TransitionResult(
                    nextState = state,
                    sideEffects = listOf(
                        Effect.PersistImportedAudioFileName(action.audioFile.name),
                    ),
                )
            }

            is Action.RecordingAction.DiscardInterruptedSession -> {
                // recording-stack-completion §4.5.3 — atomic user-driven
                // discard of a RECORDING_INTERRUPTED session. FSM stays
                // Idle; the dual side-effect handles audio + DB.
                TransitionResult(
                    nextState = state,
                    sideEffects = listOf(
                        Effect.DiscardAudioForSession(action.sessionId),
                    ),
                )
            }

            is Action.RecordingAction.SurfaceInterruptedRecording ->
                // Recovery auto-surfacing (2026-05-22) — the recovery
                // pass detected a fresh RECORDING_INTERRUPTED session;
                // drive Idle → Interrupted so the keyboard shows it "as
                // if briefly paused" (frozen timer at elapsedMs). Purely
                // passive — no hardware is touched until the user taps
                // to continue.
                TransitionResult(
                    nextState = RecordingState.Interrupted(
                        sessionId = action.sessionId,
                        elapsedMs = action.elapsedMs,
                    ),
                    sideEffects = emptyList(),
                )

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
                    // Block A1 — first segment is now live. Persist the
                    // segment list so the DB row leaves the empty-list
                    // state (and a crash after this point leaves the
                    // segment recoverable via Cold-Resume).
                    Effect.SyncAudioSegments(state.sessionId),
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
                                sessionId = state.sessionId,
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
                    // Widget-cancel-restart fix (2026-07-09): the
                    // Idle → Preparing arm already inserted the RECORDING
                    // row (CreateRecordingSession) — without this discard
                    // a cancel mid-prepare leaves a session corpse that
                    // the next PipelineRecovery pass promotes to
                    // RECORDING_INTERRUPTED (phantom "unfinished
                    // recording" the user already trashed). Ordered after
                    // ReleaseMediaRecorder so the async delete never races
                    // a live recorder handle.
                    Effect.DiscardAudioForSession(state.sessionId),
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
                    // Widget-cancel-restart fix (2026-07-09): cancel used
                    // to leave the SessionEntity row at status=RECORDING
                    // forever and — because `state.audioFile` stays the
                    // FIRST segment across SegmentRolled — kept every
                    // rolled segment 2..N on disk. The stale row (a) was
                    // promoted to RECORDING_INTERRUPTED by the next
                    // recovery pass, resurfacing/continuing a trashed
                    // recording, and (b) sat in `findActiveSessionIds`,
                    // shielding the orphan segments from the cleanup job
                    // forever. Reuse the §4.5.3 atomic discard: delete ALL
                    // segments + mark the row FAILED("discarded_by_user").
                    // Ordered after StopMediaRecorder (synchronous stop +
                    // release) so the delete never races the recorder.
                    Effect.DiscardAudioForSession(state.sessionId),
                    // Cancel from Active: discard + remove the notification.
                    Effect.DismissNotification,
                ),
            )
            // Block A1 — Rolling-Segments handover. State stays Active;
            // the only side-effect is the DB sync so the new segment lands
            // in `audio_file_paths`. A crash *after* this action leaves a
            // recoverable trail; a crash *before* loses the new segment's
            // path but the file itself is finalised on disk (the handover
            // wrote the `moov` atom before the OS callback fired).
            //
            // Defensive sessionId match — drop a stale roll for a previous
            // session-id (cannot happen with a single MediaRecorder + a
            // single live session, but the check is cheap and forward-
            // compatible with future BT-resume scenarios).
            is Action.RecordingAction.SegmentRolled ->
                if (action.sessionId == state.sessionId) {
                    TransitionResult(
                        nextState = state,
                        sideEffects = listOf(Effect.SyncAudioSegments(state.sessionId)),
                    )
                } else {
                    null
                }
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
                    // Widget-cancel-restart fix (2026-07-09) — same
                    // session-corpse discard as the Active arm (see the
                    // comment there): delete ALL segments + mark the row
                    // FAILED("discarded_by_user").
                    Effect.DiscardAudioForSession(state.sessionId),
                    // Cancel from Paused: discard + remove the notification.
                    Effect.DismissNotification,
                ),
            )
            else -> null
        }

        // Recovery-surfaced interrupted recording (2026-05-22). Rendered
        // "as if briefly paused"; the only meaningful transitions are
        // continue (a Record-tap, resolved to StartRecordingContinuation)
        // and discard (the trash button → DiscardInterruptedSession).
        is RecordingState.Interrupted -> when (action) {
            is Action.RecordingAction.StartRecordingContinuation ->
                // Continue — the same transition a Record-tap from Idle
                // takes once ContinuationLookup resolved a continuation.
                continuationTransition(action, ctx)

            is Action.RecordingAction.DiscardInterruptedSession -> TransitionResult(
                // Discard — drop the interrupted recording, return to
                // Idle. The dual side-effect deletes the segments and
                // marks the DB row FAILED.
                nextState = RecordingState.Idle,
                sideEffects = listOf(
                    Effect.DiscardAudioForSession(action.sessionId),
                ),
            )

            // A stale re-surface, or a Stop/Pause with no live recorder,
            // is structurally meaningless here → null (Rejected).
            else -> null
        }
    }

    /**
     * Shared `StartRecordingContinuation` transition — used by both the
     * `Idle` arm (a Record-tap that `ContinuationLookup` resolved to a
     * continuation) and the `Interrupted` arm (continuing a
     * recovery-surfaced interrupted recording). Both reach the FSM at
     * [RecordingState.Preparing]; the prior state carries no payload
     * into the transition, so one helper serves both.
     *
     * B2 / ADR-0008 §"Auto-Continuation". Mirrors the `StartRecording`
     * branch with three tweaks fed in by
     * [net.devemperor.dictate.state.ContinuationLookup]:
     *   1. `sessionId` is reused (no fresh UUID — the existing
     *      crash-interrupted session id).
     *   2. `audioFile` is the next segment already appended to
     *      `audio_file_paths` by `allocateNext`.
     *   3. `codecParams` are threaded into [Effect.AllocateMediaRecorder]
     *      so the new `MediaRecorder` writes the same format as the
     *      prior segments — heterogeneous formats break the eventual
     *      MediaMuxer concat (ADR-0007 §"Failure-Modes §1").
     *
     * The BT-mic branch matches the `StartRecording` shape: the SCO wait
     * carries `audioFile` + `target` + `sessionId` and defers
     * `AllocateMediaRecorder` until `ScoRouteResolved`.
     */
    private fun continuationTransition(
        action: Action.RecordingAction.StartRecordingContinuation,
        ctx: ReducerContext,
    ): TransitionResult<RecordingState, Effect> {
        require(action.sessionId.isNotBlank()) {
            "StartRecordingContinuation.sessionId must be non-blank"
        }
        return if (ctx.global.audio.useBluetoothMic) {
            TransitionResult(
                nextState = RecordingState.Preparing(
                    useBluetooth = true,
                    audioFile = action.audioFile,
                    sessionId = action.sessionId,
                    awaitingSco = true,
                    target = action.target,
                ),
                sideEffects = listOf(
                    // Recovery-chain (2026-05-22) — re-arm the
                    // interrupted row back to RECORDING so a second
                    // interruption is caught by PipelineRecovery again.
                    Effect.MarkSessionRecording(action.sessionId),
                    Effect.PersistLastFileName(action.audioFile.name),
                    // Block A1 — Cold-Resume minted a new segment via
                    // `allocateNext` before this action; persist the
                    // segment list now so a crash before
                    // MediaRecorderReady still leaves it in the DB.
                    Effect.SyncAudioSegments(action.sessionId),
                ),
            )
        } else {
            TransitionResult(
                nextState = RecordingState.Preparing(
                    useBluetooth = false,
                    audioFile = action.audioFile,
                    sessionId = action.sessionId,
                    awaitingSco = false,
                    target = null,
                ),
                sideEffects = listOf(
                    Effect.AllocateMediaRecorder(
                        target = action.target,
                        useBluetooth = false,
                        audioFile = action.audioFile,
                        sessionId = action.sessionId,
                        codecParams = action.codecParams,
                    ),
                    Effect.MarkSessionRecording(action.sessionId),
                    Effect.PersistLastFileName(action.audioFile.name),
                    Effect.SyncAudioSegments(action.sessionId),
                ),
            )
        }
    }

    override fun runEffect(effect: Effect, services: ModuleServices): Unit = when (effect) {
        is Effect.AllocateMediaRecorder ->
            services.recordingHardware.allocate(
                target = effect.target,
                useBluetooth = effect.useBluetooth,
                audioFile = effect.audioFile,
                codecParams = effect.codecParams,
                sessionId = effect.sessionId,
            )
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
        // 2026-05-21 indirection-cleanup Chunk 4.4 (A-4 / A-5) —
        // route the RESEND-recovery file-name persist through the
        // canonical PrefPersistenceService seam.
        is Effect.PersistLastFileName ->
            services.prefs.persist(Pref.LastFileName, effect.fileName)

        // Block A1 — push the live segment list into the DB. Launches
        // into `services.scope` because `syncAudioFilePaths` is suspend
        // (hops to Dispatchers.IO inside the adapter). The handler does
        // not await the result; fail-soft logging happens in the adapter.
        is Effect.SyncAudioSegments -> {
            services.scope.launch {
                services.sessionRepo.syncAudioFilePaths(effect.sessionId)
            }
            Unit
        }

        // Recovery-chain first link (2026-05-22) — insert the RECORDING
        // row at recording-start. Launched into `services.scope`; the
        // adapter hops to IO and swallows DAO failures (recording must
        // never crash because the row-create failed).
        is Effect.CreateRecordingSession -> {
            services.scope.launch {
                services.sessionRepo.createRecordingSession(
                    sessionId = effect.sessionId,
                    audioFilePath = effect.audioFile.absolutePath,
                )
            }
            Unit
        }

        // Recovery-chain (2026-05-22) — re-arm an interrupted row back
        // to RECORDING when the user continues a crash-interrupted
        // recording. Same fail-soft launch as CreateRecordingSession.
        is Effect.MarkSessionRecording -> {
            services.scope.launch {
                services.sessionRepo.transitionToRecording(effect.sessionId)
            }
            Unit
        }

        is Effect.PersistImportedAudioFileName -> {
            // Sequential SP writes (review-fix G6, 2026-05-21 — the
            // earlier "atomic pair" framing was misleading; the
            // atomicity is State-side only — see Effect.
            // PersistImportedAudioFileName KDoc for the precise
            // semantics). Both writes go through the same
            // SharedPreferences instance via the persistence service so
            // the mirror-listener fires for the first (mirrored)
            // write; the second targets `Pref.TranscriptionAudioFile`
            // which is NOT in the PipelinePrefMirror mirror — purely
            // IME-side persistence consumed at `onStartInputView`.
            services.prefs.persist(Pref.LastFileName, effect.fileName)
            // `TranscriptionAudioFile`'s default is `""`; persisting
            // the empty string is the canonical "cleared" representation
            // (consumer `onStartInputView` does `getString(...).isEmpty()`
            // — both `apply().remove(...)` and `apply().putString(...,"")`
            // produce the same observable state on the next read).
            services.prefs.persist(Pref.TranscriptionAudioFile, "")
            Unit
        }

        // recording-stack-completion §4.5.3 — atomic user-driven
        // discard. Order: delete files first (cache cleanup), then
        // mark row FAILED (DB cleanup). If the launch dies between
        // the two, the next cache-cleanup-job pass + the next
        // PipelineRecovery pass close the loop idempotently.
        is Effect.DiscardAudioForSession -> {
            services.scope.launch {
                runCatching {
                    services.audioFileRepository.deleteAll(effect.sessionId)
                }.onFailure {
                    android.util.Log.w(
                        "RecordingModule",
                        "DiscardAudioForSession deleteAll failed for ${effect.sessionId}",
                        it,
                    )
                }
                services.sessionRepo.markFailed(
                    effect.sessionId,
                    reason = "discarded_by_user",
                )
                // Re-read the pending list (now post-FAILED-promotion
                // — the markFailed above wrote the row out of the
                // RECORDING_INTERRUPTED status that loadPending picks
                // up) and dispatch Refresh so PendingSessionsModule's
                // reducer drops the row from `state.pendingSessions`.
                // This clears the Idle-arm Trash-button visibility
                // predicate on the next render.
                val refreshed = services.sessionRepo.loadPending()
                services.emitAction(
                    Action.PendingSessionsAction.Refresh(refreshed),
                )
            }
            Unit
        }
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
