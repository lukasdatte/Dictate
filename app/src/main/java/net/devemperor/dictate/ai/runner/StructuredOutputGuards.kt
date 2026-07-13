package net.devemperor.dictate.ai.runner

import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.AIProviderException

/**
 * Runner-side policy guards for the structured `{message, output}` conversation
 * response. Pure and provider-agnostic so both the OpenAI-compatible and the
 * Anthropic runner share one decision (and one unit test).
 */
object StructuredOutputGuards {

    /**
     * Rejects a structured response the provider truncated at its token limit
     * (OpenAI `finish_reason = length`, Anthropic `stop_reason = max_tokens`).
     *
     * G2-2: a truncated `{message, output}` JSON has no closing brace, so the
     * lenient parser falls through and returns the raw, half-written JSON as the
     * `output` — which was then inserted verbatim into the user's text field.
     * The Anthropic tool path degraded to an empty output. Failing the turn
     * instead lets the pipeline fall back to the plain transcript (a truncated
     * rework is not a usable answer), matching how every other provider error is
     * surfaced.
     */
    fun requireNotTruncated(truncated: Boolean, provider: AIProvider?) {
        if (truncated) {
            throw AIProviderException(
                AIProviderException.ErrorType.SERVER_ERROR,
                "Structured response was truncated at the model's max_tokens limit",
                provider = provider
            )
        }
    }
}
