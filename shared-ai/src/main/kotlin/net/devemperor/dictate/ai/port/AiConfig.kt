package net.devemperor.dictate.ai.port

import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider

/**
 * Resolves the effective AI configuration for a function slot. The platform
 * (Android: SharedPreferences via DictatePrefs; Companion: active Profile in
 * SQLDelight) implements it. The AI core never sees preference keys.
 *
 * Behaviour parity note: `apiKey` MUST reproduce today's
 * `RunnerFactory.getApiKey` exactly, including the non-ASCII strip
 * (`replace(Regex("[^ -~]"), "")`) — see AiConfigParityTest.
 *
 * @see docs/decisions/0028-shared-ai-module.md
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §4.1
 */
interface AiConfig {
    fun provider(function: AIFunction): AIProvider
    fun modelName(function: AIFunction): String

    /** Effective key for the function's provider, already ASCII-stripped. */
    fun apiKey(function: AIFunction): String

    /** Base URL; for CUSTOM the resolved host, else provider.defaultBaseUrl. */
    fun baseUrl(function: AIFunction): String

    /**
     * Completion parameters already filtered against sentinels (temp < 0,
     * maxTokens <= 0, empty reasoning_effort dropped) — reproduces the former
     * AIOrchestrator.resolveParameters + ParameterRegistry.
     */
    fun completionParameters(provider: AIProvider, model: String): Map<String, Any>

    /** Parsed ElevenLabs keyterms for the active transcription config, or null. */
    fun elevenLabsKeyterms(): List<String>?
}
