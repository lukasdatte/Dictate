package net.devemperor.dictate.core

import android.app.Application
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIOrchestrator
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.factory.RunnerFactory
import net.devemperor.dictate.ai.prompt.PromptService
import net.devemperor.dictate.ai.runner.CompletionOptions
import net.devemperor.dictate.ai.runner.CompletionResult
import net.devemperor.dictate.ai.runner.CompletionRunner
import net.devemperor.dictate.ai.runner.TranscriptionOptions
import net.devemperor.dictate.ai.runner.TranscriptionResult
import net.devemperor.dictate.ai.runner.TranscriptionRunner
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.database.entity.SessionOrigin
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.database.entity.SessionType
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.After
import net.devemperor.dictate.ai.AIProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Queue-slot execution tests for the reprocess transport model (research
 * doc "reprocess-queue-editor" §2 + Gap-2 fallback): a history reprocess
 * with an *edited* slot queue must execute the slots **in order as a
 * sequential chain with each step persisted** — mirroring the fresh
 * pipeline's queued-prompt semantics.
 *
 * Load-bearing regression: a **free-text slot** (no entity id) executes via
 * the exact `PromptService.buildQueuedPrompt` construction — pre-slot, the
 * `queuedPromptIds` transport could not represent it at all (F-110: the V1
 * fallback rejected free-text after entry).
 *
 * Harness: REAL `PipelineOrchestrator` + `PromptService` + Room, capturing
 * fake AI runners via the `open RunnerFactory` seam (K-1, same pattern as
 * [PipelineOrchestratorRegenerationTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PipelineOrchestratorQueueExecutionTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val sp: SharedPreferences = FakeSharedPreferences()

    private lateinit var db: DictateDatabase
    private lateinit var factory: CapturingRunnerFactory
    private lateinit var promptService: PromptService
    private lateinit var sessionManager: SessionManager
    private lateinit var promptQueueManager: PromptQueueManager
    private lateinit var orchestrator: PipelineOrchestrator

    @Before
    fun setUp() {
        db = DictateDatabase.getInstance(app)
        factory = CapturingRunnerFactory(sp)
        val aiOrchestrator = AIOrchestrator(sp, db.usageDao(), factory)
        promptService = PromptService.create(sp)
        sessionManager = SessionManager(db)
        promptQueueManager = PromptQueueManager(
            { emptyList() },
            sp,
            object : PromptQueueManager.PromptQueueCallback {
                override fun onQueueChanged(queuedIds: List<Int>) = Unit
            }
        )
        orchestrator = PipelineOrchestrator(
            aiOrchestrator,
            AutoFormattingService(sp, aiOrchestrator),
            promptQueueManager,
            promptService,
            sessionManager,
            SessionTracker(db.sessionDao()),
            db.promptDao(),
            NoopCallback(),
            /* recordingRepository */ null,
            db.transcriptionDao(),
            db.processingStepDao(),
            db,
            /* audioFileRepository */ null
        )
    }

    @After
    fun tearDown() {
        DictateDatabase.resetForTest(app)
    }

    @Test
    fun `edited slot queue executes sequentially - saved, free-text and deleted-saved slots all run in order`() {
        val sid = createRecordingSession()
        db.promptDao().insert(
            PromptEntity(
                id = 7, pos = 0, name = "Formalize", prompt = "Make it formal",
                requiresSelection = true, autoApply = false
            )
        )

        reprocess(
            sid,
            listOf(
                // shape 1: ID-only saved prompt (live) — resolved from the DB
                PromptQueueSlot.ofSavedPrompt(7),
                // shape 3: free-text (the F-110 dead-end case)
                PromptQueueSlot.ofFreeText("Translate to English"),
                // shape 2: editor content whose saved prompt was deleted since
                PromptQueueSlot.ofContent("Summarize in one line", 999),
                // shape 1 with a deleted prompt — legacy skip semantics
                PromptQueueSlot.ofSavedPrompt(888)
            )
        )

        // 3 completions (the dead ID-only slot is skipped), chained in order.
        assertEquals(3, factory.completionCalls.size)
        val transcript = factory.transcriptText
        val out = { i: Int -> "generated-output-$i" }

        assertPromptCall(0, promptService.buildQueuedPrompt("Make it formal", transcript))
        assertPromptCall(1, promptService.buildQueuedPrompt("Translate to English", out(1)))
        assertPromptCall(2, promptService.buildQueuedPrompt("Summarize in one line", out(2)))

        // Gap-2: each step persisted, sequential chain (input = previous output).
        val chain = db.processingStepDao().getCurrentChain(sid)
        assertEquals(3, chain.size)
        assertEquals(listOf(0, 1, 2), chain.map { it.chainIndex })
        assertEquals(listOf(transcript, out(1), out(2)), chain.map { it.inputText })
        assertEquals(listOf("Make it formal", "Translate to English", "Summarize in one line"),
            chain.map { it.promptUsed })
        // Entity linkage: live id kept, free-text null, deleted id kept as
        // historical metadata (plain column, no FK).
        assertEquals(listOf(7, null, 999), chain.map { it.promptEntityId })

        val session = db.sessionDao().getById(sid)!!
        assertEquals(SessionStatus.COMPLETED.name, session.status)
        // The free-text/deleted-slot outputs survive as the session result.
        assertEquals(out(3), sessionManager.getFinalOutput(sid))
    }

    @Test
    fun `saved prompt without requiresSelection still runs standalone (no pipeline text attached)`() {
        val sid = createRecordingSession()
        db.promptDao().insert(
            PromptEntity(
                id = 5, pos = 0, name = "Poem", prompt = "Write a short poem",
                requiresSelection = false, autoApply = false
            )
        )

        reprocess(sid, listOf(PromptQueueSlot.ofSavedPrompt(5)))

        // textForPrompt = null for non-selection prompts — legacy semantics.
        assertPromptCall(0, promptService.buildQueuedPrompt("Write a short poem", null))
        assertEquals(5, db.processingStepDao().getCurrentChain(sid).single().promptEntityId)
    }

    @Test
    fun `editor-confirmed slot text wins over the entity's current text`() {
        val sid = createRecordingSession()
        db.promptDao().insert(
            PromptEntity(
                id = 7, pos = 0, name = "Formalize", prompt = "CURRENT db text",
                requiresSelection = true, autoApply = false
            )
        )

        reprocess(sid, listOf(PromptQueueSlot.ofContent("Edited in the queue editor", 7)))

        assertPromptCall(
            0,
            promptService.buildQueuedPrompt("Edited in the queue editor", factory.transcriptText)
        )
        val step = db.processingStepDao().getCurrentChain(sid).single()
        assertEquals("Edited in the queue editor", step.promptUsed)
        assertEquals(7, step.promptEntityId)
    }

    // ── Empty-vs-unset queue semantics (verification defect 1) ────────────

    @Test
    fun `explicitly empty edited queue runs zero prompts even when the live keyboard queue is non-empty`() {
        val sid = createRecordingSession()
        // The IME's live auto-apply queue lingers with a real saved prompt
        // (F-003 documents exactly this state) — it must NOT leak into a
        // history reprocess whose editor queue was explicitly emptied.
        db.promptDao().insert(
            PromptEntity(
                id = 11, pos = 0, name = "Lingering", prompt = "Lingering keyboard prompt",
                requiresSelection = true, autoApply = true
            )
        )
        promptQueueManager.togglePrompt(11)

        reprocess(sid, emptyList())

        assertEquals(
            "explicitly empty queue must run transcription only",
            0, factory.completionCalls.size
        )
        assertEquals(SessionStatus.COMPLETED.name, db.sessionDao().getById(sid)!!.status)
        assertEquals(factory.transcriptText, sessionManager.getFinalOutput(sid))
    }

    @Test
    fun `unset queue - keyboard path - still falls back to the live auto-apply queue`() {
        val sid = createRecordingSession()
        db.promptDao().insert(
            PromptEntity(
                id = 11, pos = 0, name = "Live", prompt = "Live keyboard prompt",
                requiresSelection = true, autoApply = true
            )
        )
        promptQueueManager.togglePrompt(11)

        reprocess(sid, /* slots (null = unset) */ null)

        assertEquals(1, factory.completionCalls.size)
        assertPromptCall(0, promptService.buildQueuedPrompt("Live keyboard prompt", factory.transcriptText))
    }

    // ── Resume loop: provider-cancel mid-chain (N4 parity) ───────────────

    @Test
    fun `resume - provider-level cancel mid-chain finalises CANCELLED and stops the loop`() {
        db.promptDao().insert(
            PromptEntity(
                id = 7, pos = 0, name = "First", prompt = "First instruction",
                requiresSelection = true, autoApply = false
            )
        )
        db.promptDao().insert(
            PromptEntity(
                id = 5, pos = 1, name = "Second", prompt = "Second instruction",
                requiresSelection = true, autoApply = false
            )
        )
        val sid = createRecordingSession(queuedPromptIds = "7,5")
        sessionManager.addTranscriptionVersion(
            sid, factory.transcriptText, "test-transcribe", "OPENAI", durationMs = 10
        )
        factory.cancelAtCompletionCall = 1

        // The N4 arm rethrows as CancellationException so the caller's
        // cancel-handling (finalizeCancelled + JobExecutor's cancel path)
        // engages — identical to the fresh-run loop.
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            orchestrator.resumePipelineBlocking(sid)
        }

        assertEquals(
            "the loop must stop at the cancelled step, not march on to prompt 5",
            1, factory.completionCalls.size
        )
        assertEquals(SessionStatus.CANCELLED.name, db.sessionDao().getById(sid)!!.status)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun assertPromptCall(index: Int, expected: PromptService.PromptPair) {
        val call = factory.completionCalls[index]
        assertEquals(expected.userPrompt, call.prompt)
        assertEquals(expected.systemPrompt, call.systemPrompt)
    }

    private fun createRecordingSession(queuedPromptIds: String? = null): String {
        val id = "reproc-" + System.nanoTime()
        sessionManager.createSession(
            id = id,
            type = SessionType.RECORDING,
            targetApp = "com.example.app",
            language = "de",
            // The fake transcription runner never opens the file — only the
            // path must resolve (legacy audioFilePath bridge, repo == null).
            audioFilePath = File(app.cacheDir, "$id.m4a").absolutePath,
            audioDurationSeconds = 3L,
            parentId = null,
            origin = SessionOrigin.KEYBOARD,
            queuedPromptIds = queuedPromptIds,
            initialStatus = SessionStatus.COMPLETED
        )
        return id
    }

    /**
     * History-reprocess run: reuse the session, slot queue from the editor.
     * `slots = null` = unset (no explicit queue → live-queue fallback);
     * an empty list = explicitly none.
     */
    private fun reprocess(sid: String, slots: List<PromptQueueSlot>?) {
        orchestrator.runTranscriptionPipelineBlocking(
            PipelineOrchestrator.PipelineConfig(
                audioFile = null,
                language = "de",
                stylePrompt = null,
                recordingsDir = app.cacheDir,
                targetAppPackage = null,
                origin = SessionOrigin.HISTORY_REPROCESS,
                queuedPromptSlots = slots
            ),
            reuseSessionId = sid
        )
    }

    /**
     * Capturing AI layer (K-1): fake transcription + completion runners;
     * completions numbered `generated-output-N` so chaining is assertable.
     */
    private class CapturingRunnerFactory(sp: SharedPreferences) : RunnerFactory(sp) {
        val transcriptText = "raw transcript"
        val completionCalls = mutableListOf<CompletionOptions>()

        /**
         * When set, the Nth completion call (1-based) throws a provider-level
         * CANCELLED — emulates `JobExecutor.cancel()`'s interrupt landing in
         * the HTTP layer mid-step (the N4 scenario).
         */
        var cancelAtCompletionCall: Int? = null

        override fun createTranscriptionRunner(): TranscriptionRunner =
            object : TranscriptionRunner {
                override fun transcribe(options: TranscriptionOptions): TranscriptionResult =
                    TranscriptionResult(
                        text = transcriptText,
                        audioDurationSeconds = 3,
                        modelName = "test-transcribe"
                    )
            }

        override fun createCompletionRunner(): CompletionRunner =
            object : CompletionRunner {
                override fun complete(options: CompletionOptions): CompletionResult {
                    completionCalls += options
                    if (completionCalls.size == cancelAtCompletionCall) {
                        throw AIProviderException(
                            AIProviderException.ErrorType.CANCELLED,
                            "cancelled mid-step"
                        )
                    }
                    return CompletionResult(
                        text = "generated-output-${completionCalls.size}",
                        promptTokens = 1,
                        completionTokens = 1,
                        modelName = "test-model"
                    )
                }
            }

        override fun getProvider(function: AIFunction): AIProvider = AIProvider.OPENAI
        override fun getModelName(function: AIFunction): String = "test-model"
    }

    private class NoopCallback : PipelineOrchestrator.PipelineCallback {
        override fun onStepStarted(stepName: String) = Unit
        override fun onStepCompleted(stepName: String, durationMs: Long) = Unit
        override fun onStepFailed(stepName: String) = Unit
        override fun onPipelineCompleted(text: String, source: InsertionSource) = Unit
        override fun onPipelineError(errorInfoKey: String, vibrate: Boolean, providerName: String?) = Unit
        override fun onPipelineFinished() = Unit
        override fun onShowResend() = Unit
        override fun onAutoSwitch() = Unit
        override fun onAudioPersisted(audioFile: File, sessionId: String) = Unit
    }
}
