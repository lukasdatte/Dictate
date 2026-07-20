package net.devemperor.dictate.ai.adapter

import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.put
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Characterization test for the RunnerFactory → [AndroidAiConfig] move (A3.3):
 * provider / modelName / apiKey / baseUrl resolve to exactly today's values,
 * including the non-ASCII key strip and CUSTOM host resolution. Written against
 * the adapter that now carries the former `RunnerFactory` logic verbatim, so a
 * drift in the move surfaces here.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §8.1
 */
class AiConfigParityTest {

    private val sp = FakeSharedPreferences()
    private val config = AndroidAiConfig(sp)

    @Test
    fun `openai transcription resolves provider model key baseUrl`() {
        sp.edit()
            .put(Pref.TranscriptionProvider, "OPENAI")
            .put(Pref.TranscriptionOpenAIModel, "whisper-1")
            .put(Pref.TranscriptionApiKeyOpenAI, "sk-openai")
            .apply()

        assertEquals(AIProvider.OPENAI, config.provider(AIFunction.TRANSCRIPTION))
        assertEquals("whisper-1", config.modelName(AIFunction.TRANSCRIPTION))
        assertEquals("sk-openai", config.apiKey(AIFunction.TRANSCRIPTION))
        assertEquals("https://api.openai.com/v1/", config.baseUrl(AIFunction.TRANSCRIPTION))
    }

    @Test
    fun `apiKey strips non-ASCII characters`() {
        sp.edit()
            .put(Pref.RewordingProvider, "OPENAI")
            .put(Pref.RewordingApiKeyOpenAI, "sk-café✓123")
            .apply()

        assertEquals("sk-caf123", config.apiKey(AIFunction.COMPLETION))
    }

    @Test
    fun `custom completion resolves host as baseUrl`() {
        sp.edit()
            .put(Pref.RewordingProvider, "CUSTOM")
            .put(Pref.RewordingCustomModel, "my-model")
            .put(Pref.RewordingCustomHost, "https://custom.example/v1/")
            .put(Pref.RewordingApiKeyCustom, "custom-key")
            .apply()

        assertEquals(AIProvider.CUSTOM, config.provider(AIFunction.COMPLETION))
        assertEquals("my-model", config.modelName(AIFunction.COMPLETION))
        assertEquals("custom-key", config.apiKey(AIFunction.COMPLETION))
        assertEquals("https://custom.example/v1/", config.baseUrl(AIFunction.COMPLETION))
    }

    @Test
    fun `custom transcription resolves transcription host`() {
        sp.edit()
            .put(Pref.TranscriptionProvider, "CUSTOM")
            .put(Pref.TranscriptionCustomModel, "t-model")
            .put(Pref.TranscriptionCustomHost, "https://t.example/v1/")
            .apply()

        assertEquals("t-model", config.modelName(AIFunction.TRANSCRIPTION))
        assertEquals("https://t.example/v1/", config.baseUrl(AIFunction.TRANSCRIPTION))
    }

    @Test
    fun `anthropic completion resolves model key and default baseUrl`() {
        sp.edit()
            .put(Pref.RewordingProvider, "ANTHROPIC")
            .put(Pref.RewordingAnthropicModel, "claude-test")
            .put(Pref.RewordingApiKeyAnthropic, "ak")
            .apply()

        assertEquals(AIProvider.ANTHROPIC, config.provider(AIFunction.COMPLETION))
        assertEquals("claude-test", config.modelName(AIFunction.COMPLETION))
        assertEquals("ak", config.apiKey(AIFunction.COMPLETION))
        assertEquals("https://api.anthropic.com/v1/", config.baseUrl(AIFunction.COMPLETION))
    }

    @Test
    fun `groq completion resolves default baseUrl`() {
        sp.edit()
            .put(Pref.RewordingProvider, "GROQ")
            .put(Pref.RewordingGroqModel, "llama-x")
            .put(Pref.RewordingApiKeyGroq, "gk")
            .apply()

        assertEquals(AIProvider.GROQ, config.provider(AIFunction.COMPLETION))
        assertEquals("llama-x", config.modelName(AIFunction.COMPLETION))
        assertEquals("https://api.groq.com/openai/v1/", config.baseUrl(AIFunction.COMPLETION))
    }

    @Test
    fun `openrouter completion resolves default baseUrl`() {
        sp.edit()
            .put(Pref.RewordingProvider, "OPENROUTER")
            .put(Pref.RewordingOpenRouterModel, "or-model")
            .put(Pref.RewordingApiKeyOpenRouter, "ork")
            .apply()

        assertEquals(AIProvider.OPENROUTER, config.provider(AIFunction.COMPLETION))
        assertEquals("or-model", config.modelName(AIFunction.COMPLETION))
        assertEquals("https://openrouter.ai/api/v1/", config.baseUrl(AIFunction.COMPLETION))
    }

    @Test
    fun `elevenlabs transcription resolves model key and default baseUrl`() {
        sp.edit()
            .put(Pref.TranscriptionProvider, "ELEVENLABS")
            .put(Pref.TranscriptionElevenLabsModel, "scribe_v2")
            .put(Pref.TranscriptionApiKeyElevenLabs, "ek")
            .apply()

        assertEquals(AIProvider.ELEVENLABS, config.provider(AIFunction.TRANSCRIPTION))
        assertEquals("scribe_v2", config.modelName(AIFunction.TRANSCRIPTION))
        assertEquals("ek", config.apiKey(AIFunction.TRANSCRIPTION))
        assertEquals("https://api.elevenlabs.io/v1/", config.baseUrl(AIFunction.TRANSCRIPTION))
    }

    @Test
    fun `unknown provider key falls back to OPENAI`() {
        sp.edit().put(Pref.RewordingProvider, "SOMETHING_ELSE").apply()
        assertEquals(AIProvider.OPENAI, config.provider(AIFunction.COMPLETION))
    }

    @Test
    fun `elevenLabsKeyterms is null for non-elevenlabs transcription provider`() {
        sp.edit()
            .put(Pref.TranscriptionProvider, "OPENAI")
            .put(Pref.ElevenLabsKeytermsParsed, """["Alpha"]""")
            .apply()
        assertEquals(null, config.elevenLabsKeyterms())
    }

    @Test
    fun `elevenLabsKeyterms is null when parsed list is empty`() {
        sp.edit()
            .put(Pref.TranscriptionProvider, "ELEVENLABS")
            .put(Pref.ElevenLabsKeytermsParsed, "[]")
            .apply()
        assertEquals(null, config.elevenLabsKeyterms())
    }

    @Test
    fun `elevenLabsKeyterms returns parsed terms for elevenlabs provider`() {
        sp.edit()
            .put(Pref.TranscriptionProvider, "ELEVENLABS")
            .put(Pref.ElevenLabsKeytermsParsed, """["Alpha","Beta"]""")
            .apply()
        assertEquals(listOf("Alpha", "Beta"), config.elevenLabsKeyterms())
    }
}
