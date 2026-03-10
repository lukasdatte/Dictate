package net.devemperor.dictate.ai

enum class AIProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val supportsTranscription: Boolean,
    val supportsCompletion: Boolean,
    val isOpenAICompatible: Boolean  // Uses OpenAI API format (just different base URL)
) {
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1/",
        supportsTranscription = true,
        supportsCompletion = true,
        isOpenAICompatible = true
    ),
    GROQ(
        displayName = "Groq",
        defaultBaseUrl = "https://api.groq.com/openai/v1/",
        supportsTranscription = true,
        supportsCompletion = true,
        isOpenAICompatible = true
    ),
    ANTHROPIC(
        displayName = "Anthropic",
        defaultBaseUrl = "https://api.anthropic.com/v1/",
        supportsTranscription = false,
        supportsCompletion = true,
        isOpenAICompatible = false
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1/",
        supportsTranscription = false,  // OpenRouter has no /audio/transcriptions endpoint
        supportsCompletion = true,
        isOpenAICompatible = true
    ),
    CUSTOM(
        displayName = "Custom",
        defaultBaseUrl = "",  // Sentinel: Custom URL comes from SharedPreferences (Pref.TranscriptionCustomHost / Pref.RewordingCustomHost). RunnerFactory.getBaseUrl() resolves the value.
        supportsTranscription = true,
        supportsCompletion = true,
        isOpenAICompatible = true
    );

    companion object {
        /**
         * Reads provider from SharedPreferences (stored as enum name string).
         * Falls back to OPENAI for unknown values.
         */
        @JvmStatic
        fun fromPersistKey(key: String?): AIProvider =
            entries.find { it.name == key } ?: OPENAI

        /** All providers that support transcription. */
        @JvmStatic
        fun withTranscription(): List<AIProvider> =
            entries.filter { it.supportsTranscription }

        /** All providers that support completion. */
        @JvmStatic
        fun withCompletion(): List<AIProvider> =
            entries.filter { it.supportsCompletion }
    }
}

enum class AIFunction {
    TRANSCRIPTION,
    COMPLETION
}
