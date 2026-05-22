@file:JvmName("LayoutActionResolvers")

package net.devemperor.dictate.state.layout

import android.util.Log
import net.devemperor.dictate.R
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.ModuleServices
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.ViewMode
import net.devemperor.dictate.state.WidgetState
import java.util.UUID

/**
 * Shared `ButtonSlot.actionResolver` helpers consumed by the
 * [LayoutCatalog].
 *
 * # The nullable-return contract (R.3)
 *
 * Resolvers return `Action?`. `null` means "click is structurally
 * meaningless in the current state" — the click handler short-circuits
 * via `?.let { onAction(it) }` so no orchestrator dispatch happens and no
 * `Unrouted`-log fires (Spec 2 §3.2 KDoc, §6 wireStaticHandlers).
 *
 * **Why a separate file?** Each resolver carries non-trivial dispatch
 * logic — record-btn alone has three live branches (Idle / Active /
 * Paused). Keeping them as named top-level functions plays into Kotlin
 * method references (`actionResolver = ::resolveRecordAction`) and gives
 * the unit-test layer typed seams to assert on (Spec 2 §14.2).
 *
 * # IOException handling for `resolveRecordAction`
 *
 * `services.audioFileFactory.allocate()` can throw `IOException`
 * (mkdirs/storage failure). The resolver translates that into a
 * user-visible toast via `services.toastSink.show(...)`, logs a warning,
 * and returns `null` — the click ends as a silent no-op. The reducer
 * never sees the failure (R.2 Pure-Reducer invariant). See Spec 1
 * §4.11.10 / F1.
 *
 * @see net.devemperor.dictate.state.layout.LayoutCatalog
 * @see net.devemperor.dictate.state.layout.ButtonSlot.actionResolver
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §8.5
 */

private const val TAG: String = "LayoutResolver"

/**
 * Mint a fresh session UUID for a new recording (F-10, Epic §4 Block A2).
 *
 * Both keyboard-surface and overlay-surface record resolvers start a
 * recording and must supply a `sessionId` for the FSM to carry through
 * to the pipeline trigger. Centralised here so the two call-sites stay
 * identical (R.15 — UUID strings throughout, matching the IME's
 * `UUID.randomUUID().toString()` in `DictateInputMethodService` and
 * `PipelineOrchestrator`). B3 will replace the *origin* (the IME's
 * pre-allocated `JobExecutor.register()` id flows in instead) without
 * touching the resolver shape.
 */
private fun newSessionId(): String = UUID.randomUUID().toString()

/**
 * Record-button click resolver in standard (non-SEND-MODE) layouts.
 *
 * | RecordingState | Action returned                                      |
 * |----------------|------------------------------------------------------|
 * | `Idle`         | `StartRecording(target, allocatedFile, sessionId)`   |
 * | `Active`       | `StopRecordingAndSend`                               |
 * | `Paused`       | `StopRecordingAndSend`                               |
 * | `Preparing`    | `null` (click is a no-op while the recorder warms up)|
 *
 * **sessionId source (F-10, Epic §4 Block A2).** The resolver mints a
 * fresh UUID and threads it into
 * [Action.RecordingAction.StartRecording]; the RecordingModule FSM
 * carries it through `Preparing → Active → Paused` and the
 * `StopRecordingAndSend` reducer arm reads it back off the live state.
 * The earlier empty-string-payload sentinel on `StopRecordingAndSend`
 * is gone — the FSM is the single source of the id. When B3
 * flips the IME recording trigger to dispatch, the IME's pre-allocated
 * `JobExecutor.register()` UUID is routed in here instead of a fresh
 * mint (the seam is the same; only the id origin moves).
 *
 * **IOException side-channel.** `audioFileFactory.allocate()` may fail;
 * the resolver fires a toast on `services.toastSink` and returns `null`.
 */
fun resolveRecordAction(state: DictateUiState, services: ModuleServices): Action? {
    return when (state.recording) {
        RecordingState.Idle -> {
            // B2 / ADR-0008 §"Auto-Continuation". On every Idle Record-tap
            // we first ask the ContinuationLookup whether the most recent
            // session is a fresh RECORDING_INTERRUPTED row eligible for
            // continuation. The composite (RecordingContinuationLookup)
            // does the DB lookup, segment-list probe, MediaExtractor codec
            // read, and allocateNext — see its KDoc for the eligibility
            // chain. A non-null result has already mutated repository
            // state (next segment is reserved on disk + appended to
            // audio_file_paths). Returning the StartRecordingContinuation
            // action skips the fresh allocate + UUID mint below — both
            // would be wasteful and the new fresh allocate would orphan
            // a file the user could see in cleanup logs.
            val continuation = services.continuationLookup.lookup()
            if (continuation != null) {
                return Action.RecordingAction.StartRecordingContinuation(
                    target = InsertionTarget.INPUT_CONNECTION,
                    audioFile = continuation.nextSegmentFile,
                    sessionId = continuation.sessionId,
                    codecParams = continuation.codecParams,
                )
            }
            // Block A4 (recording-stack-completion) — Initial-File-Cutover.
            // Mint the sessionId BEFORE allocate so we can ask the
            // AudioFileRepository for `sess_{sid}_seg1.m4a`. This unifies
            // the naming convention: initial file + every rolling segment
            // share the `sess_{sid}_seg*` prefix that `segments(sid)`
            // scans for. Without this the initial file was named
            // `rec_{ts}_{uuid8}.m4a` (from CacheDirAudioFileFactory) and
            // therefore invisible to the multi-segment muxer at upload
            // time — which is exactly the "only the latest audio chunk
            // reached the AI" bug observed on-device on 2026-05-22.
            val sessionId = newSessionId()
            val file = try {
                services.audioFileRepository.allocateFirst(sessionId)
            } catch (e: java.io.IOException) {
                // B4-VAL F-4: toast via @StringRes overload so the user-visible
                // message goes through Android's i18n machinery (Spec 2 §8.5).
                services.toastSink.show(R.string.dictate_storage_full)
                Log.w(TAG, "audioFileRepository.allocateFirst failed", e)
                return null
            }
            Action.RecordingAction.StartRecording(
                target = InsertionTarget.INPUT_CONNECTION,
                audioFile = file,
                sessionId = sessionId,
            )
        }

        is RecordingState.Active -> Action.RecordingAction.StopRecordingAndSend
        is RecordingState.Paused -> Action.RecordingAction.StopRecordingAndSend
        is RecordingState.Preparing -> null
    }
}

/**
 * Record-button **long-press** resolver (behaviour group G2,
 * render-path-cutover.md §3 / §7 A1).
 *
 * Thin state→Action mapping symmetric with [resolveRecordAction]: it
 * emits [Action.RecordingAction.OnRecordLongPress] whenever a recording
 * session is in flight ([RecordingState.Active] / [RecordingState.Paused])
 * and `null` otherwise. The **2-mode body** (Idle → Settings+file-picker
 * vs Active/Paused → autoSwitch+stop) is resolved in
 * [net.devemperor.dictate.state.RecordingModule]'s reducer from
 * `state.recording` — see [Action.RecordingAction.OnRecordLongPress]
 * KDoc for the full A1 rationale (the Idle Activity-launch is an
 * IME-side concern wired in CR4; this resolver returns `null` for Idle
 * so no pointless action reaches the orchestrator, R.3).
 *
 * **Why not just always emit `OnRecordLongPress` and let the reducer
 * decide?** The R.3 nullable-resolver contract pushes structurally
 * meaningless interactions out *before* dispatch (no
 * `DispatchOutcome.Rejected` log-spam). Idle + Preparing long-press
 * produce no FSM transition, so the resolver short-circuits them here —
 * the reducer's `null` is the second defence layer, not the first.
 *
 * Dormant until CR4 (the new long-press listener is not the live one
 * for RECORD until the legacy `MainButtonsController` drive is removed —
 * RR-1 no-double-wire).
 */
fun resolveRecordLongPressAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action? =
    when (state.recording) {
        is RecordingState.Active,
        is RecordingState.Paused -> Action.RecordingAction.OnRecordLongPress
        RecordingState.Idle,
        is RecordingState.Preparing -> null
    }

/**
 * Record-button click resolver while the pipeline is live (SEND_MODE).
 *
 * While the pipeline is `Running` the record button acts as an
 * auto-enter-toggle (the visual "↵" decoration on the button text).
 * In any other pipeline state (including `Preparing`) the click is
 * structurally meaningless and the resolver returns `null`.
 */
fun resolveRecordActionPipeline(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action? =
    when (state.pipeline) {
        // Per-run auto-enter toggle — distinct from
        // FeatureToggleAction.ToggleAutoEnter (which would flip the
        // global Pref.AutoEnter). The in-pipeline toggle must NOT
        // mutate the global pref; it only flips the per-run
        // autoEnterActive flag for this one run. See
        // Action.PipelineAction.ToggleRunningAutoEnter.
        //
        // #AE-DEEP2: also dispatch during Preparing — the second
        // SEND-tap typically lands in the 500ms–2s upload window
        // before the runner emits StartPipeline (Preparing → Running).
        // Pre-fix the resolver returned null for Preparing, so taps in
        // that window were silently swallowed.
        is PipelineUiState.Preparing -> Action.PipelineAction.ToggleRunningAutoEnter
        is PipelineUiState.Running -> Action.PipelineAction.ToggleRunningAutoEnter
        else -> null
    }

/**
 * Trash-button click resolver.
 *
 * | Sub-state                                                  | Action                                |
 * |-----------------------------------------------------------|---------------------------------------|
 * | `pipeline is ReprocessStaging`                            | `CancelReprocessStaging(sessionId)`   |
 * | `recording is Idle && pipeline is Idle`                   | `null` (visibility predicate hides it)|
 * | otherwise (recording active/paused, or pipeline Preparing)| `CancelRecording`                     |
 */
fun resolveTrashAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action? {
    val pipe = state.pipeline
    return when {
        pipe is PipelineUiState.ReprocessStaging ->
            Action.PipelineAction.CancelReprocessStaging(pipe.sessionId)
        state.recording is RecordingState.Idle && state.pipeline is PipelineUiState.Idle ->
            null
        else -> Action.RecordingAction.CancelRecording
    }
}

/**
 * Pause-button click resolver.
 *
 * Toggles between `PauseRecording` and `ResumeRecording`; `null` outside
 * the Active/Paused sub-states (the visibility predicate is supposed to
 * hide the button there, but the second layer of defence keeps the
 * orchestrator-dispatch path clean per R.3).
 */
fun resolvePauseAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action? =
    when (state.recording) {
        is RecordingState.Paused -> Action.RecordingAction.ResumeRecording
        is RecordingState.Active -> Action.RecordingAction.PauseRecording
        else -> null
    }

/**
 * Resolver for the SendStaging click in `KEYBOARD_REPROCESS_STAGING`.
 *
 * Reads the active `ReprocessStaging.sessionId` from the state and emits
 * [Action.PipelineAction.SendStaging]; `null` when the pipeline isn't in
 * staging (defensive — the visibility predicate excludes this case).
 */
fun resolveSendStagingAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action? =
    (state.pipeline as? PipelineUiState.ReprocessStaging)
        ?.let { Action.PipelineAction.SendStaging(it.sessionId) }

/**
 * Resolver for the trash-button click in `KEYBOARD_REPROCESS_STAGING` —
 * cancels the staging session by id.
 */
fun resolveCancelStagingAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action? =
    (state.pipeline as? PipelineUiState.ReprocessStaging)
        ?.let { Action.PipelineAction.CancelReprocessStaging(it.sessionId) }

/**
 * OVERLAY_RECORD click resolver — Variante 2a merged RECORD+SEND slot
 * (dictate-widget-integration §6.5, §8.2 Chunk 2.2).
 *
 * The user-requirement (2026-05-21) is that the overlay record-button is
 * "exakt der gleiche Button" as the keyboard `record_btn`. That means
 * one single slot drives all four recording sub-states **and** both
 * live pipeline sub-states:
 *
 * | viewMode | recording   | pipeline             | Action returned                          |
 * |----------|-------------|----------------------|------------------------------------------|
 * | WIDGET   | Idle        | Idle                 | `StartRecording(...)`                    |
 * | WIDGET   | Active      | Idle                 | `StopRecordingAndSend`                   |
 * | WIDGET   | Paused      | Idle                 | `StopRecordingAndSend`                   |
 * | WIDGET   | Preparing   | Idle                 | `null` (recorder warming up)             |
 * | WIDGET   | any         | Preparing / Running  | `ToggleRunningAutoEnter` (auto-enter ↵)  |
 * | HOVER    | any         | any                  | `null` (no InputConnection target)       |
 *
 * **HOVER gate.** User-Requirement §2 verbatim: "Senden darf nicht
 * möglich sein, während gerade kein Tastaturinput verfügbar ist". HOVER
 * is the only ViewMode where the IME-View is hidden and
 * `getCurrentInputConnection()` returns `null`; structurally any
 * SEND-class action would commit text into nothing. The
 * `enabledResolver` already disables the button visually
 * ([resolveOverlayRecordEnabled]); this defensive `null`-return is the
 * second layer per R.3 (a Race-Window click that slips through during a
 * ViewMode transition is also a no-op).
 *
 * **Reuse of the keyboard-surface bodies.** Both branches delegate to
 * the existing keyboard-surface resolvers:
 *
 *  - non-pipeline branch → [resolveRecordAction] (the SAME body —
 *    `audioFileFactory.allocate()`, fresh UUID, IOException → toast).
 *  - pipeline branch → [resolveRecordActionPipeline] (the SAME body —
 *    `ToggleRunningAutoEnter` for Preparing/Running, `null` else).
 *
 * Side-effect parity is therefore guaranteed by composition — no
 * separate IOException-handling, no separate UUID-mint.
 *
 * @see resolveRecordAction (keyboard non-pipeline body)
 * @see resolveRecordActionPipeline (keyboard pipeline body)
 * @see resolveOverlayRecordEnabled (matching enabled-state predicate)
 * @see docs/plans/2026-05-21 - dictate-widget-integration/dictate-widget-integration.md §8.2 Chunk 2.2
 */
fun resolveOverlayRecordAction(state: DictateUiState, services: ModuleServices): Action? {
    // HOVER-gate — User-Req: "Senden darf nicht möglich sein, während
    // gerade kein Tastaturinput verfügbar ist". Catches both the
    // pipeline auto-enter toggle (which would no-op against a missing
    // InputConnection downstream anyway) and the StopRecordingAndSend
    // case (which would commit transcript into nothing).
    if (state.viewMode != ViewMode.WIDGET) return null

    // While the pipeline is live, the button is a per-run auto-enter
    // toggle (symmetric to the keyboard SEND_MODE record button).
    if (state.pipeline is PipelineUiState.Preparing ||
        state.pipeline is PipelineUiState.Running
    ) {
        return resolveRecordActionPipeline(state, services)
    }

    // B3.4 (plan §1.2 + W2 KDoc): the Send-button morphs into a
    // **Pause-Toggle** while the floating widget is visible. The
    // user-requirement is "Pause is reachable without unhiding the
    // keyboard": in widget-mode the keyboard is collapsed, so a
    // separate OVERLAY_PAUSE slot would be redundant. The Send-button
    // takes that role — Active→Paused, Paused→Active. The legacy
    // `StopRecordingAndSend` path is no longer reachable from the
    // overlay surface; sends are deferred until the user re-opens
    // the IME-View (where commitTextToInputConnection has a guaranteed
    // InputConnection — see the B3.5 host-commit guard).
    //
    // This is a deliberate behaviour change vs. ADR-0005's overlay
    // semantics — ADR-0008 §"Send-during-widget" captures the
    // motivation: a tap-to-send during widget-mode previously committed
    // text into the wrong InputConnection (the Settings app, browser
    // address bar, … whatever the host was when the widget surfaced).
    if (state.widget is WidgetState.Visible) {
        when (state.recording) {
            is RecordingState.Active -> return Action.RecordingAction.PauseRecording
            is RecordingState.Paused -> return Action.RecordingAction.ResumeRecording
            RecordingState.Idle,
            is RecordingState.Preparing -> {
                // Fall through to the keyboard-surface body — Idle
                // path becomes StartRecording / StartRecordingContinuation
                // via the resolver below; Preparing returns null.
            }
        }
    }

    // Otherwise: same Start/Stop semantics as the keyboard surface —
    // delegate so IOException handling + UUID minting + the B2
    // ContinuationLookup branch stay byte-identical (R.3 /
    // single-source-of-side-effect).
    return resolveRecordAction(state, services)
}

/**
 * `enabledResolver` for the OVERLAY_RECORD slot (Variante 2a, §8.2
 * Chunk 2.3).
 *
 * Symmetric to [resolveOverlayRecordAction]: the button is enabled iff
 * the resolver would return a non-null action. Centralised so the
 * `enabledResolver` / `alphaResolver` / `actionResolver` slot fields
 * cannot drift apart — and so HOVER-disabled is **one** branch in
 * **one** function (the user requirement).
 *
 * | viewMode | recording               | pipeline             | enabled |
 * |----------|-------------------------|----------------------|---------|
 * | HOVER    | any                     | any                  | `false` |
 * | KEYBOARD | any                     | any                  | `false` |
 * | WIDGET   | Preparing               | Idle                 | `false` |
 * | WIDGET   | any other recording     | Preparing / Running  | `true`  |
 * | WIDGET   | Idle / Active / Paused  | Idle                 | `true`  |
 *
 * Note that the *visibility* predicate stays simple (`true`) — the
 * button is always present in the overlay layout; the `enabled` /
 * `alpha` axes carry the WIDGET vs HOVER distinction. This matches the
 * keyboard surface's `record_btn`, which is also always-visible.
 */
fun resolveOverlayRecordEnabled(state: DictateUiState): Boolean {
    if (state.viewMode != ViewMode.WIDGET) return false
    if (state.pipeline is PipelineUiState.Preparing ||
        state.pipeline is PipelineUiState.Running
    ) {
        // Auto-enter toggle is available throughout the live pipeline,
        // matching the keyboard SEND_MODE behaviour
        // (#AE-OPTIK2 / #AE-DEEP2 — enabledResolver intentionally true
        // even in Preparing so the double-tap-to-toggle is reachable).
        return true
    }
    // Outside the live pipeline the recorder state alone gates: the
    // <100 ms Preparing window is the only spot where the click is
    // structurally meaningless (recorder warming up; same as keyboard).
    return state.recording !is RecordingState.Preparing
}

/**
 * OVERLAY_CLOSE click resolver — differential behaviour per ViewMode
 * (Spec 3 §6 + §3.1).
 *
 * | viewMode  | Action emitted                                        |
 * |-----------|-------------------------------------------------------|
 * | WIDGET    | `Action.ViewModeAction.ToggleViewModeWidget`          |
 * | HOVER     | `Action.ViewModeAction.CloseOverlay`                  |
 * | KEYBOARD  | `null` (button is hidden by visibility predicate)     |
 *
 * The HOVER → KEYBOARD transition fans a cancel-cascade through
 * [net.devemperor.dictate.state.OverlayModule.onCrossModuleStateChange];
 * the resolver here only emits the **trigger** action.
 */
fun resolveOverlayCloseAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action? = when (state.viewMode) {
    ViewMode.WIDGET -> Action.ViewModeAction.ToggleViewModeWidget
    ViewMode.HOVER -> Action.ViewModeAction.CloseOverlay
    ViewMode.KEYBOARD -> null
}

/**
 * WIDGET_TOGGLE click resolver — permission-aware (Spec 3 §8 /
 * ADR-0005 §"Required mechanics" #3, B5 repair-wave F-2).
 *
 * | `state.overlay.hasPermission` | Action emitted                              |
 * |-------------------------------|---------------------------------------------|
 * | `true`                        | `Action.ViewModeAction.ToggleViewModeWidget`|
 * | `false`                       | `Action.OverlayAction.ShowOverlayOnboarding`|
 *
 * `hasPermission` is the **mirrored axis** kept fresh by
 * `OverlayPermissionObserver` (the IME calls `refresh()` in
 * `onStartInputView`, B5 F-3) — this resolver reads *state*, never
 * `Settings.canDrawOverlays`, so it stays R.2-pure.
 *
 * Defence-in-depth: even when permission is present and this resolver
 * emits `ToggleViewModeWidget`, `ViewModeModule.reduce`'s
 * `!hasPermission ⇒ null` guard still refuses a widget switch if the
 * axis is stale (ADR-0005 §8 "the reducer still refuses"). The two
 * checks are intentionally redundant.
 */
fun resolveWidgetToggleAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action =
    if (state.overlay.hasPermission) {
        Action.ViewModeAction.ToggleViewModeWidget
    } else {
        Action.OverlayAction.ShowOverlayOnboarding
    }
