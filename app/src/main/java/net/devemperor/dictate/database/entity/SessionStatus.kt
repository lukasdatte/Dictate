package net.devemperor.dictate.database.entity

/**
 * Terminal persisted state of a [SessionEntity].
 * Runtime state (TRANSCRIBING, PROCESSING) is NOT stored here —
 * it lives in ActiveJobRegistry.
 *
 * Follows the Double-Enum pattern (see docs/DATABASE-PATTERNS.md):
 * the SQL column has a CHECK constraint matching these values exactly.
 */
enum class SessionStatus {
    RECORDED,   // Audio persistent, no processing run (yet) or aborted before DB write of result
    COMPLETED,  // Pipeline finished successfully
    FAILED,     // Pipeline finished with an error (API, quota, network, ...)
    CANCELLED   // User explicitly cancelled
}
