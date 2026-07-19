package net.devemperor.dictate.ai.runner

import java.io.File

data class TranscriptionOptions(
    val audioFile: File,
    val model: String,
    val language: String? = null,      // null = auto-detect
    val stylePrompt: String? = null,
    val temperature: Float? = null,     // 0-1 for Whisper, null = server default
    val keyterms: List<String>? = null  // ElevenLabs Scribe v2 only
)
