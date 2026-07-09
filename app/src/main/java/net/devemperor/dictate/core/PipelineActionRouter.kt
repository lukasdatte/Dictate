package net.devemperor.dictate.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import net.devemperor.dictate.state.Action

/**
 * Notification action-button → [Action] back-channel (Spec 1 §7.5).
 *
 * **Single responsibility — pure mapping.** The router has exactly two
 * jobs and no UI / lifecycle / notification-build knowledge:
 *
 *  1. [pendingIntentFor] builds the [PendingIntent] a notification
 *     action-button fires. The intent targets [DictatePipelineService]
 *     so the button works even while the IME-view is dead (the FGS
 *     survives a keyboard switch — ADR-0003's whole point; the user
 *     can `[Stopp]` a recording from the system tray without switching
 *     back to the Dictate keyboard).
 *  2. [dispatch] decodes an inbound action-[Intent] (delivered to the
 *     service's `onStartCommand` when the user taps a button) into the
 *     matching typed [Action] and forwards it through [dispatchAction].
 *
 * **Why a `dispatchAction` lambda and not a `DictateOrchestrator`
 * field?** Mirrors the established `emitAction: (Action) -> Unit`
 * provider-lambda pattern used by the other `core` adapters
 * ([RecordingHardwareAdapter], the [PipelineRunnerSubsystemAdapter]
 * config-resolver seam). It keeps the router unit-testable with a
 * capturing fake and avoids a construction-order coupling to the
 * orchestrator (which is built later in the `onCreate` composition
 * root). The Service wires `binder.dispatch` / `orchestrator.dispatch`
 * here.
 *
 * **R-2 note (FGS crash defence).** This class performs no FGS calls
 * and creates no notification channel — it only builds `PendingIntent`s
 * and maps intents to actions. The `IllegalArgumentException`-in-
 * `startForeground` failure mode (Epic §6.1 R-2) lives entirely in
 * [PipelineNotificationCoordinator] + the Service; the router cannot
 * trip it.
 *
 * @param dispatchAction sink for decoded actions — `orchestrator.dispatch`
 *   in production wiring. Called on the thread the inbound action-Intent
 *   arrives on (the main thread, via `Service.onStartCommand`).
 * @param onExternalDictationStart hook for [ACTION_START_DICTATION]
 *   (2026-07-09 external-dictation-entry-points). Unlike the
 *   notification-button arms this is NOT a 1:1 intent→Action mapping —
 *   the external start needs a state snapshot + `ModuleServices` (file
 *   allocation, continuation lookup) to build its action list, so the
 *   Service wires a lambda that runs
 *   [net.devemperor.dictate.state.resolveExternalDictationStart] and
 *   dispatches the result. Keeping it a lambda preserves the router's
 *   pure-mapper testability. Defaults to a no-op so existing
 *   notification-only constructions stay source-compatible.
 *
 * @see net.devemperor.dictate.core.PipelineNotificationCoordinator
 * @see net.devemperor.dictate.state.Action
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §7.5
 */
class PipelineActionRouter(
    private val dispatchAction: (Action) -> Unit,
    private val onExternalDictationStart: () -> Unit = {},
) {

    /**
     * Decode an inbound notification-action [Intent] and forward the
     * matching [Action]. Called from `DictatePipelineService.onStartCommand`
     * when a `[Pause]` / `[Stopp]` / `[Senden]` / `[Abbrechen]` /
     * `[Einfügen]` / `[Verwerfen]` / `[Fortsetzen]` button is tapped.
     *
     * Unknown / null actions are ignored (the service is also started
     * for non-action reasons — the first FGS start carries no action).
     */
    fun dispatch(intent: Intent?) {
        when (intent?.action) {
            ACTION_PAUSE -> dispatchAction(Action.RecordingAction.PauseRecording)
            ACTION_RESUME -> dispatchAction(Action.RecordingAction.ResumeRecording)
            ACTION_STOP -> dispatchAction(Action.RecordingAction.StopRecording)
            // FN-4: `StopRecordingAndSend` is a payload-less data object —
            // the reducer reads the sessionId from `state.recording`
            // (B1-C2-A2 F-10). The notification `[Senden]` button maps to
            // it directly, no `EXTRA_SESSION_ID` needed.
            ACTION_SEND -> dispatchAction(Action.RecordingAction.StopRecordingAndSend)
            // `CancelPipeline(sessionId = null)` — the reducer cancels
            // the active pipeline regardless of session (the
            // notification only ever shows the single in-flight
            // pipeline; Spec 1 §7.5 dispatches the no-arg form).
            ACTION_CANCEL -> dispatchAction(Action.PipelineAction.CancelPipeline())
            ACTION_INSERT -> intent.getStringExtra(EXTRA_SESSION_ID)
                ?.let { dispatchAction(Action.PipelineAction.ConfirmInsertion(it)) }
            ACTION_DISMISS -> intent.getStringExtra(EXTRA_SESSION_ID)
                ?.let { dispatchAction(Action.PipelineAction.DismissResult(it)) }
            // External dictation trigger (launcher alias / app shortcut /
            // QS tile via StartDictationActivity). Routed to the injected
            // hook — see the constructor-param KDoc for why this arm is
            // not a plain dispatchAction mapping.
            ACTION_START_DICTATION -> onExternalDictationStart()
            else -> Unit
        }
    }

    /**
     * Build the [PendingIntent] for a notification action-button.
     *
     * Targets [DictatePipelineService] via [PendingIntent.getService] so
     * the tap re-enters `onStartCommand` (where [dispatch] decodes it) —
     * this works regardless of whether the IME-view is currently alive.
     *
     * @param ctx a Context able to resolve the service component (the
     *   service itself in production).
     * @param action one of the `ACTION_*` constants.
     * @param sessionId attached as [EXTRA_SESSION_ID] for the
     *   result-stage buttons (`[Einfügen]` / `[Verwerfen]`), which need
     *   to disambiguate concurrent pending sessions; `null` for the
     *   recording/pipeline buttons whose target is unambiguous from
     *   state.
     */
    fun pendingIntentFor(
        ctx: Context,
        action: String,
        sessionId: String? = null,
    ): PendingIntent {
        val intent = Intent(ctx, DictatePipelineService::class.java).apply {
            this.action = action
            sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
        }
        // FLAG_IMMUTABLE is mandatory on API 31+ (and good hygiene
        // below): the system fills no extras into our intent. The
        // request-code is the action's hash so distinct buttons get
        // distinct PendingIntents but the same button reuses one
        // (FLAG_UPDATE_CURRENT refreshes the sessionId extra).
        return PendingIntent.getService(
            ctx,
            requestCodeFor(action, sessionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Distinct request-code per (action, sessionId) so the result-stage
     * buttons for two different pending sessions don't collide on the
     * `action.hashCode()`-only scheme (a `FLAG_UPDATE_CURRENT` collision
     * would make `[Einfügen]` for session A silently re-target session
     * B). Recording/pipeline buttons pass `sessionId == null` so they
     * keep the stable `action.hashCode()` code.
     */
    private fun requestCodeFor(action: String, sessionId: String?): Int =
        if (sessionId == null) action.hashCode()
        else (action + '\u0000' + sessionId).hashCode()

    companion object {
        const val ACTION_PAUSE = "net.devemperor.dictate.PAUSE"
        const val ACTION_RESUME = "net.devemperor.dictate.RESUME"
        const val ACTION_STOP = "net.devemperor.dictate.STOP"
        const val ACTION_SEND = "net.devemperor.dictate.SEND"
        const val ACTION_CANCEL = "net.devemperor.dictate.CANCEL"
        const val ACTION_INSERT = "net.devemperor.dictate.INSERT"
        const val ACTION_DISMISS = "net.devemperor.dictate.DISMISS"

        /**
         * Canonical external-entry action (2026-07-09
         * external-dictation-entry-points): "start a dictation without
         * opening the keyboard". Fired exclusively by
         * [StartDictationActivity] (which itself is the funnel for the
         * launcher alias, the static app shortcut, and the QS tile).
         */
        const val ACTION_START_DICTATION = "net.devemperor.dictate.START_DICTATION"
        const val EXTRA_SESSION_ID = "session_id"
    }
}
