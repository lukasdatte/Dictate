package net.devemperor.dictate.state

import java.io.File

/**
 * Root of the state-mutation action hierarchy.
 *
 * Every state mutation in the Dictate IME goes through
 * `DictateOrchestrator.dispatch(action: Action)` — the **only** mutation
 * entry point (F-8 Single Dispatch). Each inner `sealed class` per module
 * groups the actions that module's reducer handles; the orchestrator
 * routes via `KClass<out Action>`-Lookup against the module registry.
 *
 * **Why one inner sealed class per module?**
 *
 * - **Type-safe routing.** Each [DictateModule] declares
 *   `actionClass: KClass<A>` and the orchestrator's
 *   `moduleByLeafClass: Map<KClass<out Action>, DictateModule<*, *, *>>`
 *   is built at init time from `KClass.sealedSubclasses`. Lookup is O(1).
 * - **Compile-time exhaustivity.** Reducer-`when` blocks over the inner
 *   sealed class (e.g. `when (action: Action.RecordingAction)`) are
 *   compile-error-on-missing-branch.
 * - **OCP.** Adding a new action variant = a new `data class`/`data object`
 *   in the inner sealed; other modules untouched.
 *
 * **What an Action MUST NOT carry:**
 *
 * - Methods or logic — actions are pure data containers.
 * - Hardware references (no `MediaRecorder` field) — only the data the
 *   reducer needs to compute the next state and side-effect plan.
 * - Cross-module mutations — an Action targets exactly one module.
 *
 * **The five sources of actions** (Spec 1 §4.0.1.2):
 *
 * 1. UI click — `slot.actionResolver(state, services) -> Action?` →
 *    `onAction?.invoke(it)`; `null` is a silent no-op.
 * 2. Android lifecycle hook — `onFinishInputView` →
 *    `dispatch(ViewModeAction.OnImeViewHidden)`.
 * 3. Cross-module cascade (Mode 2) — `onCrossModuleStateChange(prev, next)`
 *    returns `List<Action>`; orchestrator dispatches recursively at depth+1.
 * 4. Effect completion — `services.emitAction(action)` from inside
 *    `runEffect` (async via `scope.launch { dispatch(action) }`).
 * 5. Effect failure (automatic) — orchestrator wraps any `runEffect` throw
 *    as [EffectFailure].
 *
 * @see net.devemperor.dictate.state.DictateModule
 * @see net.devemperor.dictate.state.DictateUiState
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"Action sealed hierarchy"
 * @see docs/decisions/0002-state-cross-module-cascade.md §"EffectFailure routing"
 * @see docs/architecture/state-architecture/state-and-actions.md §4
 */
sealed class Action {

    // ════════════════════════════════════════════════════════════════
    // Failure channel (top-level — not module-scoped)
    // ════════════════════════════════════════════════════════════════

    /**
     * Failure-channel action emitted by the orchestrator when a
     * `module.runEffect(effect, services)` throws.
     *
     * **Why a top-level `data class` (not per module)?** All modules can
     * fail; making `EffectFailure` an inner of every module's sealed
     * hierarchy would force every reducer to handle it. Top-level
     * placement plus origin-routing keeps the failure pipe single-typed.
     *
     * **Routing:** The orchestrator routes an [EffectFailure] back to the
     * module identified by [originModuleId] (NOT by KClass — all
     * EffectFailures share the same Kotlin class). The target module's
     * [DictateModule.reduceFailure] hook decides whether to roll back
     * its sub-state. Default `reduceFailure` returns `null` →
     * `DispatchOutcome.Rejected("reducer-null")`, which is semantically
     * correct ("no failure path defined").
     *
     * **`effect` is a string, not a typed effect.** The orchestrator
     * captures `effect.toString()` because the effect type
     * `E : SideEffect` is module-local — there's no top-level Effect
     * union. Module `reduceFailure` implementations match either by
     * exact-string (for `object`-effects, simple-name) or by
     * `startsWith("EffectName(")` (for `data class`-effects, which
     * include their args in `toString()`). See [SideEffect] KDoc.
     *
     * @property originModuleId which module emitted the effect that threw.
     * @property effect `effect.toString()` of the offending effect.
     * @property reason `throwable.message ?: throwable.javaClass.simpleName`.
     */
    data class EffectFailure(
        val originModuleId: ModuleId,
        val effect: String,
        val reason: String,
    ) : Action()

    // ════════════════════════════════════════════════════════════════
    // Recording-axis actions (RecordingModule)
    // ════════════════════════════════════════════════════════════════

    /** Lifecycle actions for the [RecordingState] FSM. */
    sealed class RecordingAction : Action() {
        /**
         * Begin a new recording session.
         *
         * - `audioFile` is pre-allocated by the caller (Pre-Dispatch-
         *   Allocator pattern, Spec 1 §4.11) so the reducer stays pure
         *   (no `cacheDir`-IO from inside `reduce`).
         * - `sessionId` is the caller-minted UUID (R.15 — strings
         *   throughout) for this recording. **F-10 (Epic §4 Block A2):**
         *   the reducer carries it into [RecordingState.Preparing] and it
         *   propagates through `Active`/`Paused`; on
         *   [StopRecordingAndSend] the reducer reads `state.sessionId`
         *   and hands it to the pipeline trigger. This makes the
         *   recording FSM the single source of the session id — no
         *   empty-string sentinel anywhere. The IME's pre-allocated
         *   `JobExecutor.register()` UUID is routed in here once B3 flips
         *   the recording trigger to dispatch (until then the click
         *   resolver mints a fresh UUID).
         */
        data class StartRecording(
            val target: InsertionTarget,
            val audioFile: File,
            val sessionId: String,
        ) : RecordingAction()

        /**
         * Begin a **continuation** of a crash-interrupted recording
         * session (B2 / ADR-0008 §"Auto-Continuation").
         *
         * The contract mirrors [StartRecording] but with three twists
         * fed in by [net.devemperor.dictate.state.ContinuationLookup]:
         *
         * - **`sessionId`** is the existing `RECORDING_INTERRUPTED`
         *   session-id (reused — no fresh UUID). The DB row's status
         *   transitions back to in-flight via the reducer's
         *   `Idle → Preparing` arm just like a fresh recording, but
         *   `audio_file_paths` retains every prior segment so the
         *   eventual MediaMuxer concat sees the full session.
         * - **`audioFile`** is the **next** segment file allocated by
         *   [net.devemperor.dictate.audio.AudioFileRepository.allocateNext]
         *   *before* this action is dispatched. The repository has
         *   already appended its path to the session's `audio_file_paths`
         *   column at allocate-time (R.2 — the reducer stays pure; no
         *   DB write inside the reducer).
         * - **`codecParams`** are the codec parameters read from the
         *   last existing segment via
         *   [net.devemperor.dictate.audio.AudioCodecReader.readCodecParams].
         *   The `Effect.AllocateMediaRecorder` arm passes them to the
         *   subsystem so the new MediaRecorder records in the same
         *   format — heterogeneous segments would fail MediaMuxer
         *   concat (ADR-0007 §"Failure-Modes §1", B1.2 mitigation).
         *
         * **User-visible distinction from [StartRecording]:** none. The
         * user clicked Record; the FSM goes to Preparing → Active just
         * like a fresh recording. When they hit Send, every prior
         * segment plus the new one are concatenated and fed into the
         * pipeline — exactly as if no crash had happened. Per the plan,
         * Continuation is **silent** (no info-bar, no toast).
         *
         * **Why not piggyback on [StartRecording]?** A continuation
         * carries non-trivial extra payload (the reused id, the
         * read-back codec params), and the resolver path that produces
         * it does extra IO (DB lookup, segment list, MediaExtractor
         * read, allocateNext). Keeping the actions distinct makes
         * reducer-tests, audit-trails, and analytics greppable; the
         * Idle-arm body in [net.devemperor.dictate.state.RecordingModule]
         * stays linear instead of being a 50-line `if-continuation /
         * else-fresh` branch.
         */
        data class StartRecordingContinuation(
            val target: InsertionTarget,
            val audioFile: File,
            val sessionId: String,
            val codecParams: net.devemperor.dictate.audio.CodecParams,
        ) : RecordingAction()

        /**
         * Hardware callback — `MediaRecorder.prepare()` returned. Drives
         * the `Preparing → Active` transition. Carries the **actual**
         * allocated file (may differ from the requested one if the
         * hardware adapter substituted a fallback path).
         */
        data class MediaRecorderReady(val audioFile: File) : RecordingAction()

        /**
         * Rolling-Segments handover completed — the previous segment was
         * finalised (its `moov` atom written) and the recorder is now
         * writing into the next segment file (recording-stack-completion
         * Block A1).
         *
         * Emitted by [net.devemperor.dictate.core.RecordingHardwareAdapter]'s
         * `OnInfoListener` on `MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED`.
         * The reducer's `Active`-arm responds by emitting
         * [RecordingModule.Effect.SyncAudioSegments] so the new segment
         * lands in `SessionEntity.audioFilePaths` — a crash *after* this
         * point leaves a recoverable trail in the DB.
         *
         * **Payload is the sessionId**, not the file path. The full segment
         * list lives on disk; the effect handler reads it via
         * `AudioFileRepository.segments(sessionId)`. Carrying the path here
         * would duplicate state (file is already in the cache via
         * `setNextOutputFile`); the segment-list-from-disk is the canonical
         * source.
         */
        data class SegmentRolled(val sessionId: String) : RecordingAction()

        data object PauseRecording : RecordingAction()
        data object ResumeRecording : RecordingAction()
        data object StopRecording : RecordingAction()
        data object CancelRecording : RecordingAction()

        /**
         * Audio-file import path
         * (indirection-cleanup 2026-05-21, Chunk 4.4 — A-5).
         *
         * The Settings Activity writes a user-picked audio file path to
         * `Pref.TranscriptionAudioFile`; on the next `onStartInputView`
         * the IME picks it up, dispatches this action, and the
         * RecordingModule's `Idle`-arm reducer emits
         * `Effect.PersistImportedAudioFileName(file.name)` — atomic
         * pair of writes to `Pref.LastFileName` (RESEND-recovery) +
         * `Pref.TranscriptionAudioFile` cleared (so the import does
         * not loop).
         *
         * **Why a distinct action (not piggyback on `TriggerPipeline`):**
         * the import bypasses `StartRecording` entirely (no FSM
         * Preparing → Active transition); the file is handed directly
         * to the pipeline. The persist + clear pair is the only state
         * mutation on the import path, so a dedicated action keeps it
         * dispatch-symmetric with `StartRecording`'s persist effect.
         */
        data class OnAudioFileImported(val audioFile: File) : RecordingAction()

        /**
         * RECORD-button **long-press** — the 2-mode handler ported from
         * the legacy `MainButtonsController.onRecordLongClicked`
         * (`DictateInputMethodService.java:3257-3268`, behaviour group G2,
         * render-path-cutover.md §3 / §7 ambiguity A1).
         *
         * **Pure-data action, body resolved in the reducer (ADR-0001
         * single-dispatch).** The legacy handler has two state-dependent
         * modes:
         *
         *  - **`Idle`** → open Settings + the audio-file picker
         *    (`startActivity(DictateSettingsActivity` + `open_file_picker`
         *    extra). This is an **IME-side Activity launch** — there is no
         *    Activity/IME-flag surface on [ModuleServices] and adding one
         *    would exceed CR1's additive scope (it is the CR4 IME-side
         *    wiring concern). The reducer therefore returns `null` for the
         *    Idle case (`DispatchOutcome.Rejected("reducer-null")` — the
         *    correct "no FSM transition for this mode" outcome); the
         *    Activity launch is wired IME-side when CR4 activates the new
         *    path. Until then the **legacy** `MainButtonsController`
         *    long-press listener still drives the live keyboard (RR-1:
         *    the new surface is dormant — `staticHandlerInstaller` is
         *    `null`, the catalog `longClickResolver` is wired but the
         *    backend's long-press listener is not the live one for RECORD
         *    until CR4 removes the legacy drive).
         *  - **`Active` / `Paused`** → the legacy path set the IME's
         *    `autoSwitchKeyboard = true` flag then called
         *    `stopRecording()`. The FSM half is exactly the existing
         *    [StopRecordingAndSend]-shaped *stop* transition; this arm
         *    reuses the `StopRecording` effect set (stop recorder / timer
         *    / glow / amplitude + dismiss notification — a *discard* stop,
         *    matching the legacy `stopRecording()` which discards rather
         *    than sending). The `autoSwitchKeyboard` one-shot flag is an
         *    IME-side affordance (not FSM state) wired in CR4.
         *
         * Modelling this as pure data with the mode resolved in the
         * reducer from `state.recording` is the spec-faithful A1
         * resolution (render-path-cutover.md §7 A1: "Model
         * `Action.RecordingAction.OnRecordLongPress` (2-mode resolved in
         * the module reducer from `state.recording`)"). It mirrors the
         * existing [StopRecording] / [CancelRecording] data-object arms —
         * no new [ModuleServices] surface, no architecture change. The
         * `Preparing` case returns `null` (long-press while the recorder
         * warms up is structurally meaningless, same as the click
         * resolver's `Preparing → null`).
         *
         * @see net.devemperor.dictate.state.layout.resolveRecordLongPressAction
         * @see docs/plans/2026-05-15 - dictate-cutover-completion/research/render-path-cutover.md §3 G2 / §7 A1
         */
        data object OnRecordLongPress : RecordingAction()

        /**
         * Bluetooth-SCO route resolved during a BT-mic `Preparing` wait
         * (C6-IMPL-1 / B2-C6-W1). Cascaded by [AudioModule]'s
         * cross-module observer once the SCO connection either connects
         * (`useBluetooth = true` → `VOICE_COMMUNICATION`) or
         * fails/times-out (`useBluetooth = false` → `MIC` fallback),
         * mirroring the legacy `RecordingStateController.onScoConnected`
         * / `onScoFailed` callbacks (`:300-321`).
         *
         * Consumed **only** by the `Preparing` reducer arm while
         * [RecordingState.Preparing.awaitingSco] is `true` — it carries
         * the deferred `AllocateMediaRecorder` that was withheld at
         * `StartRecording` so the recorder source matches the actual
         * SCO outcome (gate-RED-blocking silent-quality-loss fix:
         * allocating `VOICE_COMMUNICATION` without a live SCO route
         * silently records the phone mic).
         *
         * @see net.devemperor.dictate.state.RecordingState.Preparing.awaitingSco
         * @see docs/plans/2026-05-15 - dictate-cutover-completion/research/recording-audiofocus-btsco-handshake.md
         */
        data class ScoRouteResolved(val useBluetooth: Boolean) : RecordingAction()

        /**
         * "Send" click — stop recording AND trigger the pipeline.
         *
         * **F-2 fix (2026-05-15):** the cascade-via-observer pattern hinted
         * by the earlier KDoc was never implemented — `StopRecording` and
         * `StopRecordingAndSend` collapsed into the same reducer arm with
         * no Active/Paused → Idle observer firing `TriggerPipeline`. The
         * fix routes the trigger through a [Effect.EmitPipelineTrigger]
         * side-effect on the same reducer arm; the effect calls
         * `services.emitAction(Action.PipelineAction.TriggerPipeline(...))`
         * to re-enter the dispatch loop with a fresh action.
         *
         * **F-10 (Epic §4 Block A2):** this action carries **no payload**.
         * The `sessionId` for the pipeline trigger is read off the live
         * `RecordingState` (`Active.sessionId` / `Paused.sessionId`) — the
         * same id minted at [StartRecording]. The audio file likewise
         * comes from `RecordingState.Active.audioFile` (or
         * `Paused.audioFile`) at the time the reducer fires. The earlier
         * empty-string-sentinel payload is removed entirely — the FSM is
         * the single source of the session id.
         */
        data object StopRecordingAndSend : RecordingAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Pipeline-axis actions (PipelineModule)
    // ════════════════════════════════════════════════════════════════

    /** Lifecycle + progress actions for the [PipelineUiState] FSM. */
    sealed class PipelineAction : Action() {
        /**
         * Source-agnostic pipeline entry-point `(sessionId, audioFile)`.
         *
         * Two valid callers:
         *  1. **Post-record** — emitted by
         *     [net.devemperor.dictate.state.modules.RecordingModule.Effect.EmitPipelineTrigger]
         *     on the `Active/Paused → Idle` `StopRecordingAndSend` arm
         *     (the recording FSM hands its just-recorded audio off).
         *  2. **Imported-audio-file (no recording FSM)** — dispatched
         *     directly by the IME's
         *     `transcribeImportedAudioFileViaOrchestrator()`
         *     (B2-C7-MID-W1): an externally-supplied audio file is
         *     transcribed without ever entering the recording FSM.
         *
         * The action carries everything the pipeline needs
         * (`sessionId` + `audioFile`); it does not assume the audio came
         * from a recording. See
         * `research/imported-audiofile-orchestrator-route.md`.
         */
        data class TriggerPipeline(val sessionId: String, val audioFile: File) : PipelineAction()

        /** Pipeline runner reports start — sets `Preparing → Running`. */
        data class StartPipeline(
            val sessionId: String,
            val totalSteps: Int,
            val autoEnterActive: Boolean,
        ) : PipelineAction()

        data class StepStarted(val sessionId: String, val stepName: String) : PipelineAction()
        data class StepCompleted(val sessionId: String) : PipelineAction()
        data class StepFailed(val sessionId: String, val reason: String) : PipelineAction()

        /** Pipeline successfully produced [finalText]. */
        data class PipelineDone(val sessionId: String, val finalText: String) : PipelineAction()

        data class PipelineFailed(val sessionId: String, val reason: String) : PipelineAction()

        /** `sessionId == null` cancels the **currently-active** pipeline (UI-slot use). */
        data class CancelPipeline(val sessionId: String? = null) : PipelineAction()

        /**
         * Toggle the per-run "auto-enter" flag while the pipeline is
         * `Running` (second tap of the record button during send).
         *
         * **Distinct from [FeatureToggleAction.ToggleAutoEnter]** —
         * that action toggles the *global* `Pref.AutoEnter` setting
         * (the user-level "always send Enter after every dictation"
         * preference). This action toggles **only** the in-flight
         * `Running.autoEnterActive` flag for the current pipeline run:
         * the next pipeline starts again from the global pref.
         *
         * Reducer arm: flips `Running.autoEnterActive`; no-op when the
         * pipeline is not `Running` (the button is hidden / has no
         * meaningful action outside Running anyway — see
         * `ActionResolvers.resolveRecordActionPipeline`).
         */
        data object ToggleRunningAutoEnter : PipelineAction()

        // ─── Reprocess-Staging sub-FSM ───
        data class StartReprocessStaging(val sessionId: String) : PipelineAction()
        data class UpdateReprocessQueue(val sessionId: String, val newQueue: List<Int>) : PipelineAction()
        data class UpdateReprocessLanguage(val sessionId: String, val code: String?) : PipelineAction()
        data class SendStaging(val sessionId: String) : PipelineAction()
        data class CancelReprocessStaging(val sessionId: String) : PipelineAction()

        // ─── Result handling (post-Done) ───
        data class ConfirmInsertion(val sessionId: String) : PipelineAction()
        data class DismissResult(val sessionId: String) : PipelineAction()

        /** DB-write failed (R.17 / Issue 2.1.21). */
        data class PersistenceError(val sessionId: String, val reason: String) : PipelineAction()

        /**
         * `JobExecutor.start` returned `false` — a parallel job is already
         * active. Reducer rolls Pipeline back to `Idle` (state-first race
         * mitigation, R.17).
         */
        data class RejectedJobAlreadyActive(val sessionId: String) : PipelineAction()

        /**
         * Per-second timer tick during [PipelineUiState.Running].
         *
         * B-D-3 (dictate-pipeline-render-and-state-unification §5.2
         * Variante A + §9.4 OQ-4: 1000 ms cadence). Dispatched from
         * the [net.devemperor.dictate.core.PipelineActivityTickerObserver]
         * while the pipeline is in `Running`. The
         * [net.devemperor.dictate.state.modules.PipelineModule] reducer
         * restamps `elapsedMs = ctx.now - state.startedAtMs` and emits
         * no side effects — pure state-only.
         *
         * **Idempotent:** if the pipeline is not in `Running` (Idle /
         * Preparing / Done / ReprocessStaging) the reducer returns
         * `null` (no-op) so a late-arriving tick from a previously
         * scheduled `Handler.postDelayed` (race with the
         * observer's stop) is harmless.
         *
         * **No `sessionId` payload** — the reducer reads the current
         * `Running.startedAtMs` directly. A session change mid-tick is
         * caught by the observer's `distinctUntilChanged` on the
         * pipeline phase (the observer cancels and re-starts the
         * ticker on every phase transition).
         */
        data object TickPipelineTimer : PipelineAction()
    }

    // ════════════════════════════════════════════════════════════════
    // ViewMode-axis actions (ViewModeModule — Triangle-FSM, ADR-0005)
    // ════════════════════════════════════════════════════════════════

    sealed class ViewModeAction : Action() {
        /** User toggled the widget preference (T1/T2 in Spec 3 §7.3). */
        data object ToggleViewModeWidget : ViewModeAction()

        data object OnImeViewShown : ViewModeAction()
        data object OnImeViewHidden : ViewModeAction()

        /** User clicked the overlay-close button. */
        data object CloseOverlay : ViewModeAction()

        /** Permission-loss + other cross-module cascades drive this directly. */
        data class SetViewMode(val mode: ViewMode) : ViewModeAction()

        /**
         * Cross-module cascade target — emitted by PipelineModule's observer
         * when the pipeline settles to [PipelineUiState.Idle] from any
         * non-Idle state. ViewModeModule re-runs `computeViewMode` with
         * `pipelineActive=false` and (in HOVER) falls back to KEYBOARD —
         * the T7 "Geist-Widget" structural protection (Spec 3 §7.3 T7).
         */
        data object OnPipelineDone : ViewModeAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Widget-axis actions (WidgetModule) — B3 / ADR-0008
    // ════════════════════════════════════════════════════════════════

    /**
     * Triggers for the [WidgetState] + `imeViewVisible` axes per the
     * plan §3 W1-W8 transition table. The reducer logic is filled in
     * by B3.2; B3.1 ships only the action shape so consumers can be
     * migrated incrementally.
     *
     * **Migration overlap:** these actions co-exist with the legacy
     * [ViewModeAction.*] surface during B3.2-B3.5. Dispatchers route
     * to one or the other; the orchestrator runs both modules but
     * [WidgetModule] is a no-op until B3.2.
     *
     * @see net.devemperor.dictate.state.WidgetModule
     * @see docs/decisions/0008-ui-surface-axes-widget-state-and-ime-view.md
     */
    sealed class WidgetAction : Action() {
        /**
         * User pressed the widget-toggle button. **W1** transition:
         * `widget=Hidden → widget=Visible(USER)`. The handler is
         * permission-gated upstream (see [resolveWidgetToggleAction] —
         * a missing SYSTEM_ALERT_WINDOW permission routes through
         * `OverlayAction.ShowOverlayOnboarding` instead).
         */
        data object ToggleWidget : WidgetAction()

        /**
         * User pressed the widget close-button. **W2** transition:
         * `widget=Visible → widget=Hidden + suppressBit=true +
         * (recording.Active → Paused)`. The pipeline keeps running in
         * the FGS; its result surfaces via the Pending-Insert
         * info-bar (B4) after pipeline-done.
         */
        data object CloseWidget : WidgetAction()

        /**
         * The IME-View just became visible. **W4 / W5** transition.
         * Driven by `DictateInputMethodService.onStartInputView` /
         * `onCreateInputView` returning a non-null view.
         */
        data object OnImeViewShown : WidgetAction()

        /**
         * The IME-View just became invisible. **W3** transition. Driven
         * by `DictateInputMethodService.onFinishInputView`.
         */
        data object OnImeViewHidden : WidgetAction()

        /**
         * Recording transitioned `Idle → Preparing` (W7) or
         * `Paused → Active` (W8). Cascaded by `WidgetModule`'s own
         * cross-module observer in reaction to the [RecordingState]
         * FSM transition. Clears the suppress-bit so the next
         * `OnImeViewHidden` can re-auto-show the PIPELINE widget.
         *
         * **B3.2 note:** the suppress-bit currently lives on
         * `OverlayState.suppressAutoOverlayUntilNextSession`; the
         * RecordingModule's own observer already emits
         * [OverlayAction.ResetSuppressBit] on `Idle → Preparing` (W7)
         * — kept as the source-of-truth path. This action is reserved
         * for the future suppress-bit migration to WidgetSubState
         * (B5 cleanup) and currently no-ops in the reducer.
         */
        data object ResetSuppressBit : WidgetAction()

        /**
         * Cross-module cascade target — emitted by `WidgetModule`'s
         * observer when `recording=Idle && pipeline=Idle` is reached
         * from any non-Idle state. Drives **W6**: auto-close a
         * `Visible(PIPELINE)` widget once nothing is in flight any
         * more (the pipeline that motivated the auto-show is done,
         * the floating UI is no longer needed). Symmetric to the
         * legacy [ViewModeAction.OnPipelineDone] cascade that drives
         * the T7 HOVER→KEYBOARD fall-back.
         *
         * Not in the plan's literal WidgetAction surface (§4 B3 lists
         * five actions); added during B3.2 implementation because the
         * W6 transition needs an action-shaped trigger and routing
         * through the observer keeps WidgetModule the single owner of
         * the widget axis. The deviation is documented here for the
         * archival readers.
         */
        data object OnPipelineDone : WidgetAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Layout-axis actions (LayoutModule)
    // ════════════════════════════════════════════════════════════════

    sealed class LayoutAction : Action() {
        data object ToggleSingleRowMode : LayoutAction()
        data object ToggleSmallMode : LayoutAction()

        /** Cross-module cascade target — ViewModeModule sets small-mode on T2. */
        data class SetSmallMode(val enabled: Boolean) : LayoutAction()

        data class SetContentArea(val area: net.devemperor.dictate.core.ContentArea) : LayoutAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Audio-axis actions (AudioModule)
    // ════════════════════════════════════════════════════════════════

    sealed class AudioAction : Action() {
        data object ToggleAudioFocusPref : AudioAction()
        data class OnAudioFocusGrantChanged(val granted: Boolean) : AudioAction()
        data class OnBluetoothScoStateChanged(val phase: ScoPhase, val reason: String? = null) : AudioAction()

        /**
         * Recording entered an audio-capturing phase (`Idle → Preparing`,
         * or `Paused → Active` on resume). Cascaded by [AudioModule]'s
         * own cross-module observer in reaction to the [RecordingState]
         * FSM transition (ADR-0002 Mode-2 cascade → Mode-1 effect). The
         * [AudioModule] reducer emits `Effect.RequestAudioFocus` (gated
         * on `audioFocusEnabledPref`, mirroring legacy
         * `RecordingStateController.proceedStartRecording:326`
         * `if (audioFocusEnabled) gate.request()`, `Pref.AudioFocus`
         * default `true`) and, when `useBluetoothMic`, kicks the SCO
         * handshake via `Effect.StartBluetoothSco`.
         *
         * **Why an AudioModule-owned action (not a RecordingModule
         * effect)?** Audio-focus + SCO are the `audio` axis — SRP keeps
         * their lifecycle in one module (Spec 1 §15.3 rationale). This
         * restores the Spec 1 §15.1 row-3 observer arm
         * (`Recording.Preparing → AudioFocus-Request`) that the
         * Phase-B S-4 KDoc removed under the false premise that
         * `RecordingHardwareAdapter.allocate` requests focus (it does
         * not — C6-IMPL-1 gate-RED).
         *
         * @see docs/plans/2026-05-15 - dictate-cutover-completion/research/recording-audiofocus-btsco-handshake.md
         */
        data object RecordingStarted : AudioAction()

        /**
         * Recording left an audio-capturing phase (`* → Idle` stop /
         * cancel, or `Active → Paused`). The [AudioModule] reducer emits
         * `Effect.ReleaseAudioFocus` + `Effect.StopBluetoothSco`,
         * mirroring legacy `stopRecording:150` / `cancelRecording:221`
         * `gate.abandon()` + `bluetoothScoManager.release()` and
         * `togglePause:168` (pause abandons focus). Idempotent at the
         * subsystem level (release/stop are no-ops if never acquired).
         *
         * @see net.devemperor.dictate.state.Action.AudioAction.RecordingStarted
         */
        data object RecordingEnded : AudioAction()

        /**
         * **B2-VAL-W1 F-2 — re-assert audio-focus on the BT-mic
         * SCO-wait-resolved edge** (`Preparing.awaitingSco true → false`,
         * the deferred-allocate transition produced by
         * [RecordingAction.ScoRouteResolved]).
         *
         * The [AudioModule] reducer emits **only** `Effect.RequestAudioFocus`
         * (gated on `audioFocusEnabledPref`). Unlike [RecordingStarted]
         * it does **not** re-emit `Effect.StartBluetoothSco` and does
         * **not** re-prime `bluetoothSco.phase` — the SCO handshake has
         * just *resolved* on this edge; re-kicking it or resetting the
         * `Connected`/`Failed` phase back to `Waiting` would be wrong.
         *
         * **Why a distinct action (not reuse [RecordingStarted])?**
         * Focus-(re)acquire and SCO-handshake-start are separate
         * concerns that only coincide at genuine recording-start. The
         * BT-mic path requests focus early (`Idle → Preparing`) then
         * *waits* for SCO; if focus is lost during that wait, legacy
         * re-acquired it in `proceedStartRecording` *after* the SCO wait
         * (right before `MediaRecorder.start()`). This action restores
         * that exact legacy timing without the SCO side-effects. One
         * tiny focus-only leaf is fewer special-cases (and SRP-cleaner)
         * than disambiguating the two concerns via cross-axis reads
         * inside the [RecordingStarted] reducer arm. `request()` is
         * idempotent, so the kept early request is harmless.
         *
         * @see net.devemperor.dictate.state.Action.AudioAction.RecordingStarted
         * @see net.devemperor.dictate.state.Action.RecordingAction.ScoRouteResolved
         * @see docs/plans/2026-05-15 - dictate-cutover-completion/research/recording-audiofocus-btsco-handshake.md
         */
        data object ReacquireAudioFocus : AudioAction()

        /**
         * Apply the audio-focus runtime change derived from an
         * **external** SP-mutation that the [PipelinePrefMirror]
         * synced into `state.audio.audioFocusEnabledPref`
         * (indirection-cleanup 2026-05-21, Chunk 3.5 — C-3 removal of
         * `audioFocusListener`).
         *
         * **Why a distinct action (not reuse [ToggleAudioFocusPref]).**
         * `ToggleAudioFocusPref` *flips* the bit; this action carries
         * the explicit target value because the mirror-driven update
         * has already changed the state — the reducer must not flip
         * again. It is cascaded by [AudioModule.onCrossModuleStateChange]
         * on the `prev.audio.audioFocusEnabledPref !=
         * next.audio.audioFocusEnabledPref` AND `next.recording is
         * Active` edge. The AudioModule reducer reads the
         * already-mirrored bit and emits `Effect.ApplyAudioFocusRuntime`
         * iff the live AudioManager state differs from the wanted state
         * (same idempotency gate as the in-IME toggle path).
         */
        data class ApplyAudioFocusRuntimeFromPref(val enabled: Boolean) : AudioAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Resend-axis actions (ResendModule)
    // ════════════════════════════════════════════════════════════════

    sealed class ResendAction : Action() {
        data object ResendLastAudio : ResendAction()

        /** Long-press → ReprocessStaging entry. */
        data object ResendLastAudioLong : ResendAction()

        /**
         * Internal scheduler-fired action (UI side schedules it via
         * `Handler.postDelayed` in Phase 1). Clears the cooldown bit on
         * `ResendState.resendCooldown` so the Resend button becomes
         * clickable again.
         */
        data object ResendCooldownExpired : ResendAction()

        /** Cross-module cascade target — emitted after PipelineDone. */
        data class MarkLastAudio(val exists: Boolean) : ResendAction()

        /**
         * Service-death recovery — tell the user to paste from clipboard.
         * Dispatched by B3's recovery path when the pipeline completed but
         * no `InputConnection` was available; the result was copied to the
         * system clipboard and the keyboard header should hint "tap to
         * paste". Flips `ResendState.lastResultNeedsManualPaste = true`.
         *
         * (Issue 2.1.9 Option C; F-1 fix per
         *  `research/manual-paste-field-architecture.md` — moved from
         *  `PipelineAction.NotifyResultNeedsManualPaste` because the flag
         *  is a post-pipeline UI affordance, not pipeline-FSM state.)
         */
        data class NotifyManualPasteNeeded(val sessionId: String) : ResendAction()

        /**
         * User pasted (or dismissed) — clear the manual-paste hint. Flips
         * `ResendState.lastResultNeedsManualPaste = false`. Idempotent.
         *
         * (F-1 fix — moved from `PipelineAction.ClearManualPasteFlag`.)
         */
        data object ClearManualPasteFlag : ResendAction()
    }

    // ════════════════════════════════════════════════════════════════
    // LivePrompt-axis actions (LivePromptModule)
    // ════════════════════════════════════════════════════════════════

    sealed class LivePromptAction : Action() {
        data object EnableLivePrompt : LivePromptAction()
        data object DisableLivePrompt : LivePromptAction()
        data class ChainNext(val text: String) : LivePromptAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Language-axis actions (LanguageModule)
    // ════════════════════════════════════════════════════════════════

    sealed class LanguageAction : Action() {

        /**
         * Set the **permanent** effective language (curated-list +
         * `Pref.InputLanguagePos` write path).
         *
         * (indirection-cleanup 2026-05-21, Chunk 4.5c — A-6 / OQ-1
         * Option A.)
         *
         * Replaces the legacy IME `setLanguageFromPicker(code)` two-step
         * (`LanguageResolver.setLanguage(sp, code)` +
         * `pushPermanentLanguageToOrchestrator()`). The LanguageModule
         * reducer arm writes `state.language.effective = code` and
         * emits a persistence effect that delegates to
         * `LanguageResolver.setLanguage(sp, code)` (which curates +
         * persists). The Settings Activity continues to write the
         * curated list directly via `LanguageResolver`; this action
         * is the **in-IME** picker path.
         *
         * **Why a distinct action (not reuse [RefreshFromPref])**:
         * `RefreshFromPref` is the *read-back* of an already-persisted
         * value (no SP write); `SetEffectiveLanguage` is the picker
         * **write** that mutates the curated list + position before
         * the read-back lands.
         */
        data class SetEffectiveLanguage(val code: String) : LanguageAction()

        /** Reprocess-Staging override; `null` clears the override. */
        data class SetOverride(val code: String?) : LanguageAction()

        /**
         * Payload-bearing pref-refresh (D-13 / Epic §4 Block C1). The
         * caller resolves the permanent effective language from
         * `SharedPreferences` via
         * [net.devemperor.dictate.preferences.LanguageResolver.effectiveLanguage]
         * **before** dispatch (Pre-Dispatch-Resolution, Spec 1 §4.11) and
         * passes it as [effective]; the reducer writes it into
         * `LanguageState.effective`. This replaces the Phase-1 no-op
         * acknowledgement now that the legacy language controller (which
         * previously owned the SP read surface) is deleted.
         *
         * @property effective the resolved permanent language code (e.g.
         *   `"en"`, `"detect"`); never the `"system"` boot sentinel once
         *   the IME has resolved prefs.
         */
        data class RefreshFromPref(val effective: String) : LanguageAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Overlay-axis actions (OverlayModule)
    // ════════════════════════════════════════════════════════════════

    sealed class OverlayAction : Action() {
        /**
         * Drag-end position update. Coordinates are normalised [0..1]
         * relative to the screen dimension for the given orientation.
         */
        data class UpdateOverlayPosition(
            val portrait: Boolean,
            val x: Float,
            val y: Float,
        ) : OverlayAction()

        // ─── Onboarding (Spec 3 GAP-2) ───
        data object MarkOverlayOnboardingShown : OverlayAction()
        data object DismissOverlayOnboarding : OverlayAction()

        /**
         * Surface the in-IME permission-onboarding info-bar (Spec 3
         * §5.3 / §5.4). Dispatched by the permission-aware
         * WIDGET-toggle resolver
         * ([net.devemperor.dictate.state.layout.resolveWidgetToggleAction])
         * when the user taps the widget toggle without
         * `SYSTEM_ALERT_WINDOW` permission. The reducer sets
         * `onboardingPending = true`; the IME service renders the
         * info-bar off that flag.
         *
         * **SRP — why a dedicated arm (not overloading
         * [RequestOverlayPermission]).** "Show the explainer bar" and
         * "launch System Settings" are two distinct UX steps (Spec 3
         * §5.3 wants the explainer *before* the context-switch). A
         * single-purpose action keeps each step independently
         * dispatchable and the §5.4 reducer snippets coherent. It is
         * symmetric with [MarkOverlayOnboardingShown] /
         * [DismissOverlayOnboarding] / [RequestOverlayPermission].
         *
         * Spec 3 §5.4 left the trigger-arm explicitly "Auslöser TBD";
         * this resolves it. @see ADR-0005 Decision-History
         * 2026-05-15 entry.
         */
        data object ShowOverlayOnboarding : OverlayAction()

        /** User toggled the widget preference (T1/T2). */
        data class SetUserPrefersWidget(val prefers: Boolean) : OverlayAction()

        /**
         * Set after `CloseOverlay` cascade; blocks auto-reopen for the
         * current recording session. Cleared by [ResetSuppressBit]
         * on `Recording.Idle → Preparing` boundary.
         */
        data object SuppressAutoOverlayUntilNextSession : OverlayAction()

        /**
         * Idempotent reset of the suppress bit. Emitted by `RecordingModule`'s
         * cross-module observer on `Idle → Preparing` (session start).
         * `data object` (not `data class`) — singleton identity is optimal
         * for sealed-leaves routing.
         */
        data object ResetSuppressBit : OverlayAction()

        // ─── Permission axis (Issue 3.1.3) ───
        data class OnOverlayPermissionChanged(val granted: Boolean) : OverlayAction()
        data object RequestOverlayPermission : OverlayAction()

        /**
         * Permission-free notification fallback trigger (Spec 3 §9, O7).
         * Emitted by [net.devemperor.dictate.state.OverlayModule]'s
         * runtime-permission-loss cascade. The reducer emits
         * [net.devemperor.dictate.state.OverlayModule.Effect.NotifyOverlayPermissionRequired]
         * (no state change) so the FGS notification surfaces the
         * revoke reason when the user is outside the keyboard and no
         * in-IME info-bar can render.
         */
        data object RequestOverlayPermissionNotification : OverlayAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Feature-toggle actions (FeatureToggleModule)
    // ════════════════════════════════════════════════════════════════

    sealed class FeatureToggleAction : Action() {
        data object ToggleRewording : FeatureToggleAction()
        data object ToggleAutoFormatting : FeatureToggleAction()
        data object ToggleInstantOutput : FeatureToggleAction()
        data object ToggleAutoEnter : FeatureToggleAction()

        /**
         * **Deviation note:** `vibrationEnabled` lives on `AudioState`,
         * not `FeatureToggles`, so the reducer in `FeatureToggleModule`
         * returns `null` (cross-axis writes are forbidden by the lens,
         * ADR-0001). The legacy UI's SP-write path still works in
         * Phase 1; B3 may re-route this leaf to `Action.AudioAction`
         * when it migrates the click resolver.
         */
        data object ToggleVibration : FeatureToggleAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Theming-axis actions (ThemingModule)
    // ════════════════════════════════════════════════════════════════

    /**
     * Theme, accent-colour, overlay-characters, output-speed setters.
     * All four mirror `Pref.Theme` / `Pref.AccentColor` /
     * `Pref.OverlayCharacters` / `Pref.OutputSpeed`; SP writes are
     * performed by `PipelinePrefMirror` (C7) on state changes.
     */
    sealed class ThemingAction : Action() {
        data class SetTheme(val theme: String) : ThemingAction()
        data class SetAccentColor(val color: Int) : ThemingAction()
        data class SetOverlayCharacters(val chars: String) : ThemingAction()
        data class SetOutputSpeed(val speed: Int) : ThemingAction()
    }

    // ════════════════════════════════════════════════════════════════
    // PendingSessions-axis actions (PendingSessionsModule)
    // ════════════════════════════════════════════════════════════════

    sealed class PendingSessionsAction : Action() {
        data class Refresh(val sessions: List<PendingSession>) : PendingSessionsAction()
        data class Dismiss(val sessionId: String) : PendingSessionsAction()

        /**
         * User confirmed a Pending-Insert item — equivalent to [Dismiss]
         * on the state side (the session leaves `pendingSessions`, the
         * DB's `inserted_at` is stamped), with one additional imperative
         * side-channel at the IME service: the IME reads
         * `state.pendingSessions[...].transcribedText` and invokes
         * `currentInputConnection.commitText(...)` so the wartender
         * text lands at the cursor.
         *
         * **Why a distinct Action, not a Dismiss alias?** The state
         * mutation is identical, but the click source must be
         * unambiguous so the side-channel can fire selectively
         * (Dismiss-click means "verwerfen", AcceptAndInsert means
         * "einfügen"). Producer in `InfoBarSelector` emits this as the
         * Pending-Insert item's `confirmAction`; `Dismiss` stays the
         * `dismissAction`.
         *
         * ADR-0006 §"Cross-Module Producer pattern".
         */
        data class AcceptAndInsert(val sessionId: String) : PendingSessionsAction()
    }

    // ════════════════════════════════════════════════════════════════
    // KeyboardInput-axis actions (KeyboardInputModule — Unit state, Spec 1 §15.6)
    // ════════════════════════════════════════════════════════════════

    /**
     * Direct IME-input actions. Owned by `KeyboardInputModule`, which has
     * no sub-state axis (`Unit`) — these actions are pure effect-producers
     * that operate on the `InputConnection` and system clipboard.
     *
     * The module exists so every Dictate IME mutation flows through
     * `dispatch(action)` (F-8 invariant) — without it, Backspace/Enter/Space
     * clicks would have no module and be silently `DispatchOutcome.Unrouted`.
     */
    sealed class KeyboardInputAction : Action() {
        data object Backspace : KeyboardInputAction()
        data object EnterKey : KeyboardInputAction()
        data object SpaceKey : KeyboardInputAction()
        data class CopyToClipboard(val text: String) : KeyboardInputAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Interruption-axis actions (InterruptionModule — Phase 2)
    // ════════════════════════════════════════════════════════════════

    sealed class InterruptionAction : Action() {
        data class PhoneCallStateChanged(val incoming: Boolean) : InterruptionAction()
        data class HeadsetPlugChanged(val plugged: Boolean) : InterruptionAction()
        data class ScreenStateChanged(val awake: Boolean) : InterruptionAction()
    }
}
