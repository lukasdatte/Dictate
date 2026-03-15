package net.devemperor.dictate.ai

import android.content.SharedPreferences
import net.devemperor.dictate.ai.factory.RunnerFactory
import net.devemperor.dictate.ai.model.ParameterRegistry
import net.devemperor.dictate.ai.runner.CompletionOptions
import net.devemperor.dictate.ai.runner.CompletionResult
import net.devemperor.dictate.ai.runner.TranscriptionOptions
import net.devemperor.dictate.ai.runner.TranscriptionResult
import net.devemperor.dictate.database.dao.UsageDao
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import java.io.File

/**
 * Central orchestration for all AI operations.
 *
 * Responsibilities:
 * - Obtain runners from factory
 * - Execute transcription/completion
 * - Track usage after successful calls
 *
 * Thread safety: Methods are blocking and must be called from background threads
 * (same as existing speechApiThread / rewordingApiThread).
 */
class AIOrchestrator @JvmOverloads constructor(
    private val sp: SharedPreferences,
    private val usageDao: UsageDao,
    private val factory: RunnerFactory = RunnerFactory(sp)
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

        val keyterms = if (provider == AIProvider.ELEVENLABS) {
            ElevenLabsKeytermsParser.fromJson(sp.get(Pref.ElevenLabsKeytermsParsed))
                .takeIf { it.isNotEmpty() }
        } else null

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
            usageDao.addUsage(
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

        // Build parameters from ParameterRegistry + SharedPreferences
        val resolvedParams = resolveParameters(provider, model)

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
            usageDao.addUsage(
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
     * Builds parameter map from ParameterRegistry + SharedPreferences.
     * Sentinel values (-1 / "") are filtered out (= use server default).
     *
     * Uses type-safe Pref<T> objects instead of dynamic key construction.
     * Provider -> Pref mapping via PARAMETER_PREFS.
     */
    private fun resolveParameters(provider: AIProvider, model: String): Map<String, Any> {
        val defs = ParameterRegistry.getCompletionParameters(provider, model)
        val prefs = PARAMETER_PREFS[provider] ?: return emptyMap()
        val params = mutableMapOf<String, Any>()
        for (def in defs) {
            val value: Any? = when (def.name) {
                "temperature" -> prefs.temperature?.let { sp.get(it).takeIf { v -> v >= 0f } }
                "max_completion_tokens", "max_tokens" -> prefs.maxTokens?.let { sp.get(it).takeIf { v -> v > 0 } }
                "reasoning_effort" -> prefs.reasoningEffort?.let { sp.get(it).takeIf { v -> v.isNotEmpty() } }
                else -> null  // top_p, frequency_penalty etc. - extendable later
            }
            if (value != null) params[def.name] = value
        }
        return params
    }

    fun getProvider(function: AIFunction): AIProvider = factory.getProvider(function)
    fun getModelName(function: AIFunction): String = factory.getModelName(function)

    private companion object {
        /**
         * Type-safe mapping: Provider -> Parameter Pref objects.
         * No dynamic key construction - every access goes through Pref<T>.
         */
        data class ProviderParamPrefs(
            val temperature: Pref<Float>? = null,
            val maxTokens: Pref<Int>? = null,
            val reasoningEffort: Pref<String>? = null
        )

        val PARAMETER_PREFS = mapOf(
            AIProvider.OPENAI to ProviderParamPrefs(Pref.TemperatureOpenAI, Pref.MaxTokensOpenAI, Pref.ReasoningEffortOpenAI),
            AIProvider.GROQ to ProviderParamPrefs(Pref.TemperatureGroq, Pref.MaxTokensGroq),
            AIProvider.ANTHROPIC to ProviderParamPrefs(Pref.TemperatureAnthropic, Pref.MaxTokensAnthropic),
            AIProvider.OPENROUTER to ProviderParamPrefs(Pref.TemperatureOpenRouter, Pref.MaxTokensOpenRouter),
            AIProvider.CUSTOM to ProviderParamPrefs(Pref.TemperatureOpenAI, Pref.MaxTokensOpenAI)  // Custom uses OpenAI defaults
        )
    }
}
