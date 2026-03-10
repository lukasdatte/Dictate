package net.devemperor.dictate.ai.model

data class ParameterDef(
    val name: String,              // "temperature", "max_completion_tokens", "reasoning_effort"
    val type: ParameterType,       // FLOAT_RANGE, INT_RANGE, ENUM
    val min: Number? = null,       // e.g. 0.0f or 1
    val max: Number? = null,       // e.g. 2.0f or 128000
    val default: Number? = null,   // e.g. 1.0f – type matches SP key (Float for FLOAT_RANGE, Int for INT_RANGE)
    val enumValues: List<String>? = null,  // e.g. ["low", "medium", "high"]
    val modelFilter: ((String) -> Boolean)? = null,  // null = all models
    val mutuallyExclusiveWith: String? = null  // e.g. "top_p" for Anthropic temperature
)

enum class ParameterType {
    FLOAT_RANGE,   // SeekBar: Temperature (0.0-2.0), Top-P (0.0-1.0)
    INT_RANGE,     // EditText: Max Tokens (1-128000)
    ENUM           // Spinner: Reasoning Effort (low/medium/high)
}
