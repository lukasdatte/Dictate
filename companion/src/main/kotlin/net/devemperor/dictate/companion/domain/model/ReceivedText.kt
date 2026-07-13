package net.devemperor.dictate.companion.domain.model

import net.devemperor.dictate.shared.protocol.SessionOriginWire

/**
 * One dictation this PC knows about — either dispatched here, or merely mirrored by the sync.
 *
 * The phone is the authority; this is a derived copy (ADR-0020). [sessionId] is the phone's Room
 * session id and doubles as the idempotency key.
 */
data class ReceivedText(
    val sessionId: String,
    val deviceId: String,
    val text: String,
    /** Phone time. Together with [sessionId] it forms the total sync-cursor order. */
    val createdAt: Long,
    /** PC time — when this row was last written here. */
    val receivedAt: Long,
    val origin: SessionOriginWire,
    /** true = arrived through `/v1/dispatch`; false = only synced for the archive. */
    val dispatched: Boolean,
    /** null for a row that was never inserted here (a pure sync row). */
    val lastOutcome: InsertionOutcome? = null,
)
