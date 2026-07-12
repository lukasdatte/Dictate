package net.devemperor.dictate.core

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.R
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.RecordingState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * C5 (Epic §4 Block B3, AC-2 + AC-3 + AC-10) — end-to-end on the **new**
 * recording-drive path: dispatching the same actions the C5-flipped IME
 * dispatches (`StartRecording` → `StopRecordingAndSend`) drives the
 * orchestrator's RecordingModule → FGS notification → the C3 pipeline
 * runner (JobExecutor-backed) with an IME-faithful `JobRequest`.
 *
 * **K-1 / K-4** — the `JobExecutor` spy is a handwritten recording
 * [PipelineRunner] (no mocking framework); Robolectric is the justified
 * opt-out (Service + NotificationManager wiring is not observable
 * otherwise). tearDown copies the
 * `DictatePipelineServiceOverlayTransitionTest` DB/JobExecutor reset
 * (Epic R-7 / `b5-ime-activation-wiring.md` §8).
 *
 * @see net.devemperor.dictate.core.ImePipelineConfigResolver
 * @see net.devemperor.dictate.state.RecordingModule
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeRecordingDriveCutoverTest {

    private class RecordingRunner : PipelineRunner {
        val transcriptionConfig = AtomicReference<PipelineOrchestrator.PipelineConfig?>()
        val reuseSessionId = AtomicReference<String?>()
        val done = CountDownLatch(1)

        override fun runTranscription(
            config: PipelineOrchestrator.PipelineConfig,
            reuseSessionId: String?,
            token: CancellationToken,
        ) {
            transcriptionConfig.set(config)
            this.reuseSessionId.set(reuseSessionId)
            done.countDown()
        }

        override fun resume(sessionId: String, token: CancellationToken) = Unit
        override fun regenerate(request: JobRequest.StepRegenerate, token: CancellationToken) = Unit
        override fun postProcess(request: JobRequest.PostProcess) = Unit
        override fun continueConversation(request: JobRequest.ConversationContinuation, token: CancellationToken) = Unit

        override fun rerunTranscription(request: JobRequest.TranscriptionRerun) = Unit

        fun awaitStarted() = check(done.await(2, TimeUnit.SECONDS)) { "runner did not start" }
    }

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
        // ActiveJobRegistry single-job lock between tests.
        ActiveJobRegistry.resetForTest()
        // C8-IMPL-1 / B3-VAL F-1 — belt-and-suspenders: this test boots
        // the full Service (→ DictateApplication →
        // DurationHealingScheduler.schedule()). Drain the in-flight heal
        // thread BEFORE the DB is dropped so it cannot pollute a
        // co-locating sibling. Ordering mandatory: scheduler reset
        // precedes DictateDatabase.resetForTest.
        net.devemperor.dictate.database.DurationHealingScheduler.resetForTest()
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
     * Pump the main looper repeatedly until [cond] holds or a 2s
     * deadline elapses. The new-path send is a multi-hop async chain
     * (`StopRecordingAndSend → EmitPipelineTrigger → emitAction →
     * TriggerPipeline → SubmitPipeline → JobExecutor executor thread`);
     * a single `idleMainLooper()` does not drain it. Mirrors the
     * spin-wait discipline of `JobExecutorTest.waitForRegistryEmpty`.
     */
    private fun pumpUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (!cond() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
    }

    // ──────────────────────────────────────────────────────────────────
    // AC-2 — StartRecording drives recording FSM Idle→Preparing→Active +
    //        the §7.6 Recording-Active FGS notification
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun startRecording_drivesFsmToActive_andPostsRecordingNotification() {
        val b = boot()
        val audio = File.createTempFile("c5rec", ".m4a", app.cacheDir)

        b.dispatch(
            Action.RecordingAction.StartRecording(
                target = InsertionTarget.INPUT_CONNECTION,
                audioFile = audio,
                sessionId = "c5-sid-1",
            ),
        )
        ShadowLooper.idleMainLooper()

        // RecordingHardwareAdapter.allocate() emits MediaRecorderReady
        // synchronously on the Robolectric MediaRecorder shadow →
        // Preparing → Active.
        val rec = b.state.value.recording
        assertTrue(
            "recording FSM must reach Active on the new path (AC-2), was $rec",
            rec is RecordingState.Active,
        )
        val n = posted()
        assertNotNull("StartRecording must post the §7.6 Recording notification", n)
        assertEquals(
            app.getString(R.string.dictate_notif_recording_active),
            shadowOf(n).contentText,
        )
        assertEquals("Recording → [Pause][Stopp][Senden]", 3, n!!.actions.size)
        assertEquals(app.getString(R.string.dictate_action_pause), n.actions[0].title.toString())
    }

    @Test
    fun pauseThenResume_swapsTheNotificationActionSet() {
        val b = boot()
        val audio = File.createTempFile("c5rec", ".m4a", app.cacheDir)
        b.dispatch(Action.RecordingAction.StartRecording(InsertionTarget.INPUT_CONNECTION, audio, "c5-sid-p"))
        ShadowLooper.idleMainLooper()

        b.dispatch(Action.RecordingAction.PauseRecording)
        ShadowLooper.idleMainLooper()
        val paused = posted()
        assertEquals(
            "Recording-Paused subtitle (§7.6)",
            app.getString(R.string.dictate_notif_recording_paused),
            shadowOf(paused).contentText,
        )
        assertEquals(app.getString(R.string.dictate_action_resume), paused!!.actions[0].title.toString())

        b.dispatch(Action.RecordingAction.ResumeRecording)
        ShadowLooper.idleMainLooper()
        val resumed = posted()
        assertEquals(
            app.getString(R.string.dictate_notif_recording_active),
            shadowOf(resumed).contentText,
        )
        assertEquals(app.getString(R.string.dictate_action_pause), resumed!!.actions[0].title.toString())
    }

    @Test
    fun cancelRecording_dismissesTheNotification() {
        val b = boot()
        val audio = File.createTempFile("c5rec", ".m4a", app.cacheDir)
        b.dispatch(Action.RecordingAction.StartRecording(InsertionTarget.INPUT_CONNECTION, audio, "c5-sid-c"))
        ShadowLooper.idleMainLooper()
        assertNotNull("pre-condition: recording notification posted", posted())

        b.dispatch(Action.RecordingAction.CancelRecording)
        ShadowLooper.idleMainLooper()
        assertNull("CancelRecording must dismiss the FGS notification", posted())
    }

    // ──────────────────────────────────────────────────────────────────
    // AC-3 + R-1 — StopRecordingAndSend → pipeline via the C3 runner
    //              with an IME-faithful JobRequest (resolver-threaded)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun stopRecordingAndSend_reachesTheImeFaithfulResolverWithTheFsmSessionId() {
        // C5 diff boundary: the new path drives
        // StopRecordingAndSend → EmitPipelineTrigger → TriggerPipeline →
        // SubmitPipeline → pipelineRunner.submit →
        // DelegatingPipelineConfigResolver.resolveFresh. This asserts the
        // R-1 closure: the orchestrator reaches the IME-registered
        // resolver with the FSM sessionId, and the resolver rebuilds the
        // JobRequest field-for-field from the IME snapshot. (The
        // resolver → JobExecutor leg is C3's tested territory —
        // PipelineRunnerSubsystemAdapterTest — and the full
        // 5-hop-to-runner E2E is the C6-D2pre gate's job; asserting it
        // here through 5 async hops + a JobExecutor worker thread is
        // brittle, so the test boundary is the resolver call, which is
        // exactly the C5 R-1 surface.)
        // No-op runner so the post-resolver JobExecutor.start does not
        // run a real pipeline (network/DB) — this test's boundary is the
        // resolver call, not the runner.
        JobExecutor.initializeForTest(object : PipelineRunner {
            override fun runTranscription(
                config: PipelineOrchestrator.PipelineConfig,
                reuseSessionId: String?,
                token: CancellationToken,
            ) = Unit
            override fun resume(sessionId: String, token: CancellationToken) = Unit
            override fun regenerate(request: JobRequest.StepRegenerate, token: CancellationToken) = Unit
            override fun postProcess(request: JobRequest.PostProcess) = Unit
        override fun continueConversation(request: JobRequest.ConversationContinuation, token: CancellationToken) = Unit

        override fun rerunTranscription(request: JobRequest.TranscriptionRerun) = Unit
        })
        val b = boot()
        val audio = File.createTempFile("c5rec", ".m4a", app.cacheDir)

        // Instrumented resolver: capture what resolveFresh receives +
        // returns, then delegate to the real ImePipelineConfigResolver.
        val real = ImePipelineConfigResolver(
            recordingsDirProvider = { app.filesDir },
            reprocessFallback = DefaultPipelineConfigResolver { app.filesDir },
        )
        val capturedSessionId = AtomicReference<String?>()
        val capturedRequest = AtomicReference<JobRequest.TranscriptionPipeline?>()
        val instrumented = object : PipelineConfigResolver {
            override fun resolveFresh(sessionId: String, audioFile: File): JobRequest.TranscriptionPipeline {
                capturedSessionId.set(sessionId)
                return real.resolveFresh(sessionId, audioFile).also { capturedRequest.set(it) }
            }
            override fun resolveReprocess(
                sessionId: String,
                audioFile: File?,
                queuedPromptSlots: List<PromptQueueSlot>?,
                language: String?,
            ) = real.resolveReprocess(sessionId, audioFile, queuedPromptSlots, language)
        }

        b.dispatch(
            Action.RecordingAction.StartRecording(InsertionTarget.INPUT_CONNECTION, audio, "c5-sid-send"),
        )
        pumpUntil { b.state.value.recording is RecordingState.Active }

        // The IME would call snapshotFresh(...) at the send-tap (R-1).
        real.snapshotFresh(
            "c5-sid-send",
            ImePipelineConfigResolver.FreshConfig(
                totalSteps = 2,
                audioFilePath = audio.absolutePath,
                language = "de",
                queuedPromptIds = listOf(5),
                targetAppPackage = "com.app",
                stylePrompt = "tone",
                livePrompt = false,
                autoSwitchKeyboard = true,
                showResendButton = true,
            ),
        )
        b.registerPipelineConfigResolver(instrumented)

        b.dispatch(Action.RecordingAction.StopRecordingAndSend)
        pumpUntil { capturedRequest.get() != null }

        // R-1: the orchestrator reached the resolver with the FSM
        // sessionId minted at StartRecording (no empty-string sentinel,
        // F-10), and the JobRequest is field-for-field the IME's.
        assertEquals("c5-sid-send", capturedSessionId.get())
        val req = capturedRequest.get()!!
        assertEquals("de", req.language)
        assertEquals(PromptQueueSlot.fromIds(listOf(5)), req.queuedPromptSlots)
        assertEquals("tone", req.stylePrompt)
        assertEquals(true, req.autoSwitchKeyboard)
        assertEquals(true, req.showResendButton)
        assertEquals(2, req.totalSteps)
        assertEquals(JobRequest.TranscriptionKind.RECORDING, req.kind)
        // Fresh recording → reuseSessionId null (preAllocated id is the
        // sessionId itself, asserted via toPipelineConfig in C3 tests).
        assertNull("fresh recording reuseSessionId is null", req.reuseSessionId)
        // No registry-drain assertion here: this test's boundary is the
        // resolver call (the C5 R-1 surface). The post-resolver
        // JobExecutor leg is C3's tested territory; the tearDown's
        // JobExecutor.resetForTest() cleans the no-op job up.
        JobExecutor.cancel("c5-sid-send")
    }

    // ──────────────────────────────────────────────────────────────────
    // C7-IMPL-1 (mid-chunk-triage B2-C7-MID-W1) — the imported-audio-file
    // path is orchestrator-routed: a TriggerPipeline (no recording FSM)
    // reaches the IME-faithful resolver, exactly like a fresh recording's
    // post-record submit. Proves the legacy :2554 JobExecutor.start is no
    // longer the route for imported files (AC-10; only RESUME survives).
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun importedAudioFile_triggerPipeline_reachesImeFaithfulResolver_withNoRecordingFsm() {
        // No-op runner so the post-resolver JobExecutor.start does not run
        // a real pipeline — the test boundary is the resolver call (the
        // C7-IMPL-1 R-1 surface), mirroring
        // stopRecordingAndSend_reachesTheImeFaithfulResolver above.
        JobExecutor.initializeForTest(object : PipelineRunner {
            override fun runTranscription(
                config: PipelineOrchestrator.PipelineConfig,
                reuseSessionId: String?,
                token: CancellationToken,
            ) = Unit
            override fun resume(sessionId: String, token: CancellationToken) = Unit
            override fun regenerate(request: JobRequest.StepRegenerate, token: CancellationToken) = Unit
            override fun postProcess(request: JobRequest.PostProcess) = Unit
        override fun continueConversation(request: JobRequest.ConversationContinuation, token: CancellationToken) = Unit

        override fun rerunTranscription(request: JobRequest.TranscriptionRerun) = Unit
        })
        val b = boot()
        val imported = File.createTempFile("imported", ".m4a", app.cacheDir)

        val real = ImePipelineConfigResolver(
            recordingsDirProvider = { app.filesDir },
            reprocessFallback = DefaultPipelineConfigResolver { app.filesDir },
        )
        val capturedSessionId = AtomicReference<String?>()
        val capturedRequest = AtomicReference<JobRequest.TranscriptionPipeline?>()
        val instrumented = object : PipelineConfigResolver {
            override fun resolveFresh(sessionId: String, audioFile: File): JobRequest.TranscriptionPipeline {
                capturedSessionId.set(sessionId)
                return real.resolveFresh(sessionId, audioFile).also { capturedRequest.set(it) }
            }
            override fun resolveReprocess(
                sessionId: String,
                audioFile: File?,
                queuedPromptSlots: List<PromptQueueSlot>?,
                language: String?,
            ) = real.resolveReprocess(sessionId, audioFile, queuedPromptSlots, language)
        }
        b.registerPipelineConfigResolver(instrumented)

        // The IME's transcribeImportedAudioFileViaOrchestrator() snapshots
        // the IME-runtime config (via the shared captureFreshConfigSnapshot
        // helper — same fields the legacy :2507-2523 construction used)
        // then dispatches TriggerPipeline. There is NO StartRecording /
        // StopRecordingAndSend — the file already exists.
        val sessionId = "imported-sid-1"
        real.snapshotFresh(
            sessionId,
            ImePipelineConfigResolver.FreshConfig(
                totalSteps = 2,
                audioFilePath = imported.absolutePath,
                language = "fr",
                queuedPromptIds = listOf(7),
                targetAppPackage = "com.imported.app",
                stylePrompt = "verbatim",
                livePrompt = false,
                autoSwitchKeyboard = false,
                showResendButton = true,
            ),
        )

        // Pipeline is Idle (no recording happened) → TriggerPipeline is the
        // documented entry-point the imported-file path dispatches.
        b.dispatch(
            net.devemperor.dictate.state.Action.PipelineAction.TriggerPipeline(sessionId, imported),
        )
        pumpUntil { capturedRequest.get() != null }

        // The orchestrator reached the IME-faithful resolver with the IME
        // sessionId — NOT a legacy JobExecutor.start — and the JobRequest
        // is field-for-field the imported-file config (R-1, AC-9 parity).
        assertEquals(sessionId, capturedSessionId.get())
        val req = capturedRequest.get()!!
        assertEquals(JobRequest.TranscriptionKind.RECORDING, req.kind)
        assertEquals(imported.absolutePath, req.audioFilePath)
        assertEquals("fr", req.language)
        assertEquals(PromptQueueSlot.fromIds(listOf(7)), req.queuedPromptSlots)
        assertEquals("verbatim", req.stylePrompt)
        assertEquals("com.imported.app", req.targetAppPackage)
        assertEquals(2, req.totalSteps)
        assertEquals(true, req.showResendButton)
        assertNull("imported file is a fresh session → reuseSessionId null", req.reuseSessionId)
        JobExecutor.cancel(sessionId)
    }

    @Test
    fun importedAudioFile_triggerPipeline_isNoOp_whenPipelineAlreadyRunning() {
        // The FSM Idle-guard is the single-submit protection (structurally
        // equivalent to the legacy JobExecutor.start busy-false): a
        // TriggerPipeline arriving while a pipeline is already Preparing
        // does NOT reach the runner a second time.
        val runner = RecordingRunner()
        JobExecutor.initializeForTest(runner)
        val b = boot()
        val first = File.createTempFile("imp1", ".m4a", app.cacheDir)
        val second = File.createTempFile("imp2", ".m4a", app.cacheDir)
        val resolver = ImePipelineConfigResolver(
            recordingsDirProvider = { app.filesDir },
            reprocessFallback = DefaultPipelineConfigResolver { app.filesDir },
        )
        resolver.snapshotFresh(
            "imp-sid-a",
            ImePipelineConfigResolver.FreshConfig(
                1, first.absolutePath, null, emptyList(), null, null, false, false, false,
            ),
        )
        resolver.snapshotFresh(
            "imp-sid-b",
            ImePipelineConfigResolver.FreshConfig(
                1, second.absolutePath, null, emptyList(), null, null, false, false, false,
            ),
        )
        b.registerPipelineConfigResolver(resolver)

        b.dispatch(net.devemperor.dictate.state.Action.PipelineAction.TriggerPipeline("imp-sid-a", first))
        pumpUntil { runner.transcriptionConfig.get() != null }
        val firstConfig = runner.transcriptionConfig.get()

        // Second trigger while non-Idle → reducer no-op (no second submit).
        b.dispatch(net.devemperor.dictate.state.Action.PipelineAction.TriggerPipeline("imp-sid-b", second))
        ShadowLooper.idleMainLooper()
        assertSame(
            "a second TriggerPipeline while Preparing/Running must be a no-op",
            firstConfig,
            runner.transcriptionConfig.get(),
        )
        JobExecutor.cancel("imp-sid-a")
    }

    @Test
    fun newPath_recordingNotification_isNotDismissedBeforePipelineTakesOver() {
        // Seamless Recording → Pipeline hand-off on the same NOTIF_ID:
        // StopRecordingAndSend does NOT dismiss; the pipeline trigger
        // re-shows a Pipeline status (no flicker / no FGS-less window).
        val runner = RecordingRunner()
        JobExecutor.initializeForTest(runner)
        val b = boot()
        val audio = File.createTempFile("c5rec", ".m4a", app.cacheDir)
        b.dispatch(Action.RecordingAction.StartRecording(InsertionTarget.INPUT_CONNECTION, audio, "c5-h"))
        pumpUntil { b.state.value.recording is RecordingState.Active }
        val resolver = ImePipelineConfigResolver(
            recordingsDirProvider = { app.filesDir },
            reprocessFallback = DefaultPipelineConfigResolver { app.filesDir },
        )
        resolver.snapshotFresh(
            "c5-h",
            ImePipelineConfigResolver.FreshConfig(
                1, audio.absolutePath, null, emptyList(), null, null, false, false, false,
            ),
        )
        b.registerPipelineConfigResolver(resolver)

        b.dispatch(Action.RecordingAction.StopRecordingAndSend)
        pumpUntil { runner.transcriptionConfig.get() != null }

        // A notification is still present (Pipeline status), never a gap.
        val n = posted()
        assertNotNull("FGS notification must persist across Recording→Pipeline", n)
        assertEquals(
            app.getString(R.string.dictate_notif_processing),
            shadowOf(n).contentText,
        )
        // Boundary = the seamless notification hand-off; the JobExecutor
        // leg is C3-tested. tearDown's resetForTest cleans up.
        JobExecutor.cancel("c5-h")
    }
}
