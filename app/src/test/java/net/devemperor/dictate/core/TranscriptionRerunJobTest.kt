package net.devemperor.dictate.core

import android.app.Application
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIOrchestrator
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.factory.RunnerFactory
import net.devemperor.dictate.ai.prompt.PromptService
import net.devemperor.dictate.ai.runner.TranscriptionOptions
import net.devemperor.dictate.ai.runner.TranscriptionResult
import net.devemperor.dictate.ai.runner.TranscriptionRunner
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.SessionOrigin
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.database.entity.SessionType
import net.devemperor.dictate.database.entity.StepStatus
import net.devemperor.dictate.database.entity.StepType
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * R6 — transcription re-run job tests (spec §3.4, D3/D4).
 *
 * Drives the REAL `JobExecutor` → `PipelineOrchestrator` → Room chain with a
 * capturing fake transcription runner (K-1: handwritten fake via the `open
 * RunnerFactory` seam, no mocking framework — same fixture style as
 * [PipelineOrchestratorRegenerationTest]).
 *
 * Coverage:
 *  - (a) job registers/unregisters in [ActiveJobRegistry];
 *  - (b) success → a new current transcription version, old cleared, text
 *    persisted into `final_output_text`;
 *  - (c) a second job is rejected while one is active (mutual exclusion);
 *  - (d) no audio → clean failure, session row untouched;
 *  - (e) FAILED session with NO chain → COMPLETED + final_output = new text;
 *  - (f) session WITH a chain → final_output unchanged (last step wins),
 *    status unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptionRerunJobTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val sp: SharedPreferences = FakeSharedPreferences()

    private lateinit var db: DictateDatabase
    private lateinit var factory: CapturingTranscriptionFactory
    private lateinit var sessionManager: SessionManager
    private lateinit var callback: LatchingCallback
    private lateinit var orchestrator: PipelineOrchestrator

    @Before
    fun setUp() {
        ActiveJobRegistry.state.value.keys.toList().forEach { ActiveJobRegistry.unregister(it) }
        JobExecutor.resetForTest()

        db = DictateDatabase.getInstance(app)
        factory = CapturingTranscriptionFactory(sp)
        val aiOrchestrator = AIOrchestrator(sp, db.usageDao(), factory)
        sessionManager = SessionManager(db)
        callback = LatchingCallback()
        orchestrator = PipelineOrchestrator(
            aiOrchestrator,
            AutoFormattingService(sp, aiOrchestrator),
            PromptQueueManager(
                { emptyList() },
                sp,
                object : PromptQueueManager.PromptQueueCallback {
                    override fun onQueueChanged(queuedIds: List<Int>) = Unit
                }
            ),
            PromptService.create(sp),
            sessionManager,
            SessionTracker(db.sessionDao()),
            db.promptDao(),
            callback,
            /* recordingRepository */ null,
            db.transcriptionDao(),
            db.processingStepDao(),
            db,
            /* audioFileRepository */ null
        )
        JobExecutor.initialize(orchestrator)
    }

    @After
    fun tearDown() {
        ActiveJobRegistry.state.value.keys.toList().forEach { ActiveJobRegistry.unregister(it) }
        JobExecutor.resetForTest()
        DictateDatabase.resetForTest(app)
    }

    // ── (a) + (b): lifecycle + new current version + final output ────────

    @Test
    fun `rerun registers then unregisters and produces a new current transcription version`() {
        val sid = createCompletedRecording(audioPresent = true)
        sessionManager.addTranscriptionVersion(sid, "first transcription", "whisper-1", "OPENAI", 0, 0, 100)
        sessionManager.updateFinalOutputText(sid, "first transcription")

        assertFalse(ActiveJobRegistry.isActive(sid))
        assertTrue(JobExecutor.start(app, JobRequest.TranscriptionRerun(sid)))
        // The job may already have finished; either way it must end unregistered.
        callback.awaitFinished()
        waitForRegistryEmpty()
        assertFalse(ActiveJobRegistry.isActive(sid))

        val versions = db.transcriptionDao().getAllVersions(sid)
        assertEquals(2, versions.size)
        val current = db.transcriptionDao().getCurrent(sid)!!
        assertEquals(2, current.version)
        assertEquals(factory.lastResultText, current.text)
        // The old version is no longer current.
        assertFalse(versions.first { it.version == 1 }.isCurrent)
        // Bare-audio session (no chain): final output follows the transcription.
        assertEquals(factory.lastResultText, db.sessionDao().getById(sid)!!.finalOutputText)
    }

    // ── (c): mutual exclusion ────────────────────────────────────────────

    @Test
    fun `a second job is rejected while a rerun is active`() {
        val sid = createCompletedRecording(audioPresent = true)
        sessionManager.addTranscriptionVersion(sid, "v1", "whisper-1", "OPENAI", 0, 0, 100)

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        factory.gate = started to release

        assertTrue(JobExecutor.start(app, JobRequest.TranscriptionRerun(sid)))
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertTrue(ActiveJobRegistry.isActive(sid))

        // A second job on the same session must be rejected while the first runs.
        assertFalse(JobExecutor.start(app, JobRequest.TranscriptionRerun(sid)))

        release.countDown()
        callback.awaitFinished()
        waitForRegistryEmpty()
    }

    // ── (d): no audio → clean failure, session row untouched ─────────────

    @Test
    fun `no resolvable audio fails cleanly and leaves the COMPLETED session untouched`() {
        val sid = createCompletedRecording(audioPresent = false)
        sessionManager.addTranscriptionVersion(sid, "v1", "whisper-1", "OPENAI", 0, 0, 100)
        sessionManager.updateFinalOutputText(sid, "v1")

        assertTrue(JobExecutor.start(app, JobRequest.TranscriptionRerun(sid)))
        callback.awaitFinished()
        waitForRegistryEmpty()

        // No new version, session row (status + final output) unchanged.
        assertEquals(1, db.transcriptionDao().getAllVersions(sid).size)
        val session = db.sessionDao().getById(sid)!!
        assertEquals(SessionStatus.COMPLETED.name, session.status)
        assertEquals("v1", session.finalOutputText)
        assertTrue(callback.errorKeys.contains("audio_file_missing"))
        // The runner was never called.
        assertEquals(0, factory.calls)
    }

    // ── (e): FAILED + no chain → COMPLETED ───────────────────────────────

    @Test
    fun `FAILED session with no processing chain becomes COMPLETED with the new transcription`() {
        val sid = createRecording(SessionStatus.FAILED, audioPresent = true)
        // A failed bare-audio session may have a stale transcription or none.
        sessionManager.addTranscriptionVersion(sid, "stale", "whisper-1", "OPENAI", 0, 0, 100)

        assertTrue(JobExecutor.start(app, JobRequest.TranscriptionRerun(sid)))
        callback.awaitFinished()
        waitForRegistryEmpty()

        val session = db.sessionDao().getById(sid)!!
        assertEquals(SessionStatus.COMPLETED.name, session.status)
        assertEquals(factory.lastResultText, session.finalOutputText)
    }

    // ── (f): session WITH a chain → final output unchanged ───────────────

    @Test
    fun `session with a processing chain keeps the last-step output as final and status unchanged`() {
        val sid = createCompletedRecording(audioPresent = true)
        sessionManager.addTranscriptionVersion(sid, "raw transcript", "whisper-1", "OPENAI", 0, 0, 100)
        sessionManager.appendProcessingStep(
            sessionId = sid,
            stepType = StepType.QUEUED_PROMPT,
            inputText = "raw transcript",
            outputText = "final prompt output",
            modelUsed = "gpt-4o-mini",
            provider = "OPENAI",
            durationMs = 10,
            status = StepStatus.SUCCESS,
        )
        sessionManager.updateFinalOutputText(sid, "final prompt output")

        assertTrue(JobExecutor.start(app, JobRequest.TranscriptionRerun(sid)))
        callback.awaitFinished()
        waitForRegistryEmpty()

        // A new transcription version exists, but the last current step still
        // wins the final output — the chain was NOT re-run (D3).
        assertEquals(2, db.transcriptionDao().getAllVersions(sid).size)
        val session = db.sessionDao().getById(sid)!!
        assertEquals("final prompt output", session.finalOutputText)
        assertEquals(SessionStatus.COMPLETED.name, session.status)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun createCompletedRecording(audioPresent: Boolean) =
        createRecording(SessionStatus.COMPLETED, audioPresent)

    private fun createRecording(status: SessionStatus, audioPresent: Boolean): String {
        val id = "sess-" + System.nanoTime()
        val audioPath = if (audioPresent) {
            File.createTempFile("rerun-audio-", ".m4a").apply {
                writeBytes(byteArrayOf(0, 1, 2, 3))
                deleteOnExit()
            }.absolutePath
        } else {
            // A path that does not exist on disk → resolver yields nothing.
            File(app.cacheDir, "missing-${System.nanoTime()}.m4a").absolutePath
        }
        sessionManager.createSession(
            id = id,
            type = SessionType.RECORDING,
            targetApp = "com.example.app",
            language = "de",
            audioFilePath = audioPath,
            audioDurationSeconds = 3L,
            parentId = null,
            origin = SessionOrigin.KEYBOARD,
            queuedPromptIds = null,
            initialStatus = status
        )
        if (status == SessionStatus.FAILED) {
            db.sessionDao().updateError(id, "UNKNOWN", "boom")
        }
        return id
    }

    private fun waitForRegistryEmpty() {
        val deadline = System.currentTimeMillis() + 2_000
        while (ActiveJobRegistry.isAnyActive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertFalse("registry should be empty after job", ActiveJobRegistry.isAnyActive())
    }

    /**
     * Handwritten capturing transcription layer (K-1). Every re-run drives
     * [transcribe]; the gate lets one test hold the job open to prove mutual
     * exclusion.
     */
    private class CapturingTranscriptionFactory(sp: SharedPreferences) : RunnerFactory(sp) {
        @Volatile var calls: Int = 0
            private set
        @Volatile var lastResultText: String = ""
            private set

        /** started-latch (counted down when transcribe enters) to release-latch. */
        @Volatile var gate: Pair<CountDownLatch, CountDownLatch>? = null

        override fun createTranscriptionRunner(): TranscriptionRunner =
            object : TranscriptionRunner {
                override fun transcribe(options: TranscriptionOptions): TranscriptionResult {
                    calls++
                    lastResultText = "rerun-transcription-$calls"
                    gate?.let { (started, release) ->
                        started.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                    return TranscriptionResult(
                        text = lastResultText,
                        audioDurationSeconds = 3,
                        modelName = "whisper-1"
                    )
                }
            }

        override fun getProvider(function: AIFunction): AIProvider = AIProvider.OPENAI
        override fun getModelName(function: AIFunction): String = "whisper-1"
    }

    private class LatchingCallback : PipelineOrchestrator.PipelineCallback {
        private val finished = CountDownLatch(1)
        val errorKeys = mutableListOf<String>()
        override fun onStepStarted(stepName: String) = Unit
        override fun onStepCompleted(stepName: String, durationMs: Long) = Unit
        override fun onStepFailed(stepName: String) = Unit
        override fun onPipelineCompleted(
            text: String,
            source: net.devemperor.dictate.database.entity.InsertionSource
        ) = Unit
        override fun onPipelineError(errorInfoKey: String, vibrate: Boolean, providerName: String?) {
            errorKeys += errorInfoKey
        }
        override fun onPipelineFinished() { finished.countDown() }
        override fun onShowResend() = Unit
        override fun onAutoSwitch() = Unit
        override fun onAudioPersisted(audioFile: File, sessionId: String) = Unit

        fun awaitFinished() {
            assertTrue("pipeline did not finish", finished.await(5, TimeUnit.SECONDS))
        }
    }
}
