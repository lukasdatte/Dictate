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
    val responseFormat: ResponseFormatKind
)
