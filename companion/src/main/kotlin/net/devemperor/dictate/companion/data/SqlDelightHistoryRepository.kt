package net.devemperor.dictate.companion.data

import net.devemperor.dictate.companion.db.DictateCompanionDb
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.model.ReceivedText
import net.devemperor.dictate.companion.domain.port.HistoryRepository
import net.devemperor.dictate.companion.domain.session.SessionOrigin
import net.devemperor.dictate.companion.domain.session.toSession
import net.devemperor.dictate.companion.domain.session.toWire
import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.protocol.SyncCursor

/**
 * [HistoryRepository] on SQLite — the phone mirror over the Room-parity session model.
 *
 * A received text is stored as a `PHONE_SYNC` [sessions] row (the text is its final output) plus a
 * 1:1 [dispatch_state] row carrying the sync facts (device, watermark, dispatch flag, outcome). The
 * `received_texts` table this repository used to own was ablated in migration 2.sqm; the split keeps
 * `sessions` a clean Room parity (desktop-host.md §3.4/§3.5).
 *
 * Both port invariants stay enforced *in SQL*, not in Kotlin: idempotency by the `session_id`/`id`
 * primary keys + `ON CONFLICT DO UPDATE`, and "a sync never downgrades a dispatch" by
 * `MAX(dispatch_state.dispatched, excluded.dispatched)` (mirrored by a `coalesce` on
 * `sessions.inserted_at`). Putting them in the statements means a second writer cannot forget them.
 */
class SqlDelightHistoryRepository(private val database: DictateCompanionDb) : HistoryRepository {

    private val queries = database.companionQueries

    override fun upsert(deviceId: String, item: SessionUpsert, receivedAt: Long): Boolean =
        // One transaction: the "did it already exist?" read and the write must not be separated by
        // another dispatch of the same session, or two concurrent retries would both report "new".
        database.transactionWithResult {
            val duplicate = queries.receivedTextById(item.sessionId, ::toReceivedText).executeAsOneOrNull() != null
            writeSyncRow(deviceId, item, receivedAt)
            duplicate
        }

    override fun upsertAll(deviceId: String, items: List<SessionUpsert>, receivedAt: Long): Int =
        database.transactionWithResult {
            items.forEach { writeSyncRow(deviceId, it, receivedAt) }
            items.size
        }

    /**
     * Writes both halves of one received text. The session carries the Room-parity fields; the
     * dispatch_state carries the sync facts. `inserted_at` mirrors the dispatch flag so the archive
     * agrees with the dispatch_state on whether the text ever landed in a window.
     */
    private fun writeSyncRow(deviceId: String, item: SessionUpsert, receivedAt: Long) {
        queries.upsertSyncSession(
            id = item.sessionId,
            createdAt = item.createdAt,
            origin = item.origin.toSession(),
            finalOutputText = item.text,
            insertedAt = if (item.dispatched) receivedAt else null,
        )
        queries.upsertDispatchState(
            sessionId = item.sessionId,
            deviceId = deviceId,
            receivedAt = receivedAt,
            dispatched = item.dispatched,
        )
    }

    override fun recordDispatch(sessionId: String, at: Long, outcome: InsertionOutcome) {
        queries.recordDispatch(at = at, outcome = outcome, sessionId = sessionId)
    }

    override fun cursor(): SyncCursor? =
        queries.selectCursor { createdAt, id -> SyncCursor(lastCreatedAt = createdAt, lastSessionId = id) }
            .executeAsOneOrNull()

    override fun findById(sessionId: String): ReceivedText? =
        queries.receivedTextById(sessionId, ::toReceivedText).executeAsOneOrNull()

    override fun page(query: String?, limit: Int, offset: Int): List<ReceivedText> =
        queries.pageHistory(term = query.toTerm(), limit = limit.toLong(), offset = offset.toLong(), mapper = ::toReceivedText)
            .executeAsList()

    override fun count(query: String?): Int =
        queries.countHistory(term = query.toTerm()).executeAsOne().toInt()

    /** A blank search is "everything" — and `instr(x, "")` is 1, so an empty term says exactly that. */
    private fun String?.toTerm(): String = this?.trim().orEmpty()

    /**
     * The one row → domain mapping, shared by every read query via SQLDelight's mapper overload.
     *
     * `finalOutputText` is `!!`: a PHONE_SYNC session always carries it (the backfill and every
     * upsert set it from the wire text), and a null here would be an invariant breach worth
     * surfacing, not swallowing. `origin` comes back as the stored [SessionOrigin] and maps to the
     * five-value wire enum losslessly (`UNKNOWN` only ever travels *in*, folded to KEYBOARD).
     */
    private fun toReceivedText(
        id: String,
        deviceId: String,
        finalOutputText: String?,
        createdAt: Long,
        receivedAt: Long,
        origin: SessionOrigin,
        dispatched: Boolean,
        lastOutcome: InsertionOutcome?,
    ) = ReceivedText(
        sessionId = id,
        deviceId = deviceId,
        text = finalOutputText!!,
        createdAt = createdAt,
        receivedAt = receivedAt,
        origin = origin.toWire(),
        dispatched = dispatched,
        lastOutcome = lastOutcome,
    )
}
