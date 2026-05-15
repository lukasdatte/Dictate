package net.devemperor.dictate.core

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.R
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.NotificationStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric unit tests for the C4-B2
 * [PipelineNotificationCoordinator] + [PipelineActionRouter]
 * (Spec 1 §7.4/§7.5/§7.6/§11.1.2, Epic AC-2 + AC-3).
 *
 * **Quality-Gate K-4 exception (justified opt-out).** Per the
 * state-file `### Test-Strategy`, Robolectric is opt-out by default —
 * explicit justification required. The justifications here:
 *
 *  - The coordinator's whole contract is `NotificationManager.notify` /
 *    `.cancel` with a real `NotificationCompat.Builder` — the
 *    Recording-status `[Pause][Stopp][Senden]` action-button assertion
 *    (Spec 1 §7.6 / §10 Block-2 "zeigt korrekte Action-Buttons")
 *    cannot be made without a shadow `NotificationManager`.
 *  - String-resource resolution (`context.getString`) for the
 *    OQ-3/FN-2 notification strings needs a real (shadow) resources
 *    table.
 *  - The router builds real `PendingIntent.getService` instances
 *    targeting [DictatePipelineService]; decoding them back to the
 *    typed [Action] needs Robolectric's shadow `PendingIntent`.
 *
 * The notification channel is **not** created by the coordinator
 * (R-2: the Service's `ensureNotificationChannel` is the sole owner);
 * these tests create it in [setUp] exactly as the Service would, so
 * `notify` does not silently no-op on API ≥ 26.
 *
 * @see net.devemperor.dictate.core.PipelineNotificationCoordinator
 * @see net.devemperor.dictate.core.PipelineActionRouter
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PipelineNotificationCoordinatorTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    private val dispatched = mutableListOf<Action>()
    private lateinit var router: PipelineActionRouter
    private lateinit var coordinator: PipelineNotificationCoordinator

    @Before
    fun setUp() {
        dispatched.clear()
        // Mirror DictatePipelineService.ensureNotificationChannel (Step
        // 1) — the coordinator deliberately does NOT create a channel
        // (R-2). Without it, NotificationManagerCompat.notify is a
        // silent no-op on API ≥ 26 and every assertion would be vacuous.
        nm.createNotificationChannel(
            android.app.NotificationChannel(
                DictatePipelineService.CHANNEL_ID,
                "Dictate pipeline",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        router = PipelineActionRouter(dispatchAction = { dispatched += it })
        coordinator = PipelineNotificationCoordinator(app, router)
    }

    @After
    fun tearDown() {
        nm.cancelAll()
    }

    private fun posted() = shadowOf(nm).getNotification(PipelineNotificationCoordinator.NOTIF_ID)

    // ──────────────────────────────────────────────────────────────────
    // NOTIF_ID single-source-of-truth (Epic AC-3 / Spec 1 §10)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun notifId_isThePreservedCanonicalValue() {
        // The SoT value must equal the pre-C4 DictatePipelineService
        // constant (0xD1C7A7E) so the cutover changes no behaviour and
        // there is no `1001`-vs-`0xD1C7A7E` drift.
        assertEquals(0xD1C7A7E, PipelineNotificationCoordinator.NOTIF_ID)
    }

    // ──────────────────────────────────────────────────────────────────
    // Spec 1 §7.6 — content + action mapping (AC-2)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun show_recording_postsNotificationWithPauseStopSendActions() {
        coordinator.show(NotificationStatus.Recording(sessionId = "s-1"))

        val n = posted()
        assertNotNull("Recording status must post a notification", n)
        assertEquals(
            "Recording subtitle (Spec 1 §7.6 Recording-Active)",
            app.getString(R.string.dictate_notif_recording_active),
            shadowOf(n).contentText,
        )
        // Spec 1 §7.6: Recording-Active → [Pause] [Stopp] [Senden].
        assertEquals("Recording must show exactly 3 action buttons", 3, n!!.actions.size)
        assertEquals(app.getString(R.string.dictate_action_pause), n.actions[0].title.toString())
        assertEquals(app.getString(R.string.dictate_action_stop), n.actions[1].title.toString())
        assertEquals(app.getString(R.string.dictate_action_send), n.actions[2].title.toString())
    }

    @Test
    fun show_paused_postsNotificationWithResumeStopSendActions() {
        // C5 / C4-IMPL-1 — Spec 1 §7.6 Recording-Paused row: the Pause
        // button becomes Resume; Stopp/Senden stay.
        coordinator.show(NotificationStatus.Paused(sessionId = "s-1"))

        val n = posted()
        assertNotNull("Paused status must post a notification", n)
        assertEquals(
            "Paused subtitle (Spec 1 §7.6 Recording-Paused)",
            app.getString(R.string.dictate_notif_recording_paused),
            shadowOf(n).contentText,
        )
        assertEquals("Paused must show exactly 3 action buttons", 3, n!!.actions.size)
        assertEquals(app.getString(R.string.dictate_action_resume), n.actions[0].title.toString())
        assertEquals(app.getString(R.string.dictate_action_stop), n.actions[1].title.toString())
        assertEquals(app.getString(R.string.dictate_action_send), n.actions[2].title.toString())
    }

    @Test
    fun show_pipeline_postsProgressNotificationWithCancelAction() {
        coordinator.show(NotificationStatus.Pipeline(sessionId = "s-1", step = "running"))

        val n = posted()
        assertNotNull("Pipeline status must post a notification", n)
        assertEquals(
            app.getString(R.string.dictate_notif_processing),
            shadowOf(n).contentText,
        )
        // Spec 1 §7.6: Pipeline-Running → [Abbrechen] only.
        assertEquals("Pipeline must show exactly 1 action button", 1, n!!.actions.size)
        assertEquals(app.getString(R.string.dictate_action_cancel), n.actions[0].title.toString())
    }

    @Test
    fun show_overlayPermissionRequired_postsNotificationWithoutActions() {
        coordinator.show(NotificationStatus.OverlayPermissionRequired)

        val n = posted()
        assertNotNull(n)
        assertEquals(
            app.getString(R.string.dictate_notif_overlay_permission_required),
            shadowOf(n).contentText,
        )
        assertTrue(
            "OverlayPermissionRequired carries no action buttons",
            n!!.actions.isNullOrEmpty(),
        )
    }

    @Test
    fun buildInitial_isNeutralReadyNotificationWithNoActions() {
        // Spec 1 §7.4 buildInitial — the startForeground notification
        // before any recording/pipeline. Pure, in-memory (FGS-5s safe).
        val n = coordinator.buildInitial()

        assertEquals(
            app.getString(R.string.dictate_pipeline_notif_idle),
            shadowOf(n).contentText,
        )
        assertTrue("Initial notification has no action buttons", n.actions.isNullOrEmpty())
    }

    // ──────────────────────────────────────────────────────────────────
    // dismiss / Idle (AC-2 — "dismissed on Idle")
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun dismiss_removesThePostedNotification() {
        coordinator.show(NotificationStatus.Recording(sessionId = "s-1"))
        assertNotNull("Pre-condition: notification posted", posted())

        coordinator.dismiss()

        assertNull("dismiss() must cancel NOTIF_ID", posted())
    }

    @Test
    fun show_idle_dismissesInsteadOfPosting() {
        coordinator.show(NotificationStatus.Pipeline(sessionId = "s-1", step = "running"))
        assertNotNull(posted())

        coordinator.show(NotificationStatus.Idle)

        assertNull("show(Idle) must behave as dismiss()", posted())
    }

    // ──────────────────────────────────────────────────────────────────
    // Spec 1 §7.5 — action-button PendingIntent → Action back-channel
    // (AC-2 — "the action-button PendingIntent dispatches the correct
    //  Action via PipelineActionRouter")
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun recordingActionButtons_pendingIntentsDispatchTheCorrectActions() {
        coordinator.show(NotificationStatus.Recording(sessionId = "s-1"))
        val n = posted()!!

        // Fire each button's PendingIntent → the shadow re-enters the
        // service component; we decode the captured Intent through the
        // router exactly as DictatePipelineService.onStartCommand does.
        router.dispatch(intentOf(n.actions[0])) // Pause
        router.dispatch(intentOf(n.actions[1])) // Stop
        router.dispatch(intentOf(n.actions[2])) // Send

        assertEquals(
            listOf(
                Action.RecordingAction.PauseRecording,
                Action.RecordingAction.StopRecording,
                Action.RecordingAction.StopRecordingAndSend,
            ),
            dispatched.toList(),
        )
    }

    @Test
    fun pipelineCancelButton_pendingIntentDispatchesCancelPipeline() {
        coordinator.show(NotificationStatus.Pipeline(sessionId = "s-1", step = "running"))
        val n = posted()!!

        router.dispatch(intentOf(n.actions[0]))

        assertEquals(1, dispatched.size)
        assertTrue(
            "Cancel button must dispatch PipelineAction.CancelPipeline",
            dispatched[0] is Action.PipelineAction.CancelPipeline,
        )
    }

    @Test
    fun router_decodesResumeStopSendInsertDismiss() {
        router.dispatch(actionIntent(PipelineActionRouter.ACTION_RESUME))
        router.dispatch(actionIntent(PipelineActionRouter.ACTION_STOP))
        router.dispatch(actionIntent(PipelineActionRouter.ACTION_SEND))
        router.dispatch(
            actionIntent(PipelineActionRouter.ACTION_INSERT)
                .putExtra(PipelineActionRouter.EXTRA_SESSION_ID, "sess-9"),
        )
        router.dispatch(
            actionIntent(PipelineActionRouter.ACTION_DISMISS)
                .putExtra(PipelineActionRouter.EXTRA_SESSION_ID, "sess-9"),
        )

        assertEquals(
            listOf(
                Action.RecordingAction.ResumeRecording,
                Action.RecordingAction.StopRecording,
                Action.RecordingAction.StopRecordingAndSend,
                Action.PipelineAction.ConfirmInsertion("sess-9"),
                Action.PipelineAction.DismissResult("sess-9"),
            ),
            dispatched.toList(),
        )
    }

    @Test
    fun router_ignoresNullAndUnknownIntents() {
        router.dispatch(null)
        router.dispatch(Intent()) // no action
        router.dispatch(actionIntent("net.devemperor.dictate.NOT_A_REAL_ACTION"))
        // INSERT without a session id must NOT dispatch (let { } guard).
        router.dispatch(actionIntent(PipelineActionRouter.ACTION_INSERT))

        assertTrue("Unknown / malformed intents must be ignored", dispatched.isEmpty())
    }

    @Test
    fun router_pendingIntentForResultButtons_isDistinctPerSession() {
        // The result-stage buttons must not collide on action.hashCode()
        // alone — otherwise [Einfügen] for session A silently re-targets
        // session B (FLAG_UPDATE_CURRENT collision). Distinct request
        // codes ⇒ distinct PendingIntents.
        val a = router.pendingIntentFor(app, PipelineActionRouter.ACTION_INSERT, "sess-A")
        val b = router.pendingIntentFor(app, PipelineActionRouter.ACTION_INSERT, "sess-B")
        val aIntent = shadowOf(a).savedIntent
        val bIntent = shadowOf(b).savedIntent
        assertEquals("sess-A", aIntent.getStringExtra(PipelineActionRouter.EXTRA_SESSION_ID))
        assertEquals("sess-B", bIntent.getStringExtra(PipelineActionRouter.EXTRA_SESSION_ID))
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    /** A bare action-Intent (the shape Service.onStartCommand receives). */
    private fun actionIntent(action: String): Intent =
        Intent(app, DictatePipelineService::class.java).setAction(action)

    /**
     * Extract the [Intent] a notification action's [android.app.PendingIntent]
     * would deliver — exactly what `DictatePipelineService.onStartCommand`
     * receives when the user taps the button.
     */
    private fun intentOf(action: android.app.Notification.Action): Intent =
        shadowOf(action.actionIntent).savedIntent
}
