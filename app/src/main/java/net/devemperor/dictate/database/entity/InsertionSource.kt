package net.devemperor.dictate.database.entity

/**
 * Audit classifier for an [net.devemperor.dictate.state.insertion.InsertionRequest]:
 * *what kind of pipeline output* produced the committed text.
 *
 * **Not DB-constrained.** This value is an in-memory audit label only —
 * the [net.devemperor.dictate.core.SessionManager.logTextInsertion] path
 * persists an [InsertionMethod] (COMMIT / PASTE), never this enum. Adding
 * a value therefore requires no Room migration (contrast with the
 * Double-Enum columns in `docs/DATABASE-PATTERNS.md`, which back a SQL
 * CHECK constraint).
 */
enum class InsertionSource {
    TRANSCRIPTION,
    STATIC_PROMPT,
    REWORDING,
    QUEUED_PROMPT,

    /**
     * A deferred pending part flushed back into the host field (R4). Its
     * session finished while no host field was available; the
     * [net.devemperor.dictate.state.insertion.PendingPartsFlusher] later
     * re-inserts it in recording order. @see ADR-0009, spec §3.5.
     */
    PENDING_PART
}
