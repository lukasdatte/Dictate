package net.devemperor.dictate.ai.runner

data class CompletionResult(
    val text: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val modelName: String
)
