package net.devemperor.dictate.companion.data.memory

import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.model.ReceivedText
import net.devemperor.dictate.companion.domain.port.HistoryRepository
import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.protocol.SyncCursor
import net.devemperor.dictate.shared.sync.Cursor
import java.util.concurrent.ConcurrentHashMap

/**
 * The history store until SQLDelight lands (`wd-5`).
 *
 * It honours the two [HistoryRepository] invariants literally — idempotent over `sessionId`, and
 * `dispatched` never walks back from true to false — so that the E2E suite written against it in
 * `wd-4` keeps its meaning when the SQL implementation takes over in `wd-5`.
 */
class InMemoryHistoryRepository : HistoryRepository {

    private val rows = ConcurrentHashMap<String, ReceivedText>()

    override fun upsert(deviceId: String, item: SessionUpsert, receivedAt: Long): Boolean {
        var duplicate = false
        rows.compute(item.sessionId) { _, existing ->
            duplicate = existing != null
            ReceivedText(
                sessionId = item.sessionId,
                deviceId = deviceId,
                text = item.text,
                createdAt = item.createdAt,
                receivedAt = receivedAt,
                origin = item.origin,
                // A sync must not un-dispatch a row a dispatch already claimed (see the port's doc).
                dispatched = item.dispatched || (existing?.dispatched ?: false),
                lastOutcome = existing?.lastOutcome,
            )
        }
        return duplicate
    }

    override fun recordDispatch(sessionId: String, at: Long, outcome: InsertionOutcome) {
        rows.computeIfPresent(sessionId) { _, row ->
            row.copy(receivedAt = at, dispatched = true, lastOutcome = outcome)
        }
    }

    override fun cursor(): SyncCursor? = rows.values
        .map { SyncCursor(lastCreatedAt = it.createdAt, lastSessionId = it.sessionId) }
        .maxWithOrNull(Cursor)

    override fun findById(sessionId: String): ReceivedText? = rows[sessionId]

    override fun page(query: String?, limit: Int, offset: Int): List<ReceivedText> =
        matching(query)
            .sortedWith(compareByDescending<ReceivedText> { it.createdAt }.thenByDescending { it.sessionId })
            .drop(offset)
            .take(limit)

    override fun count(query: String?): Int = matching(query).size

    private fun matching(query: String?): List<ReceivedText> {
        val needle = query?.trim()?.lowercase().orEmpty()
        return rows.values.filter { needle.isEmpty() || it.text.lowercase().contains(needle) }
    }
}
