package net.devemperor.dictate.shared.sync

import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.protocol.SyncCursor

/**
 * The ordering behind the sync watermark (ADR-0020).
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015).
 *
 * The order is `(createdAt, sessionId)`, lexicographically, and it has to be **total**: paging
 * over a merely partial order silently skips or repeats rows at a page boundary. `createdAt`
 * alone is not total — two sessions can be born in the same millisecond — so the unique session
 * id breaks the tie. Both sides derive their comparisons from here, so a page boundary means the
 * same thing on the phone and on the PC.
 */
object Cursor : Comparator<SyncCursor> {

    override fun compare(a: SyncCursor, b: SyncCursor): Int {
        val byTime = a.lastCreatedAt.compareTo(b.lastCreatedAt)
        return if (byTime != 0) byTime else a.lastSessionId.compareTo(b.lastSessionId)
    }
}

/** The watermark this session would leave behind once the server has it. */
fun SessionUpsert.toCursor(): SyncCursor = SyncCursor(lastCreatedAt = createdAt, lastSessionId = sessionId)

/** True if this session lies strictly after [cursor] — i.e. the server does not have it yet. */
fun SessionUpsert.isAfter(cursor: SyncCursor?): Boolean =
    cursor == null || Cursor.compare(toCursor(), cursor) > 0

/** True if this watermark lies strictly after [other]. A null [other] means "the server has nothing". */
fun SyncCursor.isAfter(other: SyncCursor?): Boolean =
    other == null || Cursor.compare(this, other) > 0
