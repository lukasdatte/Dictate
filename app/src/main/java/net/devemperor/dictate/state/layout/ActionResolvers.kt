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
 * Record-button click resolver in standard (non-SEND-MODE) layouts.
 *
 * | RecordingState | Action returned                                      |
 * |----------------|------------------------------------------------------|
 * | `Idle`         | `StartRecording(MainInputConnection, allocatedFile)` |
 * | `Active`       | `StopRecordingAndSend(sessionId = "")`               |
 * | `Paused`       | `StopRecordingAndSend(sessionId = "")`               |
 * | `Preparing`    | `null` (click is a no-op while the recorder warms up)|
 *
 * **sessionId placeholder (B4/C12 scope).** The
 * [Action.RecordingAction.StopRecordingAndSend] carries a `sessionId`
 * payload that the IME-side click handler does NOT yet know — sessionId
 * generation happens in the recording-→-pipeline cross-module cascade
 * (Spec 1 §15.2 / F-2). For C12 we pass an empty string; the receiving
 * module overrides it via the cascade. This will be revisited when
 * C14/C15 wires the click-action into the orchestrator.
 *
 * **IOException side-channel.** `audioFileFactory.allocate()` may fail;
 * the resolver fires a toast on `services.toastSink` and returns `null`.
 */
fun resolveRecordAction(state: DictateUiState, services: ModuleServices): Action? =
    when (state.recording) {
        RecordingState.Idle -> {
            val file = try {
                services.audioFileFactory.allocate()
            } catch (e: java.io.IOException) {
                // B4-VAL F-4: toast via @StringRes overload so the user-visible
                // message goes through Android's i18n machinery (Spec 2 §8.5).
                services.toastSink.show(R.string.dictate_storage_full)
                Log.w(TAG, "audioFileFactory.allocate failed", e)
                return null
            }
            Action.RecordingAction.StartRecording(
                target = InsertionTarget.INPUT_CONNECTION,
                audioFile = file,
            )
        }

        is RecordingState.Active -> Action.RecordingAction.StopRecordingAndSend(sessionId = "")
        is RecordingState.Paused -> Action.RecordingAction.StopRecordingAndSend(sessionId = "")
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
        is PipelineUiState.Running -> Action.FeatureToggleAction.ToggleAutoEnter
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
 * OVERLAY_RECORD click resolver — Pre-Dispatch-Allocation in WIDGET mode.
 *
 * Mirrors [resolveRecordAction] for the keyboard surface, but only emits
 * when [DictateUiState.viewMode] is [ViewMode.WIDGET] (HOVER has no
 * InputConnection target — the visibility predicate hides the button
 * there, and this defensive `null` returns nothing if a stale click
 * arrives during a ViewMode transition).
 *
 * # IOException side-channel
 *
 * Identical to [resolveRecordAction]: `services.audioFileFactory.allocate()`
 * may fail; the resolver fires a toast on `services.toastSink` and
 * returns `null`. The reducer never sees the failure (R.2 Pure-Reducer
 * invariant). See Spec 3 §3.1 + §4.2.
 *
 * @see resolveRecordAction
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md §3.1
 */
fun resolveOverlayRecordAction(state: DictateUiState, services: ModuleServices): Action? {
    if (state.viewMode != ViewMode.WIDGET) return null
    if (state.recording !is RecordingState.Idle) return null
    val file = try {
        services.audioFileFactory.allocate()
    } catch (e: java.io.IOException) {
        services.toastSink.show(R.string.dictate_storage_full)
        Log.w(TAG, "audioFileFactory.allocate failed (overlay record)", e)
        return null
    }
    return Action.RecordingAction.StartRecording(
        target = InsertionTarget.INPUT_CONNECTION,
        audioFile = file,
    )
}

/**
 * OVERLAY_PAUSE click resolver — toggles Pause / Resume on the current
 * recording. `null` outside Active / Paused.
 *
 * @see resolvePauseAction (keyboard-side sibling)
 */
fun resolveOverlayPauseAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action? = when (state.recording) {
    is RecordingState.Paused -> Action.RecordingAction.ResumeRecording
    is RecordingState.Active -> Action.RecordingAction.PauseRecording
    else -> null
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
