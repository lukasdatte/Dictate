package net.devemperor.dictate.ai.runner

/**
 * Executes chat completions (rewording, auto-formatting).
 * Implementations: OpenAICompatibleRunner, AnthropicCompletionRunner.
 *
 * @throws net.devemperor.dictate.ai.AIProviderException on API errors (typed, no string parsing)
 */
interface CompletionRunner {
    fun complete(options: CompletionOptions): CompletionResult

    /**
     * Executes a structured, multi-turn conversation turn (ADR-0012) and
     * returns the parsed `{message, output}`. Providers emit structured output
     * natively (OpenAI response_format / Anthropic forced tool-use); the
     * CUSTOM / OpenRouter path falls back to plain text + lenient parsing.
     *
     * @throws net.devemperor.dictate.ai.AIProviderException on API errors
     */
    fun converse(request: ConversationRequest): ConversationResult
}
