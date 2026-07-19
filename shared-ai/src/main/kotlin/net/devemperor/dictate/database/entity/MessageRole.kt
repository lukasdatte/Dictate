package net.devemperor.dictate.database.entity

/**
 * Role of a [ConversationMessageEntity] row.
 *
 * Follows the Double-Enum pattern (see docs/DATABASE-PATTERNS.md): the SQL
 * `conversation_messages.role` column has a CHECK constraint matching these
 * values exactly.
 *
 * `ASSISTANT` is defined and permitted by the CHECK from schema v8 on, but the
 * conversation foundation (ADR-0012, Paket 1) does NOT write assistant rows —
 * assistant turns are reconstructed from the current [ProcessingStepEntity]
 * chain (design decision D-B). Reserving the value lets a later package add a
 * self-contained assistant message log without a migration.
 *
 * This is a plain Kotlin enum with no Android imports, so the Android-free
 * conversation domain (`ai/conversation`) may reference it directly.
 */
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT
}
