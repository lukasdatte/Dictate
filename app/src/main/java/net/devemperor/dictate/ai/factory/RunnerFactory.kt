package net.devemperor.dictate.ai.factory

import android.content.SharedPreferences
import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.runner.AnthropicCompletionRunner
import net.devemperor.dictate.ai.runner.CompletionRunner
import net.devemperor.dictate.ai.runner.ElevenLabsTranscriptionRunner
import net.devemperor.dictate.ai.runner.OpenAICompatibleRunner
import net.devemperor.dictate.ai.runner.TranscriptionRunner
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get

class RunnerFactory(private val sp: SharedPreferences) {

    fun createTranscriptionRunner(): TranscriptionRunner {
        val provider = getProvider(AIFunction.TRANSCRIPTION)
        require(provider.supportsTranscription) {
            "${provider.displayName} does not support transcription"
        }
        return if (provider == AIProvider.ELEVENLABS) {
            ElevenLabsTranscriptionRunner(
                apiKey = getApiKey(provider, AIFunction.TRANSCRIPTION),
                sp = sp
            )
        } else {
            createOpenAICompatibleRunner(provider, AIFunction.TRANSCRIPTION)
        }
    }

    fun createCompletionRunner(): CompletionRunner {
        val provider = getProvider(AIFunction.COMPLETION)
        return if (provider == AIProvider.ANTHROPIC) {
            AnthropicCompletionRunner(
                apiKey = getApiKey(provider, AIFunction.COMPLETION),
                sp = sp
            )
        } else {
            createOpenAICompatibleRunner(provider, AIFunction.COMPLETION)
        }
    }

    fun getProvider(function: AIFunction): AIProvider {
        val key = when (function) {
            AIFunction.TRANSCRIPTION -> sp.get(Pref.TranscriptionProvider)
            AIFunction.COMPLETION -> sp.get(Pref.RewordingProvider)
        }
        return AIProvider.fromPersistKey(key)
    }

    fun getModelName(function: AIFunction): String {
        val provider = getProvider(function)
        return when (function) {
            AIFunction.TRANSCRIPTION -> getTranscriptionModel(provider)
            AIFunction.COMPLETION -> getCompletionModel(provider)
        }
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

    private fun getApiKey(provider: AIProvider, function: AIFunction): String {
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

    private fun getBaseUrl(provider: AIProvider, function: AIFunction): String {
        if (provider == AIProvider.CUSTOM) {
            return when (function) {
                AIFunction.TRANSCRIPTION -> sp.get(Pref.TranscriptionCustomHost)
                AIFunction.COMPLETION -> sp.get(Pref.RewordingCustomHost)
            }
        }
        return provider.defaultBaseUrl
    }

    private fun createOpenAICompatibleRunner(
        provider: AIProvider, function: AIFunction
    ): OpenAICompatibleRunner {
        return OpenAICompatibleRunner(
            provider = provider,
            apiKey = getApiKey(provider, function),
            baseUrl = getBaseUrl(provider, function),
            sp = sp
        )
    }
}
