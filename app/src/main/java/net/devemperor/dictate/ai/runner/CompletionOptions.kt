package net.devemperor.dictate.ai.runner

data class CompletionOptions(
    val prompt: String,
    val model: String,
    val systemPrompt: String? = null,
    val parameters: Map<String, Any> = emptyMap()  // Dynamic parameters from ParameterRegistry
)
