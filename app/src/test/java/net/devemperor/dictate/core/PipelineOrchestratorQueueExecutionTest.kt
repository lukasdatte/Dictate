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
import org.junit.Assert.assertFalse
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
    private lateinit var callback: RecordingCallback

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
            RecordingCallback().also { callback = it },
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
    fun `continueConversation appends a turn, replays history, surfaces via onReviewTurnCompleted`() {
        val sid = createRecordingSession()
        db.promptDao().insert(
            PromptEntity(
                id = 7, pos = 0, name = "Formalize", prompt = "Make it formal",
                requiresSelection = true, autoApply = false
            )
        )
        reprocess(sid, listOf(PromptQueueSlot.ofSavedPrompt(7)))   // converse #1 -> output(1), turn 0

        orchestrator.continueConversationBlocking(sid, "make it shorter")  // converse #2 -> output(2), turn 1

        // A new turn was appended at chain_index 1 (not a new version at 0).
        val chain = db.processingStepDao().getCurrentChain(sid)
        assertEquals(2, chain.size)
        assertEquals(StepType.CONVERSATION_TURN.name, chain[1].stepType)
        assertEquals(1, chain[1].chainIndex)
        assertEquals(factory.output(2), chain[1].outputText)
        assertEquals(factory.output(2), sessionManager.getFinalOutput(sid))

        // The follow-up replay carried the full prior turn plus the <user-reply>.
        val replay = factory.converseCalls[1]
        assertEquals(3, replay.messages.size) // [USER turn0, ASSISTANT turn0, USER follow-up]
        assertTrue(replay.messages.last().content.contains("make it shorter"))
        assertTrue(replay.messages.last().content.contains("<user-reply>"))

        // Result surfaced via the NON-terminal callback.
        assertEquals(1, callback.reviewTurns.size)
        assertEquals(factory.output(2), callback.reviewTurns.single().output)
        assertEquals(sid, callback.reviewTurns.single().sessionId)

        // A follow-up USER row was appended (no second SYSTEM row).
        val roles = db.conversationMessageDao().getBySession(sid).map { it.role }
        assertEquals(1, roles.count { it == "SYSTEM" })
        assertEquals(2, roles.count { it == "USER" })
    }

    @Test
    fun `continueConversation - provider-level cancel throws and appends no turn`() {
        // ADR-0013 (d): a review-panel cancel routes to JobExecutor.cancel,
        // which triggers the token; the continuation must abort cleanly without
        // appending a turn or surfacing a review result.
        val sid = createRecordingSession()
        db.promptDao().insert(
            PromptEntity(id = 7, pos = 0, name = "F", prompt = "Make it formal", requiresSelection = true, autoApply = false)
        )
        reprocess(sid, listOf(PromptQueueSlot.ofSavedPrompt(7)))   // converse #1 (turn 0)
        factory.cancelAtConverseCall = 2                            // cancel the continuation

        assertThrows(java.util.concurrent.CancellationException::class.java) {
            orchestrator.continueConversationBlocking(sid, "make it shorter")
        }

        // No new turn appended; no non-terminal review surfaced.
        assertEquals(1, db.processingStepDao().getCurrentChain(sid).size)
        assertEquals(0, callback.reviewTurns.size)
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

    @Test
    fun `reprocess (ALWAYS_INSERT, forceTurn false) omits the ambiguity task from the merged turn (K9)`() {
        val sid = createRecordingSession()
        db.promptDao().insert(
            PromptEntity(id = 7, pos = 0, name = "F", prompt = "Make it formal", requiresSelection = true, autoApply = false)
        )
        // A history reprocess never forces a turn (ambiguity is a live-keyboard
        // concern); the verdict would be ignored, so the task must not be sent.
        reprocess(sid, listOf(PromptQueueSlot.ofSavedPrompt(7)))

        val merged = mergedUserMessage()
        assertTrue("real instruction still present", merged.contains("Make it formal"))
        assertFalse(
            "ambiguity task must be omitted when the verdict is ignored",
            merged.contains(net.devemperor.dictate.ai.prompt.PromptTemplates.AMBIGUITY_TASK),
        )
    }

    @Test
    fun `continueConversation failure persists the follow-up as an auditable ERROR turn (K8)`() {
        val sid = createRecordingSession()
        db.promptDao().insert(
            PromptEntity(id = 7, pos = 0, name = "F", prompt = "Make it formal", requiresSelection = true, autoApply = false)
        )
        reprocess(sid, listOf(PromptQueueSlot.ofSavedPrompt(7)))   // converse #1 (turn 0) succeeds
        factory.failAtConverseCall = 2                             // the continuation fails (non-cancel)

        // Non-cancel failure does NOT rethrow (best-effort), it surfaces via
        // onPipelineError — so no assertThrows here.
        orchestrator.continueConversationBlocking(sid, "make it shorter")

        // The dictated refinement is persisted as an ERROR turn at chain 1 with
        // its USER row, instead of vanishing silently.
        val chain = db.processingStepDao().getCurrentChain(sid)
        assertEquals(2, chain.size)
        val errorTurn = chain[1]
        assertEquals(StepType.CONVERSATION_TURN.name, errorTurn.stepType)
        assertEquals(1, errorTurn.chainIndex)
        assertEquals("ERROR", errorTurn.status)

        val userRows = db.conversationMessageDao().getUserMessages(sid)
        assertEquals(2, userRows.size) // turn-0 user + the failed follow-up user
        assertTrue(userRows.last().content.contains("make it shorter"))

        // Prior successful output is unchanged; the failed turn is not replayed.
        assertEquals(factory.output(1), sessionManager.getFinalOutput(sid))
        assertEquals(1, sessionManager.loadConversation(sid).turns.size)
    }

    @Test
    fun `pipeline stamps the snapshotted ambiguity mode onto the delivered review (K11)`() {
        val sid = createRecordingSession()
        // AUTO forces a turn even on a bare transcription, so a verdict is
        // produced; the delivered review must carry the snapshotted mode so the
        // IME decides insert-vs-review with the same mode, not a fresh pref read.
        orchestrator.runTranscriptionPipelineBlocking(
            PipelineOrchestrator.PipelineConfig(
                audioFile = null,
                language = "de",
                stylePrompt = null,
                recordingsDir = app.cacheDir,
                targetAppPackage = null,
                origin = SessionOrigin.HISTORY_REPROCESS,
                queuedPromptSlots = emptyList(),
                ambiguityMode = net.devemperor.dictate.preferences.AmbiguityMode.AUTO,
            ),
            reuseSessionId = sid,
        )

        val review = callback.completedReviews.single()
        assertEquals(
            net.devemperor.dictate.preferences.AmbiguityMode.AUTO,
            review!!.ambiguityMode,
        )
    }

    @Test
    fun `resume of an errored turn replays the persisted user message, not a rebuilt one (K3)`() {
        // A turn failed and was persisted as ERROR with its user message row.
        // On resume, executeConversationTurn regenerates the turn WITHOUT
        // touching the USER row — so it must converse with the persisted message,
        // otherwise the persisted history (USER=old) and the new output diverge.
        db.promptDao().insert(
            PromptEntity(
                id = 7, pos = 0, name = "Formalize", prompt = "Make it formal",
                requiresSelection = true, autoApply = false
            )
        )
        val sid = createRecordingSession(queuedPromptIds = "7")
        sessionManager.addTranscriptionVersion(
            sid, factory.transcriptText, "test-transcribe", "OPENAI", durationMs = 10
        )
        // Seed a failed turn whose USER row carries a distinctive sentinel that a
        // rebuild-from-inputs would never reproduce.
        val sentinel = "SENTINEL-PERSISTED-USER-MESSAGE"
        sessionManager.appendConversationTurnError(
            sessionId = sid, userMessageContent = sentinel, inputText = factory.transcriptText,
            model = "test-model", provider = "OPENAI", previousTranscriptionId = null,
            errorMessage = "boom", durationMs = 1,
            systemPromptForFirstTurn = "SYS"
        )

        orchestrator.resumePipelineBlocking(sid)   // converse #1 (resume) -> output(1)

        // The resume conversed with the PERSISTED user message, verbatim.
        assertEquals(1, factory.converseCalls.size)
        assertEquals(sentinel, factory.converseCalls.single().messages.single().content)

        // The regenerated turn kept the persisted USER row unchanged, so the
        // conversation replays [USER=sentinel, ASSISTANT=output] faithfully.
        assertEquals(sentinel, sessionManager.getTurnUserMessage(sid, 0))
        val versions = db.processingStepDao().getVersionsAtIndex(sid, 0)
        val current = versions.first { it.isCurrent }
        assertEquals(StepType.CONVERSATION_TURN.name, current.stepType)
        assertEquals(factory.output(1), current.outputText)
        assertEquals("SUCCESS", current.status)
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
        var failAtConverseCall: Int? = null

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
                    if (converseCalls.size == failAtConverseCall) {
                        throw AIProviderException(
                            AIProviderException.ErrorType.SERVER_ERROR,
                            "server error mid-turn"
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

    data class ReviewTurnCall(val sessionId: String, val output: String, val message: String?, val needsClarification: Boolean)

    private class RecordingCallback : PipelineOrchestrator.PipelineCallback {
        val reviewTurns = mutableListOf<ReviewTurnCall>()
        val completedReviews = mutableListOf<net.devemperor.dictate.ai.conversation.PostProcessingReview?>()
        override fun onStepStarted(stepName: String) = Unit
        override fun onStepCompleted(stepName: String, durationMs: Long) = Unit
        override fun onStepFailed(stepName: String) = Unit
        override fun onPipelineCompleted(text: String, source: InsertionSource, review: net.devemperor.dictate.ai.conversation.PostProcessingReview?) {
            completedReviews += review
        }
        override fun onReviewTurnCompleted(sessionId: String, output: String, message: String?, needsClarification: Boolean) {
            reviewTurns += ReviewTurnCall(sessionId, output, message, needsClarification)
        }
        override fun onPipelineError(errorInfoKey: String, vibrate: Boolean, providerName: String?) = Unit
        override fun onPipelineFinished() = Unit
        override fun onShowResend() = Unit
        override fun onAutoSwitch() = Unit
        override fun onAudioPersisted(audioFile: File, sessionId: String) = Unit
    }
}
