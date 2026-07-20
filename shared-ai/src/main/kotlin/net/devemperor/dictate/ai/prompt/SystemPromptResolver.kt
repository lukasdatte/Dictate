package net.devemperor.dictate.ai.prompt

import net.devemperor.dictate.ai.port.PromptConfig

/**
 * Resolves the system prompt based on user settings and prompt context.
 * When "Predefined" is selected, returns the context-specific system prompt
 * instead of a generic one — so Rewording, Live and Queued each get
 * tailored instructions.
 *
 * The selection + custom-text values come from the [PromptConfig] port
 * (Android adapter: `AndroidPromptConfig` over SharedPreferences).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §6 A3.5
 */
class SystemPromptResolver(private val config: PromptConfig) {

    fun resolve(context: PromptContext): String? {
        val raw = when (config.systemPromptMode()) {
            PromptMode.NONE -> ""
            PromptMode.PREDEFINED -> when (context) {
                PromptContext.REWORDING -> PromptTemplates.SYSTEM_PROMPT_REWORDING
                PromptContext.LIVE -> PromptTemplates.SYSTEM_PROMPT_LIVE
                PromptContext.QUEUED -> PromptTemplates.SYSTEM_PROMPT_QUEUED
            }
            PromptMode.CUSTOM -> config.systemPromptCustomText()
        }
        return raw.ifEmpty { null }
    }

    companion object {
        @JvmStatic
        fun create(config: PromptConfig) = SystemPromptResolver(config)
    }
}
