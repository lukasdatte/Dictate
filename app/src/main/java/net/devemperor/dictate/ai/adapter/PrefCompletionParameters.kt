package net.devemperor.dictate.ai.adapter

import android.content.SharedPreferences
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.model.ParameterRegistry
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get

/**
 * Resolves the completion-parameter map (`temperature`, `max_tokens`/`max_completion_tokens`,
 * `reasoning_effort`) from the legacy per-provider parameter prefs. Extracted verbatim from the
 * former `AndroidAiConfig.completionParameters` so the entity-migration path
 * ([net.devemperor.dictate.config.ConfigEntityMigration.parameterDefaults]) keeps a byte-identical
 * key set and sentinel filtering.
 *
 * These parameter prefs are **non-secret**, so this reader stays in `src/main`. The pref-based
 * `AiConfig` that also read the *secret* API-key prefs was retired to test sources (spec
 * secretstore.md §2.6 — no code outside `DictatePrefs`/`SecretsMigration` reads the secret prefs).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/androidaiconfig-secret-pref-retirement.md
 */
object PrefCompletionParameters {

    /**
     * Type-safe mapping: Provider -> Parameter Pref objects.
     * No dynamic key construction - every access goes through Pref<T>.
     */
    private data class ProviderParamPrefs(
        val temperature: Pref<Float>? = null,
        val maxTokens: Pref<Int>? = null,
        val reasoningEffort: Pref<String>? = null,
    )

    private val PARAMETER_PREFS = mapOf(
        AIProvider.OPENAI to ProviderParamPrefs(Pref.TemperatureOpenAI, Pref.MaxTokensOpenAI, Pref.ReasoningEffortOpenAI),
        AIProvider.GROQ to ProviderParamPrefs(Pref.TemperatureGroq, Pref.MaxTokensGroq),
        AIProvider.ANTHROPIC to ProviderParamPrefs(Pref.TemperatureAnthropic, Pref.MaxTokensAnthropic),
        AIProvider.OPENROUTER to ProviderParamPrefs(Pref.TemperatureOpenRouter, Pref.MaxTokensOpenRouter),
        AIProvider.CUSTOM to ProviderParamPrefs(Pref.TemperatureOpenAI, Pref.MaxTokensOpenAI), // Custom uses OpenAI defaults
    )

    fun of(sp: SharedPreferences, provider: AIProvider, model: String): Map<String, Any> {
        val defs = ParameterRegistry.getCompletionParameters(provider, model)
        val prefs = PARAMETER_PREFS[provider] ?: return emptyMap()
        val params = mutableMapOf<String, Any>()
        for (def in defs) {
            val value: Any? = when (def.name) {
                "temperature" -> prefs.temperature?.let { sp.get(it).takeIf { v -> v >= 0f } }
                "max_completion_tokens", "max_tokens" -> prefs.maxTokens?.let { sp.get(it).takeIf { v -> v > 0 } }
                "reasoning_effort" -> prefs.reasoningEffort?.let { sp.get(it).takeIf { v -> v.isNotEmpty() } }
                else -> null // top_p, frequency_penalty etc. - extendable later
            }
            if (value != null) params[def.name] = value
        }
        return params
    }
}
