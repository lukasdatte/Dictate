package net.devemperor.dictate.database.entity

/**
 * How a conversational assistant turn's structured `{message, output}` answer
 * was obtained (ADR-0012). Persisted on `processing_steps.response_format` for
 * serviceability — a maintainer can see which parse path produced a step.
 *
 * Follows the Double-Enum pattern (see docs/DATABASE-PATTERNS.md): the SQL
 * column carries a CHECK constraint matching these values. The column is
 * nullable — `NULL` marks a non-conversational step (auto-format / queued /
 * rewording rows and every row written before schema v8).
 */
enum class ResponseFormatKind {
    JSON_SCHEMA,    // OpenAI-compatible response_format = json_schema
    TOOL_USE,       // Anthropic forced tool-use
    TEXT_FALLBACK   // provider rejected structured output; lenient-parsed plain text
}
