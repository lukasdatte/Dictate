@file:JvmName("ExternalDictationStartPolicy")

package net.devemperor.dictate.state

import net.devemperor.dictate.state.layout.resolveStartRecordingFromIdle

/**
 * Policy behind every **external** "start dictation without opening the
 * keyboard" trigger — launcher alias (S-Pen Air Command, Samsung Edge
 * panel, side-key double-press, Routines, Tasker), the static app
 * shortcut, and the Quick-Settings tile.
 *
 * # Entry chain (single canonical path)
 *
 * ```
 * StartDictationActivity  (invisible trampoline; permission pre-checks)
 *   └─ startForegroundService(ACTION_START_DICTATION)
 *        └─ DictatePipelineService.onStartCommand
 *             └─ PipelineActionRouter.dispatch → onExternalDictationStart
 *                  └─ OverlayPermissionObserver.refresh()   (axis re-sync)
 *                  └─ resolveExternalDictationStart(state, services)
 *                       └─ orchestrator.dispatch(each action, in order)
 * ```
 *
 * All three OS-level triggers funnel into this one function; there is no
 * second start path (see docs/research/2026-07-09 -
 * external-dictation-entry-points.md).
 *
 * # Behaviour table
 *
 * | recording        | widget   | viewMode  | emitted actions                                        |
 * |------------------|----------|-----------|--------------------------------------------------------|
 * | Idle             | Hidden   | KEYBOARD  | `ToggleViewModeWidget`, `StartRecording`               |
 * | Idle             | Hidden   | not KEYB. | `ResetSuppressBit`, `ToggleWidget`, `StartRecording`   |
 * | Idle             | Visible  | any       | `StartRecording`                                       |
 * | Active/Paused/…  | Hidden   | (as above)| widget-surfacing actions only (no double-start)        |
 * | Active/Paused/…  | Visible  | any       | *nothing* (full no-op — widget is already on screen)   |
 *
 * # Why two widget-open shapes
 *
 * From KEYBOARD the **canonical user trigger** is
 * [Action.ViewModeAction.ToggleViewModeWidget] — the same action the
 * edit-bar widget toggle dispatches. Its T1 cascade
 * (`OverlayModule.onCrossModuleStateChange`) performs the complete axis
 * bookkeeping: `SetUserPrefersWidget(true)` + `WidgetAction.ToggleWidget`
 * + `OverlayAction.ResetSuppressBit`. Reusing it keeps the external
 * trigger indistinguishable from an in-IME widget open.
 *
 * Outside KEYBOARD that action is wrong: from WIDGET it *closes* (T2)
 * and from HOVER it is rejected. There the policy falls back to the
 * direct pair `ResetSuppressBit` + `WidgetAction.ToggleWidget`
 * (W1: Hidden → Visible(USER)). `ResetSuppressBit` is emitted first
 * because `OverlayBackend.render` refuses to inflate while the
 * suppress-bit (set by a user X-close) is high — an explicit external
 * trigger is exactly the "I want the widget now" signal that clears it,
 * mirroring the T1 cascade's own reset.
 *
 * # Recording start rules
 *
 *  - **Only from [RecordingState.Idle].** The start action comes from the
 *    shared [resolveStartRecordingFromIdle] body, so allocation, UUID
 *    mint, the B2 auto-continuation lookup, and the IOException→toast
 *    side-channel are byte-identical to the keyboard/overlay record
 *    buttons. A storage failure returns `null` → the policy emits no
 *    start action (the toast has already fired — no silent failure).
 *  - **No double-start.** Active/Paused/Preparing recordings are left
 *    untouched; the trigger degrades to surfacing the widget (which
 *    shows the live recording controls). [RecordingState.Interrupted]
 *    likewise only surfaces the widget — continue-vs-discard is the
 *    user's explicit choice there, not the trigger's.
 *  - **ADR-0009.** A live pipeline (Preparing/Running/ReprocessStaging)
 *    does NOT block a fresh start: the new run queues behind the active
 *    one — the same rule as the keyboard's secondary record button
 *    (`resolveSecondaryRecordAction`).
 *
 * # Permission handling
 *
 * The policy itself is permission-agnostic (R.2-pure — it only reads
 * `state`). `StartDictationActivity` pre-checks RECORD_AUDIO +
 * SYSTEM_ALERT_WINDOW before the service intent fires, and the service
 * hook refreshes `state.overlay.hasPermission` before invoking this
 * policy so the `ToggleViewModeWidget` reducer gate sees a fresh axis.
 *
 * @return actions to dispatch **in list order** via
 *   `DictateOrchestrator.dispatch` (synchronous, re-snapshotting — the
 *   `StartRecording` dispatch sees the post-widget-open state).
 *
 * @see net.devemperor.dictate.core.PipelineActionRouter
 * @see net.devemperor.dictate.core.StartDictationActivity
 * @see docs/decisions/0009-pipeline-run-queue-serialized-concurrency.md
 */
/**
 * Pre-start axis correction for the **stale-true `imeViewVisible` boot
 * default** (external-dictation spec §6.3 / widget-parity spec §5.3).
 *
 * `DictateUiState.initial()` seeds `imeViewVisible = true`; the axis is
 * otherwise only written by real IME lifecycle events
 * (`onStartInputView` / `onFinishInputView`). On a **fresh process**
 * where the IME has never bound (cold external trigger — the 2026-07-11
 * Chrome/side-panel incident), the axis is therefore stale-true: the
 * overlay offers Send / third-row / secondary-record affordances whose
 * host-commit can never succeed, and a Send tap starts a pipeline whose
 * completion callback is dropped (`PipelineCallbackBridge` has no IME
 * delegate) — the widget hangs in "sending" with no in-process recovery.
 *
 * The service hook can observe the authoritative signal the pure state
 * cannot: whether an IME callback delegate is registered on the bridge.
 * `imeBound == false` proves no IME service instance is connected, hence
 * no IME view can be on screen — the stale-true axis is factually wrong
 * and is corrected by replaying exactly the two actions the IME's own
 * `onFinishInputView` dispatches (`ViewModeAction.OnImeViewHidden` then
 * `WidgetAction.OnImeViewHidden`, same order as
 * `DictateInputMethodService`). This makes the fresh-process external
 * start byte-identical to the established warm-process path (IME bound
 * earlier, view currently hidden), where the axis is already `false`.
 *
 * When the IME *is* bound (`imeBound == true`) the axis is owned by the
 * live lifecycle events and must not be touched here — returns empty.
 * Likewise a no-op when the axis is already `false`.
 *
 * Dispatched **before** [resolveExternalDictationStart] so the start
 * policy branches on the corrected state.
 *
 * @param imeViewVisible current `state.imeViewVisible` snapshot.
 * @param imeBound `PipelineCallbackBridge.currentDelegate() != null` —
 *   whether an IME-side callback delegate is registered right now.
 */
fun resolveExternalStartImeAxisCorrection(
    imeViewVisible: Boolean,
    imeBound: Boolean,
): List<Action> =
    if (imeViewVisible && !imeBound) {
        listOf(
            Action.ViewModeAction.OnImeViewHidden,
            Action.WidgetAction.OnImeViewHidden,
        )
    } else {
        emptyList()
    }

fun resolveExternalDictationStart(
    state: DictateUiState,
    services: ModuleServices,
): List<Action> {
    val actions = mutableListOf<Action>()

    if (state.widget !is WidgetState.Visible) {
        if (state.viewMode == ViewMode.KEYBOARD) {
            actions += Action.ViewModeAction.ToggleViewModeWidget
        } else {
            actions += Action.OverlayAction.ResetSuppressBit
            actions += Action.WidgetAction.ToggleWidget
        }
    }

    if (state.recording is RecordingState.Idle) {
        resolveStartRecordingFromIdle(services)?.let { actions += it }
    }

    return actions
}
