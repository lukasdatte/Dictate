package net.devemperor.dictate.ai.adapter

import android.content.SharedPreferences
import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.ElevenLabsKeytermsParser
import net.devemperor.dictate.ai.model.ParameterRegistry
import net.devemperor.dictate.ai.port.AiConfig
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get

/**
 * SharedPreferences-backed [AiConfig] adapter. Holds the provider/model/key/
 * baseUrl selection that used to live in `RunnerFactory`, plus the completion-
 * parameter and ElevenLabs-keyterms resolution that used to live in
 * `AIOrchestrator` — all moved here verbatim so the `:shared-ai` core stays
 * prefs-free with byte-identical behaviour (see AiConfigParityTest,
 * ParameterResolutionParityTest).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §4.1, §6 A3.3/A3.6
 */
class AndroidAiConfig(private val sp: SharedPreferences) : AiConfig {

    override fun provider(function: AIFunction): AIProvider {
        val key = when (function) {
            AIFunction.TRANSCRIPTION -> sp.get(Pref.TranscriptionProvider)
            AIFunction.COMPLETION -> sp.get(Pref.RewordingProvider)
        }
        return AIProvider.fromPersistKey(key)
    }

    override fun modelName(function: AIFunction): String {
        val provider = provider(function)
        return when (function) {
            AIFunction.TRANSCRIPTION -> getTranscriptionModel(provider)
            AIFunction.COMPLETION -> getCompletionModel(provider)
        }
    }

    override fun apiKey(function: AIFunction): String {
        val provider = provider(function)
        val pref = when (function) {
            AIFunction.TRANSCRIPTION -> when (provider) {
                AIProvider.OPENAI -> Pref.TranscriptionApiKeyOpenAI
                AIProvider.GROQ -> Pref.TranscriptionApiKeyGroq
                AIProvider.ELEVENLABS -> Pref.TranscriptionApiKeyElevenLabs
                AIProvider.OPENROUTER -> Pref.TranscriptionApiKeyOpenRouter
                AIProvider.CUSTOM -> Pref.TranscriptionApiKeyCustom
                else -> throw IllegalStateException("${provider.displayName} does not support transcription")
            }
            AIFunction.COMPLETION -> when (provider) {
                AIProvider.OPENAI -> Pref.RewordingApiKeyOpenAI
                AIProvider.GROQ -> Pref.RewordingApiKeyGroq
                AIProvider.ANTHROPIC -> Pref.RewordingApiKeyAnthropic
                AIProvider.OPENROUTER -> Pref.RewordingApiKeyOpenRouter
                AIProvider.CUSTOM -> Pref.RewordingApiKeyCustom
                AIProvider.ELEVENLABS -> throw IllegalStateException("ElevenLabs does not support completion")
            }
        }
        return sp.get(pref).replace(Regex("[^ -~]"), "") // Strip non-ASCII
    }

    override fun baseUrl(function: AIFunction): String {
        val provider = provider(function)
        if (provider == AIProvider.CUSTOM) {
            return when (function) {
                AIFunction.TRANSCRIPTION -> sp.get(Pref.TranscriptionCustomHost)
                AIFunction.COMPLETION -> sp.get(Pref.RewordingCustomHost)
            }
        }
        return provider.defaultBaseUrl
    }

    override fun completionParameters(provider: AIProvider, model: String): Map<String, Any> {
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

    override fun elevenLabsKeyterms(): List<String>? {
        // Reproduces the former AIOrchestrator.transcribe guard:
        // `if (provider == ELEVENLABS) parse(...).takeIf { isNotEmpty } else null`.
        if (provider(AIFunction.TRANSCRIPTION) != AIProvider.ELEVENLABS) return null
        return ElevenLabsKeytermsParser.fromJson(sp.get(Pref.ElevenLabsKeytermsParsed))
            .takeIf { it.isNotEmpty() }
    }

    private fun getTranscriptionModel(provider: AIProvider): String = when (provider) {
        AIProvider.OPENAI -> sp.get(Pref.TranscriptionOpenAIModel)
        AIProvider.GROQ -> sp.get(Pref.TranscriptionGroqModel)
        AIProvider.ELEVENLABS -> sp.get(Pref.TranscriptionElevenLabsModel)
        AIProvider.CUSTOM -> sp.get(Pref.TranscriptionCustomModel)
        else -> throw IllegalStateException("${provider.displayName} does not support transcription")
    }

    private fun getCompletionModel(provider: AIProvider): String = when (provider) {
        AIProvider.OPENAI -> sp.get(Pref.RewordingOpenAIModel)
        AIProvider.GROQ -> sp.get(Pref.RewordingGroqModel)
        AIProvider.ANTHROPIC -> sp.get(Pref.RewordingAnthropicModel)
        AIProvider.OPENROUTER -> sp.get(Pref.RewordingOpenRouterModel)
        AIProvider.CUSTOM -> sp.get(Pref.RewordingCustomModel)
        AIProvider.ELEVENLABS -> throw IllegalStateException("ElevenLabs does not support completion")
    }

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
