package net.devemperor.dictate.ai.port

import net.devemperor.dictate.ai.prompt.PromptMode

/**
 * Resolves the user's prompt-selection settings for [net.devemperor.dictate.ai.prompt.PromptService]
 * and [net.devemperor.dictate.ai.prompt.SystemPromptResolver]. Android backs it with
 * SharedPreferences (StylePromptSelection / SystemPromptSelection + their custom-text
 * keys); the Companion with its own settings.
 *
 * This is the "schmaler Prompt-Config-Zugang" the spec §6 A3.5 sanctions as an
 * alternative to widening [AiConfig]: prompt selection is a distinct concern from
 * runner configuration (provider/model/key), so it gets its own narrow port
 * (Interface Segregation). The AI core never sees preference keys.
 *
 * @see docs/decisions/0028-shared-ai-module.md
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §4.1 (Schnitt-Begründung), §6 A3.5
 */
interface PromptConfig {
    /** Selection mode for the Whisper style prompt (StylePromptSelection). */
    fun stylePromptMode(): PromptMode

    /** Custom style-prompt text (StylePromptCustomText); empty string when unset. */
    fun stylePromptCustomText(): String

    /** Selection mode for the completion system prompt (SystemPromptSelection). */
    fun systemPromptMode(): PromptMode

    /** Custom system-prompt text (SystemPromptCustomText); empty string when unset. */
    fun systemPromptCustomText(): String
}
