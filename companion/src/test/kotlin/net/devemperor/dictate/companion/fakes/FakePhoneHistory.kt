package net.devemperor.dictate.companion.fakes

import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.protocol.SyncCursor
import net.devemperor.dictate.shared.sync.Cursor
import net.devemperor.dictate.shared.sync.SyncSource
import net.devemperor.dictate.shared.sync.isAfter

/**
 * The **phone's** Room history, standing in for `AndroidSyncSource` — the far side of the sync.
 *
 * It honours the [SyncSource] contract literally (strictly after the cursor, ordered by
 * `(createdAt, sessionId)`, capped at the limit), because that ordering is the contract the server's
 * cursor is paired with: a source that returned rows out of order would make the sync skip sessions,
 * and the E2E would happily prove a broken server correct.
 *
 * `:shared` has a near-identical fake in its own test sources. Duplicating ~20 lines here beats
 * exporting a test-fixtures configuration from `:shared` just so one module can borrow a fake — and
 * the duplicate is *useful*: this one plays the phone against a real server, which is a different
 * job from the one `:shared`'s plays against a fake transport.
 */
class FakePhoneHistory(sessions: List<SessionUpsert> = emptyList()) : SyncSource {

    private val sessions = sessions.sortedWith(compareBy({ it.createdAt }, { it.sessionId })).toMutableList()

    /** Every (cursor, limit) the client asked for — lets a test assert the paging itself. */
    val queries = mutableListOf<Pair<SyncCursor?, Int>>()

    override fun sessionsAfter(cursor: SyncCursor?, limit: Int): List<SessionUpsert> {
        queries += cursor to limit
        return sessions.filter { it.isAfter(cursor) }
            .sortedWith(compareBy({ it.createdAt }, { it.sessionId }))
            .take(limit)
    }

    fun latestCursor(): SyncCursor? = sessions.map { SyncCursor(it.createdAt, it.sessionId) }.maxWithOrNull(Cursor)

    companion object {
        /** [count] sessions, one per millisecond; ids zero-padded so the lexicographic tie-break is stable. */
        fun of(count: Int, dispatched: Boolean = false): FakePhoneHistory = FakePhoneHistory(
            (0 until count).map { index ->
                SessionUpsert(
                    sessionId = "session-%05d".format(index),
                    text = "dictation $index",
                    createdAt = FIRST_CREATED_AT + index,
                    origin = SessionOriginWire.KEYBOARD,
                    dispatched = dispatched,
                )
            },
        )

        const val FIRST_CREATED_AT = 1_700_000_000_000L
    }
}
