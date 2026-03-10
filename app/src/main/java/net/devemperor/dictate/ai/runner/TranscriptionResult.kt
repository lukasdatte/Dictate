package net.devemperor.dictate.ai.runner

data class TranscriptionResult(
    val text: String,
    val audioDurationSeconds: Long,
    val modelName: String
)
