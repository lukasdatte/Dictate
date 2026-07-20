package net.devemperor.dictate.ai.adapter

import android.content.SharedPreferences
import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.ElevenLabsKeytermsParser
import net.devemperor.dictate.ai.port.AiConfig
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get

/**
 * Test-only, frozen pref-based [AiConfig] baseline. Originally the production adapter, it read the
 * *secret* API-key prefs directly (`apiKey()`); production now resolves credentials via the
 * entity-based `ProfileResolver`/`SecretStore`, so this class was moved to `src/test` to honour
 * spec secretstore.md §2.6 (no code outside `DictatePrefs`/`SecretsMigration` reads the secret
 * prefs). It survives here as the **characterization baseline** the new resolver is proven against
 * (`AiConfigParityTest`, `ParameterResolutionParityTest`, `ProfileResolverCharacterizationTest`)
 * and as a pref-driven `AiConfig` fixture for the orchestrator/pipeline tests.
 *
 * Do **not** re-introduce this into `src/main` — that would re-enshrine a runtime `AiConfig` that
 * reads plaintext key prefs. The non-secret completion-parameter reader was extracted to
 * `net.devemperor.dictate.ai.adapter.PrefCompletionParameters` (still in main); [completionParameters]
 * below delegates to it so this fixture stays a real test of the extracted helper.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/androidaiconfig-secret-pref-retirement.md
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

    override fun completionParameters(provider: AIProvider, model: String): Map<String, Any> =
        // Delegate to the extracted main-source reader so this fixture exercises the real helper.
        PrefCompletionParameters.of(sp, provider, model)

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
}
