package net.devemperor.dictate.companion.pipeline

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import net.devemperor.dictate.ai.AIOrchestrator
import net.devemperor.dictate.ai.conversation.TurnInstruction
import net.devemperor.dictate.ai.factory.RunnerFactory
import net.devemperor.dictate.ai.port.AiConfig
import net.devemperor.dictate.ai.runner.CompletionOptions
import net.devemperor.dictate.ai.runner.CompletionResult
import net.devemperor.dictate.ai.runner.CompletionRunner
import net.devemperor.dictate.ai.runner.ConversationRequest
import net.devemperor.dictate.ai.runner.ConversationResult
import net.devemperor.dictate.ai.runner.TranscriptionOptions
import net.devemperor.dictate.ai.runner.TranscriptionResult
import net.devemperor.dictate.ai.runner.TranscriptionRunner
import net.devemperor.dictate.companion.ai.CompanionAiConfig
import net.devemperor.dictate.companion.ai.CompanionProxyConfig
import net.devemperor.dictate.companion.ai.NoopUsageSink
import net.devemperor.dictate.companion.capture.AudioCaptureService
import net.devemperor.dictate.companion.capture.AudioDeviceRef
import net.devemperor.dictate.companion.capture.CaptureFormat
import net.devemperor.dictate.companion.capture.CaptureResult
import net.devemperor.dictate.companion.capture.WavAudioDurationReader
import net.devemperor.dictate.companion.capture.WavWriter
import net.devemperor.dictate.companion.data.CompanionConfigRepository
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.DesktopSessionRepository
import net.devemperor.dictate.companion.domain.session.HostOrigin
import net.devemperor.dictate.companion.domain.session.MessageRole
import net.devemperor.dictate.companion.domain.session.SessionOrigin
import net.devemperor.dictate.companion.domain.session.SessionStatus
import net.devemperor.dictate.companion.domain.session.StepType
import net.devemperor.dictate.companion.fakes.FakeTextInserter
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.preferences.AmbiguityMode
import net.devemperor.dictate.shared.config.AmbiguityModeValue
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.ProfilePromptRef
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.database.entity.ResponseFormatKind as ApiResponseFormatKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Headless desktop-dictation E2E (desktop-host.md §5.5, acceptance §2 criterion 5): a WAV fixture and
 * fake runners drive Hotkey → record → transcribe → post-process → auto-insert, and the whole
 * Room-parity session graph (session + transcription + CONVERSATION_TURN step + SYSTEM/USER messages)
 * lands in the real SQLDelight DB. No microphone, no network, no window.
 */
class DesktopDictationPipelineTest {

    @get:Rule val temp = TemporaryFolder()

    private val database = CompanionDatabase.inMemory()
    private val sessions = DesktopSessionRepository(database)
    private val inserter = FakeTextInserter()
    private val clock = MutableClock()

    private val transcript = "hallo welt"
    private val postProcessed = "Hallo Welt."

    private fun controller(
        capture: AudioCaptureService,
        completion: CompletionRunner,
        transcription: TranscriptionRunner,
        profile: DictationProfile,
    ): DesktopDictationController = controller(capture, completion, transcription, profiles = { profile })

    private fun controller(
        capture: AudioCaptureService,
        completion: CompletionRunner,
        transcription: TranscriptionRunner,
        profiles: ActiveProfileSource,
    ): DesktopDictationController {
        val config: AiConfig = CompanionAiConfig()
        val orchestrator = AIOrchestrator(
            config = config,
            usageSink = NoopUsageSink,
            factory = FakeRunnerFactory(config, transcription, completion),
        )
        val effects = DictationEffects(
            capture = capture,
            ai = orchestrator,
            sessions = sessions,
            inserter = inserter,
            queue = InlineJobQueue(), // deterministic: the whole pipeline runs on the calling thread
            clock = clock,
            profiles = profiles,
            panel = PanelControl.None,
        )
        return DesktopDictationController(effects)
    }

    private fun wavFixture(): File {
        val file = temp.newFile("fixture.wav")
        WavWriter(file).use { it.write(ByteArray(CaptureFormat.BYTES_PER_SECOND), CaptureFormat.BYTES_PER_SECOND) }
        return file
    }

    @Test
    fun autoInsertTake_persistsSessionTranscriptionTurnAndMessages() {
        val fixture = wavFixture()
        val capture = FakeAudioCapture(fixture)
        val controller = controller(
            capture = capture,
            transcription = FakeTranscriptionRunner(transcript),
            completion = FakeCompletionRunner(output = postProcessed, message = null, needsClarification = false),
            // auto-format on → hasWork → a real conversation turn is produced; ALWAYS_INSERT → auto-insert.
            profile = DictationProfile(
                ambiguityMode = AmbiguityMode.ALWAYS_INSERT,
                language = "de",
                autoFormatEnabled = true,
                instructions = emptyList(),
                stylePrompt = null,
            ),
        )

        val sessionId = controller.startHotkey()
        assertTrue("capture started", capture.started)
        assertEquals(DictationPhase.RECORDING, controller.state.value.phase)

        controller.stopRecording() // InlineJobQueue → the whole pipeline runs synchronously here

        // ── terminal UI state ──
        assertEquals(DictationPhase.IDLE, controller.state.value.phase)
        assertTrue(!controller.state.value.panelVisible)

        // ── auto-insert reached the Win32 boundary with the post-processed text ──
        assertEquals(listOf(postProcessed), inserter.inserted)

        // ── session row ──
        val session = sessions.session(sessionId)
        assertNotNull("session persisted", session)
        session!!
        assertEquals(SessionStatus.COMPLETED, session.status)
        assertEquals(HostOrigin.DESKTOP_DICTATION, session.host_origin)
        assertEquals(postProcessed, session.final_output_text)
        assertEquals("de", session.language)
        assertNotNull("inserted_at stamped by the auto-insert", session.inserted_at)

        // ── transcription ──
        val transcriptions = sessions.transcriptions(sessionId)
        assertEquals(1, transcriptions.size)
        assertEquals(transcript, transcriptions.single().text)
        assertTrue(transcriptions.single().is_current)
        assertEquals(1L, transcriptions.single().version)

        // ── CONVERSATION_TURN step ──
        val steps = sessions.steps(sessionId)
        assertEquals(1, steps.size)
        val step = steps.single()
        assertEquals(StepType.CONVERSATION_TURN, step.step_type)
        assertEquals(0L, step.chain_index)
        assertEquals(postProcessed, step.output_text)
        assertEquals(transcript, step.input_text)

        // ── persisted SYSTEM + USER messages ──
        val messages = sessions.messages(sessionId)
        assertEquals(2, messages.size)
        assertEquals(MessageRole.SYSTEM, messages[0].role)
        assertEquals(0L, messages[0].seq)
        assertEquals(MessageRole.USER, messages[1].role)
        assertEquals(1L, messages[1].seq)
        assertTrue("USER message carries the transcript", messages[1].content.contains(transcript))
        assertEquals("both messages hang off the turn step", step.id, messages[0].step_id)
    }

    @Test
    fun profiledTake_resolvesTheAutoApplyInstructionIntoThePersistedUserMessage() {
        // Seed a real Block-C profile with one auto-apply prompt in the SAME in-memory DB the sessions
        // land in, and drive the pipeline through the REAL ConfigProfileSource — the regression guard
        // that the finding's failure (only ambiguityMode resolved, instructions stuck at DEFAULT) cannot
        // recur on the post-processing axis.
        val config = CompanionConfigRepository(database, now = { 1L })
        config.save(PromptV3Entity(id = "p-auto", name = "tidy", text = "tidy it", requiresSelection = false))
        config.save(
            ProfileEntity(
                id = "prof-1", name = "P",
                orderedPrompts = listOf(ProfilePromptRef(promptRef = "p-auto", autoApply = true)),
                ambiguityMode = AmbiguityModeValue.ALWAYS_INSERT,
            )
        )
        val source = ConfigProfileSource(
            config = config,
            activeProfileId = { "prof-1" },
            language = { "de" },
            autoFormatEnabled = { false },
        )

        val capture = FakeAudioCapture(wavFixture())
        val controller = controller(
            capture = capture,
            transcription = FakeTranscriptionRunner(transcript),
            // instructions non-empty → hasWork → a turn runs; ALWAYS_INSERT → auto-insert.
            completion = FakeCompletionRunner(output = postProcessed, message = null, needsClarification = false),
            profiles = source,
        )

        val sessionId = controller.startHotkey()
        controller.stopRecording()

        assertEquals(listOf(postProcessed), inserter.inserted)
        val messages = sessions.messages(sessionId)
        val userMessage = messages.single { it.role == MessageRole.USER }
        assertTrue("the resolved auto-apply instruction reaches the persisted USER message", userMessage.content.contains("tidy it"))
        assertEquals("language resolved from the device supplier", "de", sessions.session(sessionId)!!.language)
    }

    @Test
    fun bareTranscriptTake_insertsVerbatimWithNoTurn() {
        val capture = FakeAudioCapture(wavFixture())
        val controller = controller(
            capture = capture,
            transcription = FakeTranscriptionRunner(transcript),
            completion = FakeCompletionRunner("SHOULD-NOT-RUN", null, false),
            // no auto-format, no instructions, ALWAYS_INSERT → hasWork=false → no turn (§5.5, ADR-0012 §1)
            profile = DictationProfile(AmbiguityMode.ALWAYS_INSERT, null, autoFormatEnabled = false, emptyList(), null),
        )

        val sessionId = controller.startHotkey()
        controller.stopRecording()

        assertEquals(DictationPhase.IDLE, controller.state.value.phase)
        assertEquals("verbatim transcript inserted", listOf(transcript), inserter.inserted)
        assertEquals(SessionStatus.COMPLETED, sessions.session(sessionId)!!.status)
        assertEquals(transcript, sessions.session(sessionId)!!.final_output_text)
        assertTrue("no conversation turn ran", sessions.steps(sessionId).isEmpty())
        assertTrue("no messages persisted", sessions.messages(sessionId).isEmpty())
        assertEquals("1.92 MB/min fixture ⇒ 1 s duration", 1L, sessions.session(sessionId)!!.audio_duration_seconds)
    }

    @Test
    fun reviewVerdict_holdsThePanelAndDoesNotInsert() {
        val capture = FakeAudioCapture(wavFixture())
        val controller = controller(
            capture = capture,
            transcription = FakeTranscriptionRunner(transcript),
            completion = FakeCompletionRunner(output = "draft", message = "which Anna?", needsClarification = true),
            profile = DictationProfile(
                ambiguityMode = AmbiguityMode.ALWAYS_REVIEW,
                language = null,
                autoFormatEnabled = false,
                instructions = listOf(TurnInstruction("tidy it", appliesToTranscript = true)),
                stylePrompt = null,
            ),
        )

        val sessionId = controller.startHotkey()
        controller.stopRecording()

        assertEquals(DictationPhase.REVIEW, controller.state.value.phase)
        assertTrue("panel stays up for review", controller.state.value.panelVisible)
        assertEquals("nothing auto-inserted on a REVIEW verdict", emptyList<String>(), inserter.inserted)
        assertNull("insert not stamped", sessions.session(sessionId)!!.inserted_at)
        // The turn is still persisted (the review reads it) — one CONVERSATION_TURN + its messages.
        assertEquals(1, sessions.steps(sessionId).size)
        assertEquals(2, sessions.messages(sessionId).size)
    }

    @Test
    fun reDictate_persistsAReviewRefinementSessionAndAppendsAContinuationTurn() {
        val capture = FakeAudioCapture(wavFixture())
        // First turn → REVIEW ("draft"); the re-dictate continuation → a second REVIEW ("sharper draft"),
        // proving the non-terminal panel update (ADR-0013 §6 iterative re-dictate).
        val completion = SequencedCompletionRunner(
            listOf(
                FakeConversationResult(output = "draft", message = "which Anna?", needsClarification = true),
                FakeConversationResult(output = "sharper draft", message = "still ambiguous?", needsClarification = true),
            )
        )
        val controller = controller(
            capture = capture,
            transcription = FakeTranscriptionRunner(transcript),
            completion = completion,
            profile = DictationProfile(
                ambiguityMode = AmbiguityMode.ALWAYS_REVIEW,
                language = "de",
                autoFormatEnabled = false,
                instructions = listOf(TurnInstruction("tidy it", appliesToTranscript = true)),
                stylePrompt = null,
            ),
        )

        val reviewSessionId = controller.startHotkey()
        controller.stopRecording() // → REVIEW ("draft")
        assertEquals(DictationPhase.REVIEW, controller.state.value.phase)
        assertEquals("draft", controller.state.value.review!!.output)

        // ── re-dictate: S2 record → stop → transcription-only → ConversationContinuation ──
        val refinementSessionId = controller.startRefinement()
        assertTrue("Insert/Discard disabled while recording", controller.state.value.review!!.refinementRecording)
        controller.stopRefinement() // InlineJobQueue → whole continuation runs synchronously

        // ── panel updated NON-TERMINAL: still REVIEW, new output, refinement flags cleared ──
        assertEquals(DictationPhase.REVIEW, controller.state.value.phase)
        assertEquals("sharper draft", controller.state.value.review!!.output)
        assertTrue(!controller.state.value.review!!.refining)
        assertTrue(!controller.state.value.review!!.refinementRecording)
        assertEquals("nothing inserted — still under review", emptyList<String>(), inserter.inserted)
        assertEquals("converse called for turn-0 and the continuation", 2, completion.converseCalls)

        // ── REVIEW_REFINEMENT session for the S2 take, parented to the reviewed session ──
        val refinement = sessions.session(refinementSessionId)
        assertNotNull("refinement session persisted", refinement)
        refinement!!
        assertEquals(SessionOrigin.REVIEW_REFINEMENT, refinement.origin)
        assertEquals(reviewSessionId, refinement.parent_session_id)
        assertEquals(HostOrigin.DESKTOP_DICTATION, refinement.host_origin)
        assertEquals("S2 transcription persisted", 1, sessions.transcriptions(refinementSessionId).size)

        // ── continuation turn appended to the REVIEWED session (chain_index 1) ──
        val steps = sessions.steps(reviewSessionId).filter { it.step_type == StepType.CONVERSATION_TURN }
        assertEquals("turn-0 + continuation", 2, steps.size)
        assertEquals(listOf(0L, 1L), steps.map { it.chain_index })
        assertEquals("sharper draft", steps.last().output_text)
        assertEquals("continuation links back to the refinement session", refinementSessionId, steps.last().source_session_id)
        assertEquals("final output updated to the continuation result", "sharper draft", sessions.session(reviewSessionId)!!.final_output_text)

        // ── conversation messages: SYSTEM + USER(turn0) + USER(continuation) ──
        val messages = sessions.messages(reviewSessionId)
        assertEquals(3, messages.size)
        assertEquals(1, messages.count { it.role == MessageRole.SYSTEM })
        assertEquals(2, messages.count { it.role == MessageRole.USER })
        assertTrue("continuation USER message wraps the spoken reply as <user-reply>", messages.last().content.contains("user-reply"))
    }
}

// ── fakes ────────────────────────────────────────────────────────────────────────────────────

private class FakeAudioCapture(private val fixture: File) : AudioCaptureService {
    var started = false
    override fun start(device: AudioDeviceRef?) { started = true }
    override fun pause() {}
    override fun resume() {}
    override fun stop(): CaptureResult =
        CaptureResult(mergedWav = fixture, segmentPaths = listOf(fixture), durationSeconds = WavAudioDurationReader.durationSeconds(fixture))
    override fun discard() {}
    override val amplitudes: Flow<Float> = emptyFlow()
}

private class FakeTranscriptionRunner(private val text: String) : TranscriptionRunner {
    override fun transcribe(options: TranscriptionOptions): TranscriptionResult =
        TranscriptionResult(text = text, audioDurationSeconds = 1, modelName = options.model)
}

private class FakeCompletionRunner(
    private val output: String,
    private val message: String?,
    private val needsClarification: Boolean,
) : CompletionRunner {
    override fun complete(options: CompletionOptions): CompletionResult = error("completion() not used by the pipeline")
    override fun converse(request: ConversationRequest): ConversationResult = ConversationResult(
        message = message,
        output = output,
        promptTokens = 12,
        completionTokens = 34,
        modelName = request.model,
        responseFormat = ApiResponseFormatKind.JSON_SCHEMA,
        needsClarification = needsClarification,
    )
}

/** One scripted `converse` answer. */
private data class FakeConversationResult(val output: String, val message: String?, val needsClarification: Boolean)

/** Returns a scripted answer per `converse` call — the turn-0 verdict, then each continuation's. */
private class SequencedCompletionRunner(private val answers: List<FakeConversationResult>) : CompletionRunner {
    var converseCalls = 0
        private set

    override fun complete(options: CompletionOptions): CompletionResult = error("completion() not used by the pipeline")
    override fun converse(request: ConversationRequest): ConversationResult {
        val answer = answers[converseCalls.coerceAtMost(answers.lastIndex)]
        converseCalls++
        return ConversationResult(
            message = answer.message,
            output = answer.output,
            promptTokens = 12,
            completionTokens = 34,
            modelName = request.model,
            responseFormat = ApiResponseFormatKind.JSON_SCHEMA,
            needsClarification = answer.needsClarification,
        )
    }
}

/** Production [RunnerFactory] with its two runner entry points swapped for fakes (the K-1 open seam). */
private class FakeRunnerFactory(
    config: AiConfig,
    private val transcription: TranscriptionRunner,
    private val completion: CompletionRunner,
) : RunnerFactory(config, CompanionProxyConfig, WavAudioDurationReader) {
    override fun createTranscriptionRunner(): TranscriptionRunner = transcription
    override fun createCompletionRunner(): CompletionRunner = completion
}
