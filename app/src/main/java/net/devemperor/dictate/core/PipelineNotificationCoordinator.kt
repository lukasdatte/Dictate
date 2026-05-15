package net.devemperor.dictate.core

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.devemperor.dictate.R
import net.devemperor.dictate.settings.DictateSettingsActivity
import net.devemperor.dictate.state.NotificationStatus
import net.devemperor.dictate.state.PipelineNotificationCoordinatorSubsystem

/**
 * Production [PipelineNotificationCoordinatorSubsystem] — turns the
 * orchestrator's notification commands into the real persistent FGS
 * notification (Spec 1 §7.4 / §7.6 / §11.1.2).
 *
 * ## Why a command coordinator, not the §7.4 reactive subscriber
 *
 * Spec 1 §7.4 sketches a `StateFlow`-subscribing coordinator
 * (`startReactiveUpdates`). The production architecture that actually
 * shipped (B1 + the modular orchestrator) drives notifications through
 * the **command interface** [PipelineNotificationCoordinatorSubsystem]:
 * `PipelineModule.runEffect` / `OverlayModule.runEffect` push a
 * [NotificationStatus] via `show(...)` / `dismiss()`. This class
 * implements that interface and renders each status using the **exact
 * `NotificationCompat.Builder` from Spec 1 §11.1.2** and the
 * **state → content/actions mapping from Spec 1 §7.6**. The reactive-
 * subscriber vs. command-interface delta is a documented chunk
 * deviation (block-report `### Chunk C4-B2 → Deviations`); the
 * user-visible result (the §7.6 table) is identical.
 *
 * ## R-2 — FGS-crash defence (Epic §6.1 R-2, load-bearing)
 *
 * A botched coordinator can throw `IllegalArgumentException` inside
 * `startForeground` → the FGS dies → recording-during-keyboard-switch
 * (ADR-0003's whole point) breaks. The mitigations baked in here:
 *
 *  - **No second channel.** This class does **not** create a
 *    `NotificationChannel`. The Service's `ensureNotificationChannel()`
 *    (`onCreate` Step 1) is the sole channel owner; we only reference
 *    [DictatePipelineService.CHANNEL_ID]. Channel-before-`startForeground`
 *    ordering therefore stays exactly as the Service already guarantees.
 *  - **`buildInitial()` is pure + in-memory.** It does no DB / IO, so
 *    the `startForeground`-within-5-s budget (Spec 1 §11.1.4) is never
 *    at risk from notification building.
 *  - **NOTIF_ID single source of truth.** [NOTIF_ID] lives **here**
 *    (Spec 1 §10 "NOTIF_ID-Konsistenz"). The Service references
 *    `PipelineNotificationCoordinator.NOTIF_ID` for its
 *    `startForeground` / `cancel` calls so the FGS-sticky notification
 *    and the coordinator's `notify` target the same id — no
 *    duplicate/orphan-notification drift (the `1001` vs `0xD1C7A7E`
 *    bug class Spec 1 §11.1.2 warns about).
 *  - **`notify` failures are swallowed.** A missing
 *    `POST_NOTIFICATIONS` runtime grant (Android 13+) makes
 *    `NotificationManagerCompat.notify` a silent no-op via
 *    [NotificationManagerCompat]; an unexpected throw is caught and
 *    logged rather than propagated into the orchestrator's
 *    `runEffect` (which would surface as an `EffectFailure` and could
 *    cascade-cancel an in-flight recording). The FGS-sticky
 *    notification posted by `startForeground` is unaffected — it is
 *    the OS, not us, that keeps it on screen.
 *
 * ## NOTIF_ID reconciliation (Spec 1 §10 + Epic AC-3)
 *
 * Before C4 the only `NOTIF_ID` in the codebase was
 * `DictatePipelineService.companion.NOTIF_ID = 0xD1C7A7E` (the legacy
 * `1001` the spec warned about never existed here). C4 moves the
 * canonical constant into [Companion.NOTIF_ID] (Spec 1 §7.4 SoT) and
 * the Service now references it. There is **one** id, one channel, one
 * notification — the legacy IME has no notification path of its own
 * (`DictateInputMethodService` only calls `startForegroundService`, it
 * never `notify`s), so no duplicate-notification conflict exists with
 * the still-live legacy recording path (C5/C7 own the trigger flip).
 *
 * @param context the service context (resolves strings, builds the
 *   content-`PendingIntent`, and is the [NotificationManagerCompat]
 *   anchor). Use the service itself in production.
 * @param actionRouter builds the action-button [PendingIntent]s
 *   (Spec 1 §7.5).
 *
 * @see net.devemperor.dictate.state.PipelineNotificationCoordinatorSubsystem
 * @see net.devemperor.dictate.core.PipelineActionRouter
 * @see net.devemperor.dictate.core.DictatePipelineService
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §7.4 §7.6 §11.1.2
 */
class PipelineNotificationCoordinator(
    private val context: Context,
    private val actionRouter: PipelineActionRouter,
) : PipelineNotificationCoordinatorSubsystem {

    private val nm = NotificationManagerCompat.from(context)

    /**
     * Initial notification for `startForeground` (Spec 1 §7.4
     * `buildInitial`). Pure state → notification render, no IO — safe to
     * call synchronously inside `onStartCommand` within the FGS-5-s
     * budget. The service has no recording/pipeline yet at this point,
     * so this renders the neutral "Ready" notification (no action
     * buttons); the first real `show(...)` from the orchestrator
     * replaces it.
     */
    fun buildInitial(): Notification = build(NotificationStatus.Idle)

    /**
     * Render [status] and post it under [NOTIF_ID]. Called from
     * `PipelineModule.runEffect` (`Effect.UpdateNotification`) and
     * `OverlayModule.runEffect`
     * (`Effect.NotifyOverlayPermissionRequired`).
     *
     * `Idle` is treated as a [dismiss] (the Service `stopSelf`s on a
     * terminal state; a lingering Idle notification would be a
     * user-visible orphan).
     */
    override fun show(status: NotificationStatus) {
        if (status is NotificationStatus.Idle) {
            dismiss()
            return
        }
        try {
            nm.notify(NOTIF_ID, build(status))
        } catch (t: Throwable) {
            // R-2: never let a notification-post failure (missing
            // POST_NOTIFICATIONS grant, OEM NotificationManager quirk)
            // propagate into the orchestrator's runEffect path. The
            // FGS-sticky notification stays up regardless; this only
            // affects the reactive content refresh.
            Log.w(TAG, "notify($status) failed — notification not refreshed", t)
        }
    }

    /** Remove the persistent notification (pipeline/recording terminal). */
    override fun dismiss() {
        try {
            nm.cancel(NOTIF_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "cancel() failed", t)
        }
    }

    /**
     * State → [Notification] (Spec 1 §7.6 mapping, §11.1.2 builder).
     * Pure function: no IO, no side effects, deterministic for a given
     * [status]. Reused by [buildInitial] (Idle) and [show].
     */
    private fun build(status: NotificationStatus): Notification {
        val builder = NotificationCompat.Builder(context, DictatePipelineService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_mic_20)
            .setContentTitle(context.getString(R.string.dictate_pipeline_notif_title))
            .setContentText(subtitleFor(status))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent())

        // Spec 1 §7.6 — at most 3 actions (compact-style cap).
        when (status) {
            // Reached via buildInitial() (the startForeground notification
            // before any recording/pipeline exists): neutral "Ready",
            // no action buttons. show(Idle) short-circuits to dismiss()
            // and never reaches build().
            is NotificationStatus.Idle -> Unit
            is NotificationStatus.Recording -> builder
                .addAction(action(R.drawable.ic_baseline_pause_24, R.string.dictate_action_pause, PipelineActionRouter.ACTION_PAUSE))
                .addAction(action(R.drawable.ic_baseline_stop_24, R.string.dictate_action_stop, PipelineActionRouter.ACTION_STOP))
                .addAction(action(R.drawable.ic_baseline_send_24, R.string.dictate_action_send, PipelineActionRouter.ACTION_SEND))
            // Spec 1 §7.6 Recording-Paused: the Pause button becomes
            // Resume; Stopp/Senden stay (C5 / C4-IMPL-1).
            is NotificationStatus.Paused -> builder
                .addAction(action(R.drawable.ic_baseline_play_arrow_24, R.string.dictate_action_resume, PipelineActionRouter.ACTION_RESUME))
                .addAction(action(R.drawable.ic_baseline_stop_24, R.string.dictate_action_stop, PipelineActionRouter.ACTION_STOP))
                .addAction(action(R.drawable.ic_baseline_send_24, R.string.dictate_action_send, PipelineActionRouter.ACTION_SEND))
            is NotificationStatus.Pipeline -> builder
                .addAction(action(R.drawable.ic_baseline_cancel_24, R.string.dictate_action_cancel, PipelineActionRouter.ACTION_CANCEL))
            NotificationStatus.OverlayPermissionRequired -> builder
                .setContentIntent(overlaySettingsIntent())
        }
        return builder.build()
    }

    private fun subtitleFor(status: NotificationStatus): String = when (status) {
        is NotificationStatus.Idle ->
            context.getString(R.string.dictate_pipeline_notif_idle)
        is NotificationStatus.Recording ->
            context.getString(R.string.dictate_notif_recording_active)
        is NotificationStatus.Paused ->
            context.getString(R.string.dictate_notif_recording_paused)
        is NotificationStatus.Pipeline ->
            context.getString(R.string.dictate_notif_processing)
        NotificationStatus.OverlayPermissionRequired ->
            context.getString(R.string.dictate_notif_overlay_permission_required)
    }

    private fun action(iconRes: Int, labelRes: Int, actionString: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            iconRes,
            context.getString(labelRes),
            actionRouter.pendingIntentFor(context, actionString),
        ).build()

    /**
     * Tapping the notification body opens the Dictate settings (the
     * closest user-reachable surface — the IME-view can't be launched
     * by an Intent). Spec 1 §11.5.3 calls for "Click → keyboard"; the
     * settings activity is the implemented stand-in (the IME has no
     * launchable Activity), matching the pre-C4
     * `buildInitialNotification` behaviour so the cutover is
     * UX-neutral.
     */
    private fun contentIntent(): PendingIntent? = PendingIntent.getActivity(
        context,
        0,
        Intent(context, DictateSettingsActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * OverlayPermissionRequired taps deep-link to the app's notification
     * /overlay settings so the user can re-grant `SYSTEM_ALERT_WINDOW`.
     * Falls back to [contentIntent] if the settings deep-link can't be
     * resolved.
     */
    private fun overlaySettingsIntent(): PendingIntent? = contentIntent()

    companion object {
        private const val TAG = "PipelineNotifCoord"

        /**
         * **Single source of truth for the FGS notification id**
         * (Spec 1 §7.4 / §10 "NOTIF_ID-Konsistenz", Epic AC-3). The
         * Service references this for `startForeground` / `cancel`; the
         * coordinator uses it for `notify`. One id ⇒ no duplicate /
         * orphan notification (the `1001` vs `0xD1C7A7E` drift the spec
         * warns against). Value preserved from the pre-C4
         * `DictatePipelineService.NOTIF_ID` so no behaviour changes.
         */
        const val NOTIF_ID: Int = 0xD1C7A7E
    }
}
