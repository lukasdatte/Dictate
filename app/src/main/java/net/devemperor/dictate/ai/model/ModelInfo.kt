package net.devemperor.dictate.ai.model

/**
 * Simple data class for an available model.
 * No pricing, no capabilities metadata - only what the API provides.
 */
data class ModelInfo(
    val id: String,           // e.g. "gpt-4o-mini-transcribe"
    val displayName: String   // e.g. "gpt-4o-mini-transcribe" (or API display_name if available)
)
