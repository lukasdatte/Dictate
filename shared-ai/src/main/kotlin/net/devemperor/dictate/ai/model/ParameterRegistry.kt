package net.devemperor.dictate.ai.model

import net.devemperor.dictate.ai.AIProvider

object ParameterRegistry {

    private val isNotReasoningModel: (String) -> Boolean = { model ->
        !model.startsWith("o1") && !model.startsWith("o3") && !model.startsWith("o4") &&
        !model.startsWith("gpt-5")
    }

    private val isReasoningModel: (String) -> Boolean = { model ->
        model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4") ||
        model.startsWith("gpt-5")
    }

    private val OPENAI_COMPLETION = listOf(
        ParameterDef("temperature", ParameterType.FLOAT_RANGE, 0.0f, 2.0f, 1.0f,
            modelFilter = isNotReasoningModel),
        ParameterDef("max_completion_tokens", ParameterType.INT_RANGE, 1, 128000),
        ParameterDef("top_p", ParameterType.FLOAT_RANGE, 0.0f, 1.0f, 1.0f,
            modelFilter = isNotReasoningModel),
        ParameterDef("frequency_penalty", ParameterType.FLOAT_RANGE, -2.0f, 2.0f, 0.0f,
            modelFilter = isNotReasoningModel),
        ParameterDef("presence_penalty", ParameterType.FLOAT_RANGE, -2.0f, 2.0f, 0.0f,
            modelFilter = isNotReasoningModel),
        ParameterDef("reasoning_effort", ParameterType.ENUM,
            enumValues = listOf("none", "low", "medium", "high", "xhigh"),
            modelFilter = isReasoningModel)
    )

    private val ANTHROPIC_COMPLETION = listOf(
        ParameterDef("temperature", ParameterType.FLOAT_RANGE, 0.0f, 1.0f, 1.0f,
            mutuallyExclusiveWith = "top_p"),
        ParameterDef("max_tokens", ParameterType.INT_RANGE, 1, 200000, 4096),
        ParameterDef("top_p", ParameterType.FLOAT_RANGE, 0.0f, 1.0f, 0.99f,
            mutuallyExclusiveWith = "temperature"),
        ParameterDef("top_k", ParameterType.INT_RANGE, 1, 500)
    )

    private val GROQ_COMPLETION = listOf(
        ParameterDef("temperature", ParameterType.FLOAT_RANGE, 0.0f, 2.0f, 1.0f),
        ParameterDef("max_completion_tokens", ParameterType.INT_RANGE, 1, 32768),
        ParameterDef("top_p", ParameterType.FLOAT_RANGE, 0.0f, 1.0f, 1.0f)
    )

    private val OPENAI_TRANSCRIPTION = listOf(
        ParameterDef("temperature", ParameterType.FLOAT_RANGE, 0.0f, 1.0f, 0.0f)
    )

    private val ELEVENLABS_TRANSCRIPTION = listOf(
        ParameterDef("temperature", ParameterType.FLOAT_RANGE, 0.0f, 2.0f, 0.0f)
    )

    @JvmStatic
    fun getCompletionParameters(provider: AIProvider, modelId: String): List<ParameterDef> {
        val params = when (provider) {
            AIProvider.OPENAI -> OPENAI_COMPLETION
            AIProvider.ANTHROPIC -> ANTHROPIC_COMPLETION
            AIProvider.GROQ -> GROQ_COMPLETION
            AIProvider.ELEVENLABS -> emptyList()  // No completion support
            AIProvider.OPENROUTER -> OPENAI_COMPLETION  // Fallback, later API enrichment
            AIProvider.CUSTOM -> OPENAI_COMPLETION
        }
        return params.filter { it.modelFilter?.invoke(modelId) ?: true }
    }

    /**
     * Transcription parameters (currently only temperature for Whisper).
     * Not automatically resolved by AIOrchestrator.transcribe() –
     * TranscriptionOptions.temperature is passed directly from the God class.
     * This method is used by the Settings UI (Task 6.1)
     * to dynamically generate parameter fields for transcription.
     */
    @JvmStatic
    fun getTranscriptionParameters(provider: AIProvider): List<ParameterDef> {
        return when (provider) {
            AIProvider.OPENAI, AIProvider.GROQ -> OPENAI_TRANSCRIPTION
            AIProvider.ELEVENLABS -> ELEVENLABS_TRANSCRIPTION
            else -> emptyList()
        }
    }
}
