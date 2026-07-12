package net.devemperor.dictate.core

import android.app.Application
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIOrchestrator
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.ai.factory.RunnerFactory
import net.devemperor.dictate.ai.prompt.PromptService
import net.devemperor.dictate.ai.runner.CompletionOptions
import net.devemperor.dictate.ai.runner.CompletionResult
import net.devemperor.dictate.ai.runner.CompletionRunner
import net.devemperor.dictate.ai.runner.ConversationRequest
import net.devemperor.dictate.ai.runner.ConversationResult
import net.devemperor.dictate.ai.runner.TranscriptionOptions
import net.devemperor.dictate.ai.runner.TranscriptionResult
import net.devemperor.dictate.ai.runner.TranscriptionRunner
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.database.entity.ResponseFormatKind
import net.devemperor.dictate.database.entity.SessionOrigin
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.database.entity.SessionType
import net.devemperor.dictate.database.entity.StepType
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Consolidated-turn execution tests (ADR-0012). A reprocess with an edited slot
 * queue now runs ONE `converse` call whose single user message lists all
 * resolved instructions in order, persisted as ONE `CONVERSATION_TURN` step —
 * replacing the pre-ADR-0012 per-prompt chain.
 *
 * Harness: REAL `PipelineOrchestrator` + `PromptService` + Room, capturing fake
 * AI runners via the `open RunnerFactory` seam (K-1).
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

    /** The single merged user message of the (only) converse call. */
    private fun mergedUserMessage(): String {
        assertEquals("expected exactly one conversation turn", 1, factory.converseCalls.size)
        val messages = factory.converseCalls.single().messages
        assertEquals(1, messages.size)
        return messages.single().content
    }

    private fun assertInOrder(haystack: String, vararg needles: String) {
        var prev = -1
        for (n in needles) {
            val idx = haystack.indexOf(n)
            assertTrue("missing instruction: $n", idx >= 0)
            assertTrue("out of order: $n", idx > prev)
            prev = idx
        }
    }

    @Test
    fun `edited slot queue merges all resolved instructions into one ordered turn`() {
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
                PromptQueueSlot.ofSavedPrompt(7),
                PromptQueueSlot.ofFreeText("Translate to English"),
                PromptQueueSlot.ofContent("Summarize in one line", 999),
                // dead ID-only slot — skipped
                PromptQueueSlot.ofSavedPrompt(888)
            )
        )

        val merged = mergedUserMessage()
        assertInOrder(merged, "Make it formal", "Translate to English", "Summarize in one line")
        assertTrue("transcript is a data tag", merged.contains(factory.transcriptText))

        // ONE merged step, not a per-prompt chain.
        val chain = db.processingStepDao().getCurrentChain(sid)
        assertEquals(1, chain.size)
        assertEquals(StepType.CONVERSATION_TURN.name, chain[0].stepType)
        assertEquals(0, chain[0].chainIndex)
        assertEquals(ResponseFormatKind.JSON_SCHEMA.name, chain[0].responseFormat)
        assertEquals("done", chain[0].assistantMessage)

        val session = db.sessionDao().getById(sid)!!
        assertEquals(SessionStatus.COMPLETED.name, session.status)
        assertEquals(factory.output(1), sessionManager.getFinalOutput(sid))
    }

    @Test
    fun `non-requiresSelection prompt still runs as a merged instruction`() {
        val sid = createRecordingSession()
        db.promptDao().insert(
            PromptEntity(
                id = 5, pos = 0, name = "Poem", prompt = "Write a short poem",
                requiresSelection = false, autoApply = false
            )
        )

        reprocess(sid, listOf(PromptQueueSlot.ofSavedPrompt(5)))

        assertTrue(mergedUserMessage().contains("Write a short poem"))
        assertEquals(StepType.CONVERSATION_TURN.name, db.processingStepDao().getCurrentChain(sid).single().stepType)
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

        val merged = mergedUserMessage()
        assertTrue(merged.contains("Edited in the queue editor"))
        assertTrue("stale db text must not leak", !merged.contains("CURRENT db text"))
    }

    @Test
    fun `explicitly empty edited queue runs zero turns even when the live keyboard queue is non-empty`() {
        val sid = createRecordingSession()
        db.promptDao().insert(
            PromptEntity(
                id = 11, pos = 0, name = "Lingering", prompt = "Lingering keyboard prompt",
                requiresSelection = true, autoApply = true
            )
        )
        promptQueueManager.togglePrompt(11)

        reprocess(sid, emptyList())

        assertEquals("explicitly empty queue: no conversation turn", 0, factory.converseCalls.size)
        assertEquals(0, db.processingStepDao().getCurrentChain(sid).size)
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

        assertTrue(mergedUserMessage().contains("Live keyboard prompt"))
    }

    @Test
    fun `regenerate of a conversation turn writes a new version and replays the turn`() {
        val sid = createRecordingSession()
        db.promptDao().insert(
            PromptEntity(
                id = 7, pos = 0, name = "Formalize", prompt = "Make it formal",
                requiresSelection = true, autoApply = false
            )
        )
        reprocess(sid, listOf(PromptQueueSlot.ofSavedPrompt(7)))   // converse #1 -> output(1)
        val userMsgBefore = db.conversationMessageDao().getBySession(sid)

        orchestrator.regenerateStepBlocking(sid, 0)                // converse #2 -> output(2)

        // new version at the same chain index
        val versions = db.processingStepDao().getVersionsAtIndex(sid, 0)
        assertEquals(2, versions.size)
        val current = versions.first { it.isCurrent }
        assertEquals(2, current.version)
        assertEquals(factory.output(2), current.outputText)
        assertEquals(factory.output(2), sessionManager.getFinalOutput(sid))

        // conversation history (user + system rows) is unchanged by a regenerate
        assertEquals(userMsgBefore.size, db.conversationMessageDao().getBySession(sid).size)

        // the regenerate replayed turn 0's user message (no prior turns)
        val replay = factory.converseCalls[1]
        assertEquals(1, replay.messages.size)
        assertTrue(replay.messages.single().content.contains("Make it formal"))
    }

    @Test
    fun `resume - provider-level cancel finalises CANCELLED`() {
        db.promptDao().insert(
            PromptEntity(
                id = 7, pos = 0, name = "First", prompt = "First instruction",
                requiresSelection = true, autoApply = false
            )
        )
        val sid = createRecordingSession(queuedPromptIds = "7")
        sessionManager.addTranscriptionVersion(
            sid, factory.transcriptText, "test-transcribe", "OPENAI", durationMs = 10
        )
        factory.cancelAtConverseCall = 1

        assertThrows(java.util.concurrent.CancellationException::class.java) {
            orchestrator.resumePipelineBlocking(sid)
        }

        assertEquals(1, factory.converseCalls.size)
        assertEquals(SessionStatus.CANCELLED.name, db.sessionDao().getById(sid)!!.status)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun createRecordingSession(queuedPromptIds: String? = null): String {
        val id = "reproc-" + System.nanoTime()
        sessionManager.createSession(
            id = id,
            type = SessionType.RECORDING,
            targetApp = "com.example.app",
            language = "de",
            audioFilePath = File(app.cacheDir, "$id.m4a").absolutePath,
            audioDurationSeconds = 3L,
            parentId = null,
            origin = SessionOrigin.KEYBOARD,
            queuedPromptIds = queuedPromptIds,
            initialStatus = SessionStatus.COMPLETED
        )
        return id
    }

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
     * Capturing AI layer (K-1): fake transcription + structured converse; outputs
     * numbered `generated-output-N`. complete() is unused on the pipeline path.
     */
    private class CapturingRunnerFactory(sp: SharedPreferences) : RunnerFactory(sp) {
        val transcriptText = "raw transcript"
        val converseCalls = mutableListOf<ConversationRequest>()
        var cancelAtConverseCall: Int? = null

        fun output(n: Int) = "generated-output-$n"

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
                override fun complete(options: CompletionOptions): CompletionResult =
                    throw UnsupportedOperationException("complete() is not used on the pipeline path")

                override fun converse(request: ConversationRequest): ConversationResult {
                    converseCalls += request
                    if (converseCalls.size == cancelAtConverseCall) {
                        throw AIProviderException(
                            AIProviderException.ErrorType.CANCELLED,
                            "cancelled mid-turn"
                        )
                    }
                    return ConversationResult(
                        message = "done",
                        output = output(converseCalls.size),
                        promptTokens = 1,
                        completionTokens = 1,
                        modelName = "test-model",
                        responseFormat = ResponseFormatKind.JSON_SCHEMA
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
