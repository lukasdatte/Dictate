package net.devemperor.dictate.companion.data

import net.devemperor.dictate.companion.db.DictateCompanionDb
import net.devemperor.dictate.companion.db.Received_texts
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.model.ReceivedText
import net.devemperor.dictate.companion.domain.port.HistoryRepository
import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.protocol.SyncCursor

/**
 * [HistoryRepository] on SQLite.
 *
 * Both invariants of the port are enforced *in SQL*, not in Kotlin: the idempotency by the
 * `session_id` primary key + `ON CONFLICT DO UPDATE`, and the "a sync never downgrades a dispatch"
 * rule by `MAX(received_texts.dispatched, excluded.dispatched)`. Putting them in the statement means
 * a second writer (the sync route, the UI's re-insert) cannot forget them.
 */
class SqlDelightHistoryRepository(private val database: DictateCompanionDb) : HistoryRepository {

    private val queries = database.companionQueries

    override fun upsert(deviceId: String, item: SessionUpsert, receivedAt: Long): Boolean =
        // One transaction: the "did it already exist?" read and the write must not be separated by
        // another dispatch of the same session, or two concurrent retries would both report "new".
        database.transactionWithResult {
            val duplicate = queries.receivedTextById(item.sessionId).executeAsOneOrNull() != null

            queries.upsertReceivedText(
                session_id = item.sessionId,
                device_id = deviceId,
                text = item.text,
                created_at = item.createdAt,
                received_at = receivedAt,
                origin = item.origin,
                dispatched = item.dispatched,
            )

            duplicate
        }

    override fun upsertAll(deviceId: String, items: List<SessionUpsert>, receivedAt: Long): Int =
        database.transactionWithResult {
            items.forEach { item ->
                queries.upsertReceivedText(
                    session_id = item.sessionId,
                    device_id = deviceId,
                    text = item.text,
                    created_at = item.createdAt,
                    received_at = receivedAt,
                    origin = item.origin,
                    dispatched = item.dispatched,
                )
            }
            items.size
        }

    override fun recordDispatch(sessionId: String, at: Long, outcome: InsertionOutcome) {
        queries.recordDispatch(at = at, outcome = outcome, sessionId = sessionId)
    }

    override fun cursor(): SyncCursor? = queries.selectCursor().executeAsOneOrNull()?.let {
        SyncCursor(lastCreatedAt = it.created_at, lastSessionId = it.session_id)
    }

    override fun findById(sessionId: String): ReceivedText? =
        queries.receivedTextById(sessionId).executeAsOneOrNull()?.toDomain()

    override fun page(query: String?, limit: Int, offset: Int): List<ReceivedText> =
        queries.pageHistory(term = query.toTerm(), limit = limit.toLong(), offset = offset.toLong())
            .executeAsList()
            .map { it.toDomain() }

    override fun count(query: String?): Int =
        queries.countHistory(term = query.toTerm()).executeAsOne().toInt()

    /** A blank search is "everything" — and `instr(x, "")` is 1, so an empty term says exactly that. */
    private fun String?.toTerm(): String = this?.trim().orEmpty()

    private fun Received_texts.toDomain() = ReceivedText(
        sessionId = session_id,
        deviceId = device_id,
        text = text,
        createdAt = created_at,
        receivedAt = received_at,
        origin = origin,
        dispatched = dispatched,
        lastOutcome = last_outcome,
    )
}
