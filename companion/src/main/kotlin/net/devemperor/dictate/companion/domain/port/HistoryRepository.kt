package net.devemperor.dictate.companion.domain.port

import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.model.ReceivedText
import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.protocol.SyncCursor

/**
 * The texts this PC has received — the derived copy of the phone's history (ADR-0020).
 *
 * **Two invariants every implementation owes, and the reason they live here and not in a service:**
 *
 * 1. [upsert] is idempotent over `sessionId`. A dispatch that the phone retried after an ambiguous
 *    timeout must not produce a second row.
 * 2. [upsert] must never **downgrade** `dispatched` from true to false. The lazy sync mirrors rows
 *    the phone believes were never dispatched; if it ran after a successful dispatch it would
 *    otherwise erase the very fact that the text was typed here.
 */
interface HistoryRepository {

    /** @return true if [SessionUpsert.sessionId] was already known — i.e. this was a duplicate. */
    fun upsert(deviceId: String, item: SessionUpsert, receivedAt: Long): Boolean

    /**
     * One sync page, atomically. @return how many rows were written.
     *
     * All-or-nothing, because the phone pages on from the cursor this page leaves behind: a page
     * half-applied would advance the watermark past rows that were never stored, and those rows
     * would never be offered again. (The protocol would survive it — an upsert is idempotent — but
     * only after a full resend, which is precisely what the cursor exists to avoid.)
     */
    fun upsertAll(deviceId: String, items: List<SessionUpsert>, receivedAt: Long): Int

    fun recordDispatch(sessionId: String, at: Long, outcome: InsertionOutcome)

    /**
     * The receive watermark: the greatest `(createdAt, sessionId)` on file, or null when empty.
     *
     * null is the self-healing signal — a companion whose database was wiped says "I know nothing",
     * and the phone resends its history from the beginning (ADR-0020).
     */
    fun cursor(): SyncCursor?

    fun findById(sessionId: String): ReceivedText?

    /** Newest first (`createdAt DESC`). [query] is a case-insensitive substring of the text. */
    fun page(query: String?, limit: Int, offset: Int): List<ReceivedText>

    fun count(query: String?): Int
}
