package net.devemperor.dictate.shared.fakes

import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.protocol.SyncCursor
import net.devemperor.dictate.shared.sync.Cursor
import net.devemperor.dictate.shared.sync.SyncSource
import net.devemperor.dictate.shared.sync.isAfter
import net.devemperor.dictate.shared.sync.toCursor

/**
 * A deterministic local history. Hand-written, per house style — no mock library.
 *
 * It honours the [SyncSource] contract literally (strictly after the cursor, ordered by
 * `(createdAt, sessionId)`, capped at the limit), which is what makes it a fair stand-in for the
 * Room-backed implementation that lands in `:app`.
 */
class FakeSyncSource(sessions: List<SessionUpsert> = emptyList()) : SyncSource {

    private val sessions = sessions.sortedWith(compareBy({ it.createdAt }, { it.sessionId })).toMutableList()

    /** Every (cursor, limit) the client asked for — lets a test assert the paging itself. */
    val queries = mutableListOf<Pair<SyncCursor?, Int>>()

    override fun sessionsAfter(cursor: SyncCursor?, limit: Int): List<SessionUpsert> {
        queries += cursor to limit
        return sessions.filter { it.isAfter(cursor) }
            .sortedWith(compareBy({ it.createdAt }, { it.sessionId }))
            .take(limit)
    }

    fun add(session: SessionUpsert) {
        sessions += session
    }

    fun latestCursor(): SyncCursor? =
        sessions.map { it.toCursor() }.maxWithOrNull(Cursor)

    companion object {
        /** [count] sessions, one per millisecond, ids padded so the lexicographic tie-break is stable. */
        fun of(count: Int, firstCreatedAt: Long = 1_700_000_000_000L): FakeSyncSource =
            FakeSyncSource(
                (0 until count).map { index ->
                    SessionUpsert(
                        sessionId = "session-%05d".format(index),
                        text = "dictation $index",
                        createdAt = firstCreatedAt + index,
                        origin = SessionOriginWire.KEYBOARD,
                        dispatched = false,
                    )
                },
            )
    }
}
