package net.devemperor.dictate.database.entity

/**
 * How a text reached its destination.
 *
 * Follows the Double-Enum pattern (see docs/DATABASE-PATTERNS.md): the SQL column
 * `text_insertions.insertion_method` has a CHECK constraint matching these values exactly
 * (retrofitted in schema v10 — the last outstanding Double-Enum debt).
 */
enum class InsertionMethod {
    /** InputConnection.commitText into the Android host field. */
    COMMIT,

    /** Clipboard + paste into the Android host field. */
    PASTE,

    /** Delivered to the paired Windows companion over HTTP (ADR-0019). */
    WINDOWS_DISPATCH,
}
