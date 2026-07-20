package net.devemperor.dictate.companion.pipeline

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIOrchestrator
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.ai.conversation.ConversationMessage
import net.devemperor.dictate.ai.conversation.ConversationReconstructor
import net.devemperor.dictate.ai.conversation.ConversationTurnBuilder
import net.devemperor.dictate.ai.conversation.PostProcessingInputs
import net.devemperor.dictate.ai.conversation.ReviewDecision
import net.devemperor.dictate.ai.conversation.Verdict
import net.devemperor.dictate.ai.prompt.PromptTemplates
import net.devemperor.dictate.companion.capture.AudioCaptureService
import net.devemperor.dictate.companion.capture.CaptureResult
import net.devemperor.dictate.companion.data.ContinuationTurnRecord
import net.devemperor.dictate.companion.data.ConversationTurnRecord
import net.devemperor.dictate.companion.data.DesktopSessionRepository
import net.devemperor.dictate.companion.data.TranscriptionRecord
import net.devemperor.dictate.companion.domain.port.ClockPort
import net.devemperor.dictate.companion.domain.port.TextInserter
import java.util.UUID
import net.devemperor.dictate.database.entity.MessageRole as ApiMessageRole
import net.devemperor.dictate.companion.domain.session.ResponseFormatKind as CompanionResponseFormatKind

/**
 * Executes the [Effect]s the pure [DictationReducer] emits — the IO side of the pipeline
 * (desktop-host.md §5.4/§5.5). Everything the reducer is forbidden to touch lives here: the clock,
 * UUIDs, the microphone, the three `:shared-ai` calls, the text insert, and every persistence write.
 *
 * The transcribe → post-process → verdict run is submitted to the serial [JobQueue] (ADR-0009,
 * §5.6); it advances the UI by dispatching [DictationIntent] callbacks (`TranscriptionCompleted`,
 * `PipelineVerdict`, `PipelineFailed`) as it goes, so the reducer stays the single state authority.
 */
class DictationEffects(
    private val capture: AudioCaptureService,
    private val ai: AIOrchestrator,
    private val sessions: DesktopSessionRepository,
    private val inserter: TextInserter,
    private val queue: JobQueue,
    private val clock: ClockPort,
    private val profiles: ActiveProfileSource,
    private val panel: PanelControl,
    /**
     * Live read of `insertion.confirmBeforeInsert` (F21, §8.5) — a supplier so a settings change
     * applies to the next take without rebuilding the graph. Default: auto-insert.
     */
    private val confirmBeforeInsert: () -> Boolean = { false },
) {

    fun run(effect: Effect, dispatch: (DictationIntent) -> Unit) {
        when (effect) {
            Effect.ShowPanel -> panel.setVisible(true)
            Effect.HidePanel -> panel.setVisible(false)
            is Effect.StartCapture -> capture.start(effect.device)
            Effect.PauseCapture -> capture.pause()
            Effect.ResumeCapture -> capture.resume()
            Effect.DiscardCapture -> capture.discard()
            is Effect.StopCaptureAndRun -> submitPipeline(effect.sessionId, dispatch)
            is Effect.InsertText -> insert(effect.sessionId, effect.text, dispatch)
            // Discard from the waiting panel: acknowledge without inserting — the same `inserted_at`
            // stamp as an insert, one acknowledge channel for both (ADR-0013 §4, §8.5).
            is Effect.AcknowledgeDiscard -> sessions.stampInserted(effect.sessionId, clock.nowMillis())
            is Effect.RunRefinementTranscription -> submitRefinementTranscription(effect, dispatch)
            is Effect.RunContinuation -> submitContinuation(effect, dispatch)
            // No queue.cancel (D1b built none): drop the S2 audio; a continuation already on the queue
            // finishes but its ReviewTurnCompleted is dropped by the reducer's `refining` guard (§8.4).
            Effect.CancelRefinement -> capture.discard()
        }
    }

    private fun submitPipeline(sessionId: String, dispatch: (DictationIntent) -> Unit) {
        // capture.stop() must run on the JobQueue too so the whole take-to-verdict run is one
        // serialized unit (a second dictation queued behind it, §5.6). A failure to even stop the mic
        // has no persisted session yet, so it only reports the failure.
        queue.submit(sessionId) {
            val take = try {
                capture.stop()
            } catch (e: Exception) {
                dispatch(DictationIntent.PipelineFailed(sessionId, AIProviderException.ErrorType.UNKNOWN.name))
                return@submit
            }
            runPipeline(sessionId, take, dispatch)
        }
    }

    private fun runPipeline(sessionId: String, take: CaptureResult, dispatch: (DictationIntent) -> Unit) {
        val profile = profiles.current()
        try {
            sessions.createDictationSession(
                id = sessionId,
                createdAt = clock.nowMillis(),
                language = profile.language,
                audioFilePath = take.mergedWav.absolutePath,
                audioFilePathsJson = encodePaths(take.segmentPaths.map { it.absolutePath }),
                durationSeconds = take.durationSeconds,
            )

            val transcript = transcribe(sessionId, take, profile)
            dispatch(DictationIntent.TranscriptionCompleted(sessionId))

            val inputs = PostProcessingInputs(
                transcript = transcript.text,
                languageHint = profile.language,
                autoFormatEnabled = profile.autoFormatEnabled,
                instructions = profile.instructions,
                includeAmbiguityTask = profile.ambiguityMode.forcesTurn,
                forceTurn = profile.ambiguityMode.forcesTurn,
            )

            if (!ConversationTurnBuilder.hasWork(inputs)) {
                // Bare transcript: no turn, insert verbatim (ADR-0012 §1). ALWAYS_INSERT is the only
                // mode that reaches here (the others force a turn), so the verdict is always INSERT.
                sessions.completeWithFinalOutput(sessionId, transcript.text)
                dispatch(
                    DictationIntent.PipelineVerdict(
                        sessionId, Verdict.INSERT, transcript.text, null,
                        requiresConfirm = confirmBeforeInsert(),
                    )
                )
                return
            }

            val verdict = postProcess(sessionId, transcript, inputs, profile)
            dispatch(verdict)
        } catch (e: AIProviderException) {
            sessions.markFailed(sessionId, e.errorType, e.message)
            dispatch(DictationIntent.PipelineFailed(sessionId, e.errorType.name))
        } catch (e: Exception) {
            sessions.markFailed(sessionId, AIProviderException.ErrorType.UNKNOWN, e.message)
            dispatch(DictationIntent.PipelineFailed(sessionId, AIProviderException.ErrorType.UNKNOWN.name))
        }
    }

    private data class Transcript(val id: String, val text: String)

    private fun transcribe(sessionId: String, take: CaptureResult, profile: DictationProfile): Transcript {
        val start = clock.nowMillis()
        val result = ai.transcribe(take.mergedWav, profile.language, profile.stylePrompt)
        val id = UUID.randomUUID().toString()
        sessions.insertTranscription(
            TranscriptionRecord(
                id = id,
                sessionId = sessionId,
                version = 1,
                isCurrent = true,
                text = result.text.trim(),
                modelUsed = result.modelName,
                provider = ai.getProvider(AIFunction.TRANSCRIPTION).name,
                promptTokens = 0,
                completionTokens = 0,
                durationMs = clock.nowMillis() - start,
                createdAt = clock.nowMillis(),
            )
        )
        return Transcript(id, result.text.trim())
    }

    private fun postProcess(
        sessionId: String,
        transcript: Transcript,
        inputs: PostProcessingInputs,
        profile: DictationProfile,
    ): DictationIntent.PipelineVerdict {
        val userMessage = ConversationTurnBuilder.buildFirstUserMessage(inputs)
        // The turn-0 system prompt is a fixed template, persisted verbatim as the SYSTEM row so the
        // dialog survives template changes across versions (ADR-0012, footgun "Systemprompt aus
        // Live-Template"). Same source :app uses (PipelineOrchestrator.executeConversationTurn).
        val systemPrompt = PromptTemplates.SYSTEM_PROMPT_CONVERSATION
        val start = clock.nowMillis()
        val result = ai.converse(listOf(ConversationMessage(ApiMessageRole.USER, userMessage)), systemPrompt)

        sessions.persistConversationTurn(
            ConversationTurnRecord(
                sessionId = sessionId,
                stepId = UUID.randomUUID().toString(),
                systemMessageId = UUID.randomUUID().toString(),
                userMessageId = UUID.randomUUID().toString(),
                chainIndex = 0,
                inputText = transcript.text,
                output = result.output,
                assistantMessage = result.message,
                responseFormat = CompanionResponseFormatKind.valueOf(result.responseFormat.name),
                modelUsed = result.modelName,
                provider = ai.getProvider(AIFunction.COMPLETION).name,
                previousTranscriptionId = transcript.id,
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                durationMs = clock.nowMillis() - start,
                systemContent = systemPrompt,
                userContent = userMessage,
                createdAt = clock.nowMillis(),
            )
        )

        // ReviewDecision is the single verdict authority (shared-ai) — never rebuilt (§8.2 footgun).
        val verdict = ReviewDecision.decide(profile.ambiguityMode, result.needsClarification, result.message)
        return DictationIntent.PipelineVerdict(
            sessionId, verdict, result.output, result.message,
            requiresConfirm = verdict == Verdict.INSERT && confirmBeforeInsert(),
        )
    }

    private fun insert(sessionId: String, text: String, dispatch: (DictationIntent) -> Unit) {
        inserter.insert(text)
        sessions.stampInserted(sessionId, clock.nowMillis())
        dispatch(DictationIntent.InsertCompleted(sessionId))
    }

    // ── re-dictate: S2 transcription + ConversationContinuation (§8.3) ─────────────────────────

    /**
     * Stops the S2 mic and transcribes it (transcription-only, no post-processing): a
     * `REVIEW_REFINEMENT` session + its transcription, then the follow-up text back to the reducer.
     */
    private fun submitRefinementTranscription(effect: Effect.RunRefinementTranscription, dispatch: (DictationIntent) -> Unit) {
        queue.submit(effect.refinementSessionId) {
            val take = try {
                capture.stop()
            } catch (e: Exception) {
                dispatch(DictationIntent.RefinementFailed(AIProviderException.ErrorType.UNKNOWN.name))
                return@submit
            }
            val profile = profiles.current()
            try {
                sessions.createRefinementSession(
                    id = effect.refinementSessionId,
                    createdAt = clock.nowMillis(),
                    language = profile.language,
                    audioFilePath = take.mergedWav.absolutePath,
                    audioFilePathsJson = encodePaths(take.segmentPaths.map { it.absolutePath }),
                    durationSeconds = take.durationSeconds,
                    parentSessionId = effect.reviewSessionId,
                )
                val transcript = transcribe(effect.refinementSessionId, take, profile)
                dispatch(DictationIntent.RefinementTranscribed(transcript.text))
            } catch (e: AIProviderException) {
                sessions.markFailed(effect.refinementSessionId, e.errorType, e.message)
                dispatch(DictationIntent.RefinementFailed(e.errorType.name))
            } catch (e: Exception) {
                sessions.markFailed(effect.refinementSessionId, AIProviderException.ErrorType.UNKNOWN, e.message)
                dispatch(DictationIntent.RefinementFailed(AIProviderException.ErrorType.UNKNOWN.name))
            }
        }
    }

    /**
     * Runs a `ConversationContinuation` (ADR-0013 §6): the reviewed session's persisted turns + system
     * prompt + the follow-up user message go back to the model, the answer is appended as a new turn,
     * and the re-run `ReviewDecision.decide` verdict returns to the reducer (non-terminal).
     */
    private fun submitContinuation(effect: Effect.RunContinuation, dispatch: (DictationIntent) -> Unit) {
        queue.submit("continuation-${effect.reviewSessionId}-${clock.nowMillis()}") {
            val profile = profiles.current()
            val stepId = UUID.randomUUID().toString()
            try {
                val snapshot = sessions.loadConversation(effect.reviewSessionId)
                val followUpMsg = ConversationTurnBuilder.buildFollowUpUserMessage(effect.followUpText)
                val messages = ConversationReconstructor.toApiMessages(snapshot.turns, followUpMsg)
                val start = clock.nowMillis()
                val result = ai.converse(messages, snapshot.systemContent)

                sessions.appendContinuationTurn(
                    ContinuationTurnRecord(
                        reviewSessionId = effect.reviewSessionId,
                        refinementSessionId = effect.refinementSessionId,
                        stepId = stepId,
                        userMessageId = UUID.randomUUID().toString(),
                        followUpText = effect.followUpText,
                        userContent = followUpMsg,
                        output = result.output,
                        assistantMessage = result.message,
                        responseFormat = CompanionResponseFormatKind.valueOf(result.responseFormat.name),
                        modelUsed = result.modelName,
                        provider = ai.getProvider(AIFunction.COMPLETION).name,
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                        durationMs = clock.nowMillis() - start,
                        createdAt = clock.nowMillis(),
                    )
                )

                val verdict = ReviewDecision.decide(profile.ambiguityMode, result.needsClarification, result.message)
                dispatch(
                    DictationIntent.ReviewTurnCompleted(
                        effect.reviewSessionId, verdict, result.output, result.message,
                        requiresConfirm = verdict == Verdict.INSERT && confirmBeforeInsert(),
                    )
                )
            } catch (e: AIProviderException) {
                sessions.appendErrorTurn(effect.reviewSessionId, stepId, effect.followUpText, e.message, clock.nowMillis())
                dispatch(DictationIntent.RefinementFailed(e.errorType.name))
            } catch (e: Exception) {
                sessions.appendErrorTurn(effect.reviewSessionId, stepId, effect.followUpText, e.message, clock.nowMillis())
                dispatch(DictationIntent.RefinementFailed(AIProviderException.ErrorType.UNKNOWN.name))
            }
        }
    }

    private fun encodePaths(paths: List<String>): String =
        Json.encodeToString(ListSerializer(String.serializer()), paths)
}
