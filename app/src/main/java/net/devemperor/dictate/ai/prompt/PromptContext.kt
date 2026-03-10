package net.devemperor.dictate.ai.prompt

/**
 * Identifies the prompt context so SystemPromptResolver can select
 * the appropriate context-specific system prompt.
 */
enum class PromptContext {
    /** User selected a rewording prompt and optional text. */
    REWORDING,
    /** User dictated a free-form request (instant/live prompt). */
    LIVE,
    /** Automated processing step in a prompt chain. */
    QUEUED
}
