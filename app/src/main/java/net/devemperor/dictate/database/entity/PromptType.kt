package net.devemperor.dictate.database.entity

/**
 * Discriminates the two keyboard prompt-pill kinds.
 *
 * Follows the Double-Enum pattern (see docs/DATABASE-PATTERNS.md): the
 * `prompts.type` SQL column carries a CHECK constraint matching these values
 * exactly, so a new kind cannot be persisted without a migration.
 *
 * - [PROMPT]: an AI instruction. Clicking runs it — standalone rewording when
 *   idle, queue-toggle during recording for selection prompts.
 * - [TEXT]: a literal snippet. Clicking always inserts its content 1:1 into the
 *   host field — no AI call, never queued, never greyed out. Replaces the former
 *   `[bracketed]` string convention (see ADR — prompt pill types).
 */
enum class PromptType {
    PROMPT,
    TEXT
}
