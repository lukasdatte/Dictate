package net.devemperor.dictate.ai.runner

import net.devemperor.dictate.database.entity.ResponseFormatKind

/**
 * Result of a structured conversation turn (ADR-0012): the parsed
 * `{message, output}` plus usage and the parse path actually taken (persisted
 * on the step for serviceability).
 */
data class ConversationResult(
    val message: String?,
    val output: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val modelName: String,
    val responseFormat: ResponseFormatKind,
    /**
     * The model's ambiguity verdict (ADR-0013) — `true` when it had to guess.
     * Transient (never persisted); the IME's [ReviewDecision] consumes it at
     * completion time. `false` for fallback providers that omit the field.
     */
    val needsClarification: Boolean = false
)
