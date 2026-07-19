package net.devemperor.dictate.ai.runner

/**
 * Transcribes audio files to text.
 * Implementations: OpenAICompatibleRunner (OpenAI, Groq, Custom).
 *
 * @throws net.devemperor.dictate.ai.AIProviderException on API errors (typed, no string parsing)
 */
interface TranscriptionRunner {
    fun transcribe(options: TranscriptionOptions): TranscriptionResult
}
