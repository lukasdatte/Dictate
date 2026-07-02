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
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.database.entity.SessionOrigin
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.database.entity.SessionType
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
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
 * F-109 / F-111 regression tests (history-reprocess-hardening §3) — drives
 * the REAL `PipelineOrchestrator` + `PromptService` + Room persistence with a
 * capturing fake AI runner (K-1: handwritten fake via the `open RunnerFactory`
 * seam, no mocking framework).
 *
 * The load-bearing assertion: regenerating a step sends the **identical built
 * prompt** the original pipeline call sent. Pre-F-109, regenerate fed the
 * persisted *built* prompt back through the builder — the instruction was
 * applied twice ("double-wrap") and regenerated versions were produced under
 * a different prompt contract than v1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PipelineOrchestratorRegenerationTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val sp: SharedPreferences = FakeSharedPreferences()

    private lateinit var db: DictateDatabase
    private lateinit var factory: CapturingRunnerFactory
    private lateinit var promptService: PromptService
    private lateinit var sessionManager: SessionManager
    private lateinit var callback: LatchingCallback
    private lateinit var orchestrator: PipelineOrchestrator

    @Before
    fun setUp() {
        db = DictateDatabase.getInstance(app)
        factory = CapturingRunnerFactory(sp)
        val aiOrchestrator = AIOrchestrator(sp, db.usageDao(), factory)
        promptService = PromptService.create(sp)
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
            promptService,
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
    }

    @After
    fun tearDown() {
        DictateDatabase.resetForTest(app)
    }

    // ── F-111: session lifecycle lives in the job body ───────────────────

    @Test
    fun `post-process session row is created only by the job body - never before`() {
        val parentId = createParentSession()

        // The chooser being open/dismissed corresponds to NO job call — there
        // must be no POST_PROCESSING row for the pre-allocated id.
        val newId = "post-1"
        assertNull(db.sessionDao().getById(newId))

        orchestrator.runPostProcessingBlocking(
            newId, parentId, "source text", "Make it formal", 7
        )

        val session = db.sessionDao().getById(newId)
        assertNotNull(session)
        assertEquals(SessionType.POST_PROCESSING.name, session!!.type)
        assertEquals(parentId, session.parentSessionId)
        assertEquals("source text", session.inputText)
        assertEquals(SessionStatus.COMPLETED.name, session.status)
        // Parent metadata is inherited so the history entry stays attributable.
        assertEquals("com.example.app", session.targetAppPackage)
        assertEquals("de", session.language)

        val chain = db.processingStepDao().getCurrentChain(newId)
        assertEquals(1, chain.size)
        assertEquals("source text", chain[0].inputText)
        assertEquals("Make it formal", chain[0].promptUsed)
        assertEquals(7, chain[0].promptEntityId)
        assertEquals(factory.lastResultText, session.finalOutputText)
    }

    // ── F-109: regenerate == original prompt construction ────────────────

    @Test
    fun `regenerating a queued-prompt step sends the identical built prompt as the original call`() {
        val parentId = createParentSession()
        val sid = "post-2"
        orchestrator.runPostProcessingBlocking(
            sid, parentId, "source text", "Make it formal", null
        )
        val original = factory.calls.single()
        val chainIndex = db.processingStepDao().getCurrentChain(sid).single().chainIndex

        orchestrator.regenerateStepBlocking(sid, chainIndex)

        assertEquals(2, factory.calls.size)
        val regenerated = factory.calls[1]
        // Byte-identical user prompt + same system prompt — the double-wrap
        // regression (feeding the built prompt back in) makes this fail.
        assertEquals(original.prompt, regenerated.prompt)
        assertEquals(original.systemPrompt, regenerated.systemPrompt)

        // The new version keeps the raw-input persistence contract, so a
        // second regenerate stays stable too.
        val current = db.processingStepDao().getCurrentChain(sid).single()
        assertEquals(2, current.version)
        assertEquals("source text", current.inputText)
        assertEquals("Make it formal", current.promptUsed)
    }

    @Test
    fun `regenerate with an Other-prompt override rebuilds with the override and persists it`() {
        val parentId = createParentSession()
        val sid = "post-3"
        orchestrator.runPostProcessingBlocking(
            sid, parentId, "source text", "Make it formal", 7
        )
        val chainIndex = db.processingStepDao().getCurrentChain(sid).single().chainIndex

        orchestrator.regenerateStepBlocking(
            sid, chainIndex,
            promptOverride = "Translate to English",
            promptOverrideEntityId = null
        )

        val expected = promptService.buildQueuedPrompt("Translate to English", "source text")
        val regenerated = factory.calls.last()
        assertEquals(expected.userPrompt, regenerated.prompt)
        assertEquals(expected.systemPrompt, regenerated.systemPrompt)

        // Free-text override: the old step's entity id must NOT be carried over.
        val current = db.processingStepDao().getCurrentChain(sid).single()
        assertEquals("Translate to English", current.promptUsed)
        assertNull(current.promptEntityId)
    }

    @Test
    fun `rewording step persists the raw selection as input_text and regenerates identically`() {
        val prompt = PromptEntity(
            id = 5, pos = 0, name = "Formalize", prompt = "Make it formal",
            requiresSelection = true, autoApply = false
        )
        orchestrator.runStandalonePrompt(
            PipelineOrchestrator.StandaloneConfig(
                promptEntity = prompt,
                selectedText = "raw selection",
                overrideSelection = null,
                targetAppPackage = "com.example.app"
            )
        )
        assertTrue(
            "standalone prompt did not finish",
            callback.finished.await(5, TimeUnit.SECONDS)
        )
        val original = factory.calls.single()

        // Locate the REWORDING session the orchestrator created — it is the
        // only session in this test's fresh DB.
        val sid = db.sessionDao().findActiveSessionIds().single()
        val step = db.processingStepDao().getCurrentChain(sid).single()

        // F-109 persistence contract: the RAW selection, not the built prompt.
        assertEquals("raw selection", step.inputText)

        orchestrator.regenerateStepBlocking(sid, step.chainIndex)
        val regenerated = factory.calls.last()
        assertEquals(original.prompt, regenerated.prompt)
        assertEquals(original.systemPrompt, regenerated.systemPrompt)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun createParentSession(): String {
        val id = "parent-" + System.nanoTime()
        sessionManager.createSession(
            id = id,
            type = SessionType.RECORDING,
            targetApp = "com.example.app",
            language = "de",
            audioFilePath = null,
            audioDurationSeconds = 0L,
            parentId = null,
            origin = SessionOrigin.KEYBOARD,
            queuedPromptIds = null,
            initialStatus = SessionStatus.RECORDED
        )
        return id
    }

    /**
     * Handwritten capturing AI layer (K-1). Records every [CompletionOptions]
     * the orchestrator sends; provider/model fixed so no prefs are consulted.
     */
    private class CapturingRunnerFactory(sp: SharedPreferences) : RunnerFactory(sp) {
        val calls = mutableListOf<CompletionOptions>()
        var lastResultText: String? = null
            private set

        override fun createCompletionRunner(): CompletionRunner =
            object : CompletionRunner {
                override fun complete(options: CompletionOptions): CompletionResult {
                    calls += options
                    lastResultText = "generated-output-${calls.size}"
                    return CompletionResult(
                        text = lastResultText!!,
                        promptTokens = 1,
                        completionTokens = 1,
                        modelName = "test-model"
                    )
                }
            }

        override fun getProvider(function: AIFunction): AIProvider = AIProvider.OPENAI
        override fun getModelName(function: AIFunction): String = "test-model"
    }

    private class LatchingCallback : PipelineOrchestrator.PipelineCallback {
        val finished = CountDownLatch(1)
        override fun onStepStarted(stepName: String) = Unit
        override fun onStepCompleted(stepName: String, durationMs: Long) = Unit
        override fun onStepFailed(stepName: String) = Unit
        override fun onPipelineCompleted(text: String, source: InsertionSource) = Unit
        override fun onPipelineError(errorInfoKey: String, vibrate: Boolean, providerName: String?) = Unit
        override fun onPipelineFinished() {
            finished.countDown()
        }
        override fun onShowResend() = Unit
        override fun onAutoSwitch() = Unit
        override fun onAudioPersisted(audioFile: File, sessionId: String) = Unit
    }
}
