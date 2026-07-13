package net.devemperor.dictate.windows

import net.devemperor.dictate.database.dao.SessionDao
import net.devemperor.dictate.database.dao.TextInsertionDao
import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.sync.SyncSource
import net.devemperor.dictate.shared.protocol.SyncCursor

/**
 * The Room-backed [SyncSource] (ADR-0020) — the one place in `windows/` that knows Room.
 *
 * The phone is authoritative: this reads history forward from the server's watermark and never
 * writes. The page's `dispatched` flags come from the audit table (a `WINDOWS_DISPATCH` row) so
 * the archive sync never contradicts what `/v1/dispatch` already told the companion.
 *
 * The ordering `(createdAt, id)` is the contract, not an implementation detail — [SyncClient]
 * pages by feeding the server's new watermark back in, so a mis-ordered page would skip sessions.
 * The SQL `ORDER BY created_at ASC, id ASC` provides exactly that total order.
 */
class AndroidSyncSource(
    private val sessionDao: SessionDao,
    private val textInsertionDao: TextInsertionDao,
) : SyncSource {

    override fun sessionsAfter(cursor: SyncCursor?, limit: Int): List<SessionUpsert> {
        val rows = if (cursor == null) {
            sessionDao.sessionsFromStart(limit)
        } else {
            sessionDao.sessionsAfterCursor(cursor.lastCreatedAt, cursor.lastSessionId, limit)
        }
        if (rows.isEmpty()) return emptyList()

        val dispatched = textInsertionDao.dispatchedSessionIds(rows.map { it.id }).toSet()
        return rows.map { SessionEntityMapper.toUpsert(it, dispatched = dispatched.contains(it.id)) }
    }
}
