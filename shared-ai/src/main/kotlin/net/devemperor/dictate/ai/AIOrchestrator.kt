package net.devemperor.dictate.ai

import net.devemperor.dictate.ai.conversation.ConversationMessage
import net.devemperor.dictate.ai.factory.RunnerFactory
import net.devemperor.dictate.ai.port.AiConfig
import net.devemperor.dictate.ai.port.UsageSink
import net.devemperor.dictate.ai.runner.CompletionOptions
import net.devemperor.dictate.ai.runner.CompletionResult
import net.devemperor.dictate.ai.runner.ConversationRequest
import net.devemperor.dictate.ai.runner.ConversationResult
import net.devemperor.dictate.ai.runner.TranscriptionOptions
import net.devemperor.dictate.ai.runner.TranscriptionResult
import java.io.File

/**
 * Central orchestration for all AI operations.
 *
 * Responsibilities:
 * - Obtain runners from factory
 * - Execute transcription/completion
 * - Track usage after successful calls
 *
 * All platform coupling is behind ports: [AiConfig] resolves provider/model/key,
 * completion parameters and ElevenLabs keyterms; [UsageSink] records usage. The
 * orchestrator itself is prefs-free and consumable from both `:app` and
 * `:companion`.
 *
 * Thread safety: Methods are blocking and must be called from background threads
 * (same as existing speechApiThread / rewordingApiThread).
 *
 * @see docs/decisions/0028-shared-ai-module.md
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §4.5, §6 A3.6
 */
class AIOrchestrator(
    private val config: AiConfig,
    private val usageSink: UsageSink,
    private val factory: RunnerFactory
) {

    /**
     * Transcribes an audio file.
     * Corresponds to the logic in startWhisperApiRequest(), lines 1498-1537.
     *
     * @throws AIProviderException on API errors
     */
    fun transcribe(
        audioFile: File,
        language: String?,
        stylePrompt: String?
    ): TranscriptionResult {
        val model = factory.getModelName(AIFunction.TRANSCRIPTION)
        val runner = factory.createTranscriptionRunner()
        val provider = factory.getProvider(AIFunction.TRANSCRIPTION)

        // The adapter returns keyterms only when the active transcription
        // provider is ElevenLabs (and the parsed list is non-empty), else null —
        // reproducing the former `if (provider == ELEVENLABS) parse(...) else null`.
        val keyterms = config.elevenLabsKeyterms()

        try {
            val result = runner.transcribe(
                TranscriptionOptions(
                    audioFile = audioFile,
                    model = model,
                    language = language,
                    stylePrompt = stylePrompt,
                    keyterms = keyterms
                )
            )

            // Usage tracking
            usageSink.addUsage(
                result.modelName,
                result.audioDurationSeconds,
                0, 0,
                provider.name
            )

            return result
        } catch (e: AIProviderException) {
            throw AIProviderException(e.errorType, e.message ?: "", e.cause, e.modelName, provider)
        }
    }

    /**
     * Executes a chat completion (rewording / auto-formatting).
     * Corresponds to the logic in requestRewordingFromApi(), lines 1702-1779.
     *
     * @param systemPrompt Optional - null for auto-formatting, set for rewording
     * @throws AIProviderException on API errors
     */
    fun complete(prompt: String, systemPrompt: String? = null): CompletionResult {
        val model = factory.getModelName(AIFunction.COMPLETION)
        val runner = factory.createCompletionRunner()
        val provider = factory.getProvider(AIFunction.COMPLETION)

        // Parameters resolved by the config port (ParameterRegistry + sentinel filter).
        val resolvedParams = config.completionParameters(provider, model)

        try {
            val result = runner.complete(
                CompletionOptions(
                    prompt = prompt,
                    model = model,
                    systemPrompt = systemPrompt,
                    parameters = resolvedParams
                )
            )

            // Usage tracking
            usageSink.addUsage(
                result.modelName,
                0,
                result.promptTokens,
                result.completionTokens,
                provider.name
            )

            return result
        } catch (e: AIProviderException) {
            throw AIProviderException(e.errorType, e.message ?: "", e.cause, e.modelName, provider)
        }
    }

    /**
     * Executes a structured, multi-turn conversation turn (ADR-0012). Same
     * model / provider / parameter resolution and usage tracking as [complete],
     * but returns the parsed `{message, output}` structured answer.
     *
     * @param messages ordered conversation; the last entry is the new user turn
     * @param systemPrompt system prompt for the turn (sourced from the persisted
     *   SYSTEM row so it survives prompt-template changes across app versions)
     * @throws AIProviderException on API errors
     */
    fun converse(messages: List<ConversationMessage>, systemPrompt: String?): ConversationResult {
        val model = factory.getModelName(AIFunction.COMPLETION)
        val runner = factory.createCompletionRunner()
        val provider = factory.getProvider(AIFunction.COMPLETION)

        val resolvedParams = config.completionParameters(provider, model)

        try {
            val result = runner.converse(
                ConversationRequest(
                    messages = messages,
                    model = model,
                    systemPrompt = systemPrompt,
                    parameters = resolvedParams
                )
            )

            usageSink.addUsage(
                result.modelName,
                0,
                result.promptTokens,
                result.completionTokens,
                provider.name
            )

            return result
        } catch (e: AIProviderException) {
            throw AIProviderException(e.errorType, e.message ?: "", e.cause, e.modelName, provider)
        }
    }

    fun getProvider(function: AIFunction): AIProvider = factory.getProvider(function)
    fun getModelName(function: AIFunction): String = factory.getModelName(function)
}
