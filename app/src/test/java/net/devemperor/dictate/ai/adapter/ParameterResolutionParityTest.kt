package net.devemperor.dictate.ai.adapter

import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.put
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Characterization test for the AIOrchestrator.resolveParameters → [AndroidAiConfig].
 * completionParameters move (A3.6): the sentinel filter (temp < 0, maxTokens <= 0,
 * empty reasoning_effort) and the ParameterRegistry-driven key set stay identical.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §8.1
 */
class ParameterResolutionParityTest {

    private val sp = FakeSharedPreferences()
    private val config = AndroidAiConfig(sp)

    @Test
    fun `openai non-reasoning model maps temperature and max tokens`() {
        sp.edit()
            .put(Pref.TemperatureOpenAI, 0.5f)
            .put(Pref.MaxTokensOpenAI, 1000)
            .apply()

        val params = config.completionParameters(AIProvider.OPENAI, "gpt-4o-mini")
        assertEquals(0.5f, params["temperature"] as Float, 0.0f)
        assertEquals(1000, params["max_completion_tokens"])
        assertEquals(2, params.size)
    }

    @Test
    fun `openai default sentinels drop temperature and max tokens`() {
        // Defaults: TemperatureOpenAI = -1f, MaxTokensOpenAI = -1 → both filtered.
        val params = config.completionParameters(AIProvider.OPENAI, "gpt-4o-mini")
        assertEquals(emptyMap<String, Any>(), params)
    }

    @Test
    fun `openai reasoning model maps reasoning_effort only when set`() {
        sp.edit().put(Pref.ReasoningEffortOpenAI, "high").apply()

        val params = config.completionParameters(AIProvider.OPENAI, "gpt-5")
        assertEquals("high", params["reasoning_effort"])
        // temperature/top_p are filtered out of the def set for reasoning models;
        // max_completion_tokens stays sentinel-dropped (default -1).
        assertEquals(1, params.size)
    }

    @Test
    fun `openai reasoning model drops empty reasoning_effort`() {
        // Default ReasoningEffortOpenAI = "" → filtered.
        val params = config.completionParameters(AIProvider.OPENAI, "gpt-5")
        assertEquals(emptyMap<String, Any>(), params)
    }

    @Test
    fun `anthropic maps temperature and default max tokens`() {
        sp.edit().put(Pref.TemperatureAnthropic, 0.7f).apply()
        // MaxTokensAnthropic default = 4096 (> 0) → retained.
        val params = config.completionParameters(AIProvider.ANTHROPIC, "claude-test")
        assertEquals(0.7f, params["temperature"] as Float, 0.0f)
        assertEquals(4096, params["max_tokens"])
        assertEquals(2, params.size)
    }

    @Test
    fun `elevenlabs has no completion parameters`() {
        assertEquals(emptyMap<String, Any>(), config.completionParameters(AIProvider.ELEVENLABS, "scribe_v2"))
    }
}
