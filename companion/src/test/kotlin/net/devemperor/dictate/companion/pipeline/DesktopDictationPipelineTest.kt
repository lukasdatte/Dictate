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
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.DesktopSessionRepository
import net.devemperor.dictate.companion.domain.session.HostOrigin
import net.devemperor.dictate.companion.domain.session.MessageRole
import net.devemperor.dictate.companion.domain.session.SessionStatus
import net.devemperor.dictate.companion.domain.session.StepType
import net.devemperor.dictate.companion.fakes.FakeTextInserter
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.preferences.AmbiguityMode
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
            profiles = { profile },
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

/** Production [RunnerFactory] with its two runner entry points swapped for fakes (the K-1 open seam). */
private class FakeRunnerFactory(
    config: AiConfig,
    private val transcription: TranscriptionRunner,
    private val completion: CompletionRunner,
) : RunnerFactory(config, CompanionProxyConfig, WavAudioDurationReader) {
    override fun createTranscriptionRunner(): TranscriptionRunner = transcription
    override fun createCompletionRunner(): CompletionRunner = completion
}
