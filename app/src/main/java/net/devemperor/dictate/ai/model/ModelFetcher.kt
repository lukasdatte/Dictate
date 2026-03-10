package net.devemperor.dictate.ai.model

import android.content.SharedPreferences
import com.openai.client.okhttp.OpenAIOkHttpClient
import net.devemperor.dictate.DictateUtils
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import java.time.Duration

/**
 * Fetches available models dynamically via API.
 * No cache, no DB access - purely in-memory.
 *
 * Filter strategy:
 * - OpenAI: Blocklist for irrelevant models (Embeddings, DALL-E, TTS, Image, Moderation)
 * - Groq: Blocklist applied too (API may return irrelevant models)
 * - OpenRouter: OpenAI-compatible, uses same fetch path
 * - Anthropic: NO fetching! Anthropic has no OpenAI-compatible /models endpoint.
 *   Settings UI shows free-text field for model ID (like Custom provider).
 * - Custom: No fetching (free-text input)
 *
 * Transcription/Completion separation:
 * - Suffix-based: *-transcribe, whisper-* -> Transcription, rest -> Completion
 */
object ModelFetcher {

    /** Irrelevant OpenAI model prefixes (Embeddings, Image, Audio-Output, Moderation). */
    private val OPENAI_BLOCKLIST = listOf(
        "text-embedding-", "dall-e-", "tts-", "gpt-image-",
        "omni-moderation-", "babbage-", "davinci-"
    )

    /** Suffixes that count as blocklist matches (e.g. "-tts" at the end). */
    private val OPENAI_SUFFIX_BLOCKLIST = listOf("-tts")

    /**
     * Fetches models for a provider (blocking, must be called from background thread).
     * @return List of available models, filtered by relevance.
     * @throws Exception on network/auth errors (caller must handle).
     */
    @JvmStatic
    fun fetchModels(
        provider: AIProvider,
        apiKey: String,
        sp: SharedPreferences,
        forTranscription: Boolean
    ): List<ModelInfo> {
        // Custom + Anthropic: No API fetching (free-text input in Settings UI)
        // Anthropic has no OpenAI-compatible /models endpoint
        if (provider == AIProvider.CUSTOM || provider == AIProvider.ANTHROPIC) return emptyList()

        val client = OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl(provider.defaultBaseUrl)
            .timeout(Duration.ofSeconds(30))
            .maxRetries(2)
            .also { builder ->
                if (sp.get(Pref.ProxyEnabled)) {
                    val proxyHost = sp.get(Pref.ProxyHost)
                    if (DictateUtils.isValidProxy(proxyHost)) {
                        DictateUtils.applyProxy(builder, sp)
                    }
                }
            }
            .build()

        val allModels = client.models().list().data()

        return allModels
            .map { ModelInfo(id = it.id(), displayName = it.id()) }
            .filter { filterModel(it.id, provider, forTranscription) }
            .sortedBy { it.id }
    }

    private fun filterModel(modelId: String, provider: AIProvider, forTranscription: Boolean): Boolean {
        val lower = modelId.lowercase()

        // OpenAI/Groq: Apply blocklist
        if (provider == AIProvider.OPENAI || provider == AIProvider.GROQ) {
            if (OPENAI_BLOCKLIST.any { lower.startsWith(it) }) return false
            if (OPENAI_SUFFIX_BLOCKLIST.any { lower.endsWith(it) }) return false
        }

        // Transcription/Completion separation
        val isTranscriptionModel = lower.contains("whisper") || lower.contains("transcribe")
        return if (forTranscription) isTranscriptionModel else !isTranscriptionModel
    }
}
