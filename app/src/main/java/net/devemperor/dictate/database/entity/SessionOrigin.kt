package net.devemperor.dictate.database.entity

/**
 * Where a session was initiated from. Used to distinguish keyboard-originated
 * sessions from sessions started out of the history UI.
 *
 * Follows the Double-Enum pattern (see docs/DATABASE-PATTERNS.md).
 *
 * Valid type x origin combinations (Finding SEC-1-4):
 *
 * | SessionType     | KEYBOARD | HISTORY_REPROCESS | POST_PROCESSING | REVIEW_REFINEMENT |
 * |-----------------|----------|-------------------|-----------------|-------------------|
 * | RECORDING       |   yes    |       yes         |       no        |       yes         |
 * | REWORDING       |   yes    |       no          |       no        |       no          |
 * | POST_PROCESSING |   no     |       no          |       yes       |       no          |
 *
 * Note: POST_PROCESSING type + POST_PROCESSING origin is intentionally
 * redundant — origin confirms provenance, type classifies the session's
 * content. This allows queries like "all sessions from history" regardless
 * of whether they are recordings or post-processing chains.
 */
enum class SessionOrigin {
    KEYBOARD,           // Started from the IME keyboard (normal recording flow)
    HISTORY_REPROCESS,  // Started from HistoryDetailActivity (re-transcribe / re-run)
    POST_PROCESSING,    // Post-processing chain (child of another session)

    // The transcription-only S2 recording that backs a dictated review
    // refinement (ADR-0013): its transcript becomes the next user turn on the
    // reviewed conversation, it is never inserted itself. Tagged so the
    // in-keyboard history panel can hide these carrier recordings while the
    // full-screen HistoryActivity still shows them (Paket 3 / ADR-0014).
    REVIEW_REFINEMENT
}
