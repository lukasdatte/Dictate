package net.devemperor.dictate.windows

import net.devemperor.dictate.database.dao.TextInsertionDao
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionOrigin
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.database.entity.TextInsertionEntity
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.protocol.SyncCursor
import net.devemperor.dictate.testutil.FakeSessionDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AndroidSyncSource] — the Room→wire adapter of the lazy sync (ADR-0020).
 *
 * The one edge case that matters: two sessions born in the same millisecond must each be delivered
 * exactly once across a page boundary, which is why the cursor is `(createdAt, id)` and not
 * `createdAt` alone.
 */
class AndroidSyncSourceTest {

    private val sessionDao = FakeSessionDao()
    private val textDao = FakeTextInsertionDao()
    private val source = AndroidSyncSource(sessionDao, textDao)

    private fun seed(
        id: String,
        createdAt: Long,
        text: String? = "text-$id",
        status: SessionStatus = SessionStatus.COMPLETED,
        origin: SessionOrigin = SessionOrigin.KEYBOARD,
    ) {
        sessionDao.seed(
            SessionEntity(
                id = id,
                type = "RECORDING",
                createdAt = createdAt,
                targetAppPackage = null,
                language = null,
                audioFilePath = null,
                status = status.name,
                origin = origin.name,
                finalOutputText = text,
            )
        )
    }

    @Test
    fun `fromStart returns eligible sessions oldest-first`() {
        seed("b", createdAt = 200)
        seed("a", createdAt = 100)
        val page = source.sessionsAfter(null, limit = 10)
        assertEquals(listOf("a", "b"), page.map { it.sessionId })
        assertEquals("text-a", page[0].text)
        assertEquals(100L, page[0].createdAt)
        assertEquals(SessionOriginWire.KEYBOARD, page[0].origin)
    }

    @Test
    fun `excludes non-completed, textless and review-refinement carriers`() {
        seed("done", createdAt = 100)
        seed("failed", createdAt = 110, status = SessionStatus.FAILED)
        seed("notext", createdAt = 120, text = null)
        seed("carrier", createdAt = 130, origin = SessionOrigin.REVIEW_REFINEMENT)
        val page = source.sessionsAfter(null, limit = 10)
        assertEquals(listOf("done"), page.map { it.sessionId })
    }

    @Test
    fun `cursor with identical createdAt delivers both rows exactly once, none skipped`() {
        // Two sessions in the same millisecond — the classic total-order boundary.
        seed("s1", createdAt = 500)
        seed("s2", createdAt = 500)
        seed("s3", createdAt = 600)

        val first = source.sessionsAfter(null, limit = 2)
        assertEquals(listOf("s1", "s2"), first.map { it.sessionId })

        // Page on from the last row's watermark — s2 must NOT reappear, s3 must follow.
        val cursor = SyncCursor(lastCreatedAt = 500, lastSessionId = "s2")
        val second = source.sessionsAfter(cursor, limit = 2)
        assertEquals(listOf("s3"), second.map { it.sessionId })
    }

    @Test
    fun `dispatched flag reflects a WINDOWS_DISPATCH audit row`() {
        seed("sent", createdAt = 100)
        seed("archived", createdAt = 200)
        textDao.markDispatched("sent")

        val byId = source.sessionsAfter(null, limit = 10).associateBy { it.sessionId }
        assertTrue(byId.getValue("sent").dispatched)
        assertFalse(byId.getValue("archived").dispatched)
    }

    /** Minimal [TextInsertionDao] fake — only the two methods the sync path touches. */
    private class FakeTextInsertionDao : TextInsertionDao {
        private val dispatched = mutableSetOf<String>()
        fun markDispatched(sessionId: String) { dispatched.add(sessionId) }
        override fun insert(entity: TextInsertionEntity) {}
        override fun dispatchedSessionIds(sessionIds: List<String>): List<String> =
            sessionIds.filter { it in dispatched }
    }
}
