package net.devemperor.dictate.ai.runner

/**
 * Executes chat completions (rewording, auto-formatting).
 * Implementations: OpenAICompatibleRunner, AnthropicCompletionRunner.
 *
 * @throws net.devemperor.dictate.ai.AIProviderException on API errors (typed, no string parsing)
 */
interface CompletionRunner {
    fun complete(options: CompletionOptions): CompletionResult
}
