package net.devemperor.dictate.core

import net.devemperor.dictate.ai.runner.CompletionResult

/**
 * Result of AutoFormattingService.formatIfEnabled().
 *
 * Three states:
 * - Disabled: text = original, completionResult = null, error = null
 * - Success:  text = formatted, completionResult = non-null, error = null
 * - Failed:   text = original (fallback), completionResult = null, error = non-null
 */
data class FormatResult(
    val text: String,
    val completionResult: CompletionResult?,
    val error: Exception? = null
)
