package net.devemperor.dictate.core

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.R
import net.devemperor.dictate.state.Action
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Robolectric integration tests for the C4-B2 cutover: the
 * **production** [DictatePipelineService] composition root now wires
 * the real [PipelineNotificationCoordinator] +
 * [PipelineActionRouter] (Spec 1 §7.4/§7.5, Epic AC-2 + AC-3),
 * replacing the no-op `PipelineServiceStubSubsystems.notificationCoordinator`.
 *
 * **Quality-Gate K-4 exception (justified opt-out).** This IS
 * Service/notification wiring: the assertions are that the booted
 * Service's `ModuleServices.notificationCoordinator` is the real
 * class, that an orchestrator-dispatched pipeline trigger reaches a
 * real posted notification, and that `startForeground` uses the SoT
 * NOTIF_ID — none of which is observable without a shadow Service +
 * NotificationManager.
 *
 * **Test-pollution discipline (mandated by
 * `research/b5-ime-activation-wiring.md` §8 / Epic R-7).** This test
 * boots the full Service many times; the [tearDown] copies the
 * `DictatePipelineServiceOverlayTransitionTest` DB/JobExecutor reset
 * so a co-locating sibling test starts from a clean singleton.
 *
 * @see net.devemperor.dictate.core.PipelineNotificationCoordinator
 * @see net.devemperor.dictate.core.DictatePipelineService
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictatePipelineServiceRecordingDriveTest {

    private val controller = Robolectric.buildService(DictatePipelineService::class.java)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @After
    fun tearDown() {
        try {
            controller.destroy()
        } catch (ignored: Throwable) {
        }
        nm.cancelAll()
        JobExecutor.resetForTest()
        // B2-VAL-W1 F-6 / Epic R-7 — drain the process-wide
        // ActiveJobRegistry single-job lock (a still-registered job from
        // a prior test whose async unregister had not completed would
        // make this boot-test's job silently never start).
        ActiveJobRegistry.resetForTest()
        // C8-IMPL-1 / B3-VAL F-1 — belt-and-suspenders: this class boots
        // the full Service (→ DictateApplication →
        // DurationHealingScheduler.schedule()) repeatedly. Drain the
        // in-flight heal thread BEFORE the DB is dropped so it cannot
        // pollute a co-locating sibling (notably
        // LegacyAudioFileMigrationTest). Ordering mandatory: scheduler
        // reset precedes DictateDatabase.resetForTest.
        net.devemperor.dictate.database.DurationHealingScheduler.resetForTest()
        // Epic R-7 / b5-ime-activation-wiring §8: drop the shared
        // DictateDatabase singleton + file so the next boot-test starts
        // clean (this class boots the full Service repeatedly).
        net.devemperor.dictate.database.DictateDatabase.resetForTest(
            ApplicationProvider.getApplicationContext(),
        )
    }

    private fun boot(): DictatePipelineService.LocalBinder {
        controller.create()
        val b = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        ShadowLooper.idleMainLooper()
        return b
    }

    private fun posted() = shadowOf(nm).getNotification(PipelineNotificationCoordinator.NOTIF_ID)

    /**
     * Arrange a deterministically ACTIVE pipeline FSM (`Preparing`).
     *
     * 2026-07-12 (pending-mechanics): raw `TriggerPipeline` dispatches in
     * this IME-less wiring used to throw the R-1 fresh-resolve tripwire
     * inside `Effect.SubmitPipeline` — and before `PipelineModule` gained
     * its `reduceFailure` rollback, the FSM silently stayed `Preparing`,
     * which these tests implicitly relied on. Arrange it legitimately:
     * a stub config resolver (fresh resolve succeeds) + a pre-occupied
     * [ActiveJobRegistry] (the adapter's `startWhenFree` defers the real
     * `JobExecutor.start`, so no background job thread races the test).
     * tearDown's `ActiveJobRegistry.resetForTest()` clears the occupier.
     */
    private fun DictatePipelineService.LocalBinder.arrangeActivePipeline(
        sessionId: String,
        audio: java.io.File,
    ) {
        registerPipelineConfigResolver(object : PipelineConfigResolver {
            override fun resolveFresh(sessionId: String, audioFile: java.io.File) =
                JobRequest.TranscriptionPipeline(
                    sessionId = sessionId,
                    totalSteps = 1,
                    kind = JobRequest.TranscriptionKind.RECORDING,
                    audioFilePath = audioFile.absolutePath,
                    recordingsDir = java.io.File("/tmp"),
                )

            override fun resolveReprocess(
                sessionId: String,
                audioFile: java.io.File?,
                queuedPromptSlots: List<PromptQueueSlot>?,
                language: String?,
            ): JobRequest.TranscriptionPipeline =
                throw UnsupportedOperationException("not used by these tests")
        })
        ActiveJobRegistry.register(
            "registry-occupier",
            JobState.Running(
                sessionId = "registry-occupier",
                currentStepIndex = 0,
                totalSteps = 1,
                currentStepName = "",
                startedAt = 0L,
            ),
        )
        dispatch(Action.PipelineAction.TriggerPipeline(sessionId = sessionId, audioFile = audio))
    }

    // ──────────────────────────────────────────────────────────────────
    // AC-2 — production wiring reaches the REAL coordinator (not stub)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun moduleServices_notificationCoordinator_isTheRealCoordinator() {
        val b = boot()
        assertTrue(
            "ModuleServices.notificationCoordinator must be the real " +
                "PipelineNotificationCoordinator, not the demoted stub",
            b.moduleServices.notificationCoordinator is PipelineNotificationCoordinator,
        )
    }

    @Test
    fun channelIsCreatedBeforeStartForeground_andUsesSoTNotifId() {
        // Spec 1 §10 Phase-B S-5: channel-before-startForeground.
        controller.create()
        assertNotNull(
            "ensureNotificationChannel (onCreate Step 1) must run before any startForeground",
            nm.getNotificationChannel(DictatePipelineService.CHANNEL_ID),
        )

        controller.startCommand(0, 0)
        val shadow = shadowOf(controller.get())
        assertNotNull(
            "onStartCommand must call startForeground synchronously (FGS-5s budget)",
            shadow.lastForegroundNotification,
        )
        assertEquals(
            "startForeground must use the single SoT NOTIF_ID",
            PipelineNotificationCoordinator.NOTIF_ID,
            shadow.lastForegroundNotificationId,
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // AC-2 — orchestrator pipeline trigger → real notification posted,
    //        dismissed when the pipeline goes terminal
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun triggerPipeline_postsTheProcessingNotification_throughTheRealPath() {
        val b = boot()

        // A real audio file so the runner path is reachable; the
        // notification effect fires from PipelineModule.reduce
        // regardless of runner outcome (Effect.UpdateNotification is
        // emitted on the Idle → Preparing edge).
        val audio = java.io.File.createTempFile("c4-drive", ".m4a", app.cacheDir)
        b.arrangeActivePipeline("drive-1", audio)
        ShadowLooper.idleMainLooper()

        val n = posted()
        assertNotNull("TriggerPipeline must post the FGS notification", n)
        assertEquals(
            app.getString(R.string.dictate_notif_processing),
            shadowOf(n).contentText,
        )
        assertEquals(
            "Pipeline notification shows the single [Abbrechen] action",
            1,
            n!!.actions.size,
        )
        assertEquals(
            app.getString(R.string.dictate_action_cancel),
            n.actions[0].title.toString(),
        )
    }

    @Test
    fun pipelinePersistenceError_dismissesTheNotification() {
        val b = boot()
        val audio = java.io.File.createTempFile("c4-drive", ".m4a", app.cacheDir)
        b.arrangeActivePipeline("drive-2", audio)
        ShadowLooper.idleMainLooper()
        assertNotNull("Pre-condition: notification posted", posted())

        // PersistenceError on the in-flight session → PipelineModule
        // emits Effect.DismissNotification → coordinator.dismiss().
        b.dispatch(Action.PipelineAction.PersistenceError("drive-2", "io-failure"))
        ShadowLooper.idleMainLooper()

        assertNull(
            "DismissNotification effect must cancel the notification",
            posted(),
        )
    }
}
