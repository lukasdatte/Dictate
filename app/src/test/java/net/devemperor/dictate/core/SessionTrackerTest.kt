package net.devemperor.dictate.core

import net.devemperor.dictate.database.dao.SessionDao
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for [SessionTracker].
 *
 * Verifies the RAM-first/DB-fallback caching strategy for
 * [SessionTracker.getLastKeyboardSession] and the cache-invalidate paths.
 */
class SessionTrackerTest {

    @Test
    fun `getLastKeyboardSession returns null when DB has no keyboard session`() {
        val dao = FakeSessionDao()
        val tracker = SessionTracker(dao)

        assertNull(tracker.getLastKeyboardSession())
        assertEquals(1, dao.findLatestCallCount)
    }

    @Test
    fun `getLastKeyboardSession hits DB on first call and caches result`() {
        val session = makeSession("id-1")
        val dao = FakeSessionDao().apply { stubbedLatest = session }
        val tracker = SessionTracker(dao)

        val first = tracker.getLastKeyboardSession()
        val second = tracker.getLastKeyboardSession()

        assertSame(session, first)
        assertSame(session, second)
        assertEquals(
            "second call should not hit DB (cache hit)",
            1, dao.findLatestCallCount
        )
    }

    @Test
    fun `invalidateLastKeyboardCache forces a re-read from DB`() {
        val session = makeSession("id-1")
        val dao = FakeSessionDao().apply { stubbedLatest = session }
        val tracker = SessionTracker(dao)

        tracker.getLastKeyboardSession()
        tracker.invalidateLastKeyboardCache()
        tracker.getLastKeyboardSession()

        assertEquals(2, dao.findLatestCallCount)
    }

    @Test
    fun `notifyKeyboardSessionCompleted seeds the cache without DB hit`() {
        val session = makeSession("id-1")
        val dao = FakeSessionDao() // no stubbed value → would return null if queried
        val tracker = SessionTracker(dao)

        tracker.notifyKeyboardSessionCompleted(session)
        val returned = tracker.getLastKeyboardSession()

        assertSame(session, returned)
        assertEquals(
            "DB should not be queried when RAM cache is populated",
            0, dao.findLatestCallCount
        )
    }

    @Test
    fun `clearCurrent resets transient tracking fields`() {
        val dao = FakeSessionDao()
        val tracker = SessionTracker(dao).apply {
            currentSessionId = "active"
            setTranscription("t1")
            setStep("s1")
        }

        tracker.clearCurrent()
        assertNull(tracker.currentSessionId)
        assertNull(tracker.currentTranscriptionId)
        assertNull(tracker.currentStepId)
    }

    // ── Helpers ──

    private fun makeSession(id: String) = SessionEntity(
        id = id,
        type = "RECORDING",
        createdAt = 0L,
        targetAppPackage = null,
        language = null,
        audioFilePath = null,
        audioDurationSeconds = 0L,
        parentSessionId = null
    )
}

/** Thin JUnit-friendly in-memory stand-in for [SessionDao]. Tracks call counts. */
private class FakeSessionDao : SessionDao {
    var stubbedLatest: SessionEntity? = null
    var findLatestCallCount: Int = 0

    override fun findLatestByOrigin(origin: String): SessionEntity? {
        findLatestCallCount++
        return if (origin == SessionOrigin.KEYBOARD.name) stubbedLatest else null
    }

    // ── Unused — throw so accidental calls fail loud ──

    override fun insert(entity: SessionEntity) = notUsed()
    override fun getById(id: String): SessionEntity? = notUsed()
    override fun updateFinalOutputText(sessionId: String, text: String?) = notUsed()
    override fun updateInputText(sessionId: String, text: String?) = notUsed()
    override fun updateAudioDuration(sessionId: String, durationSeconds: Long) = notUsed()
    override fun getAll(): List<SessionEntity> = notUsed()
    override fun getByType(type: String): List<SessionEntity> = notUsed()
    override fun search(query: String): List<SessionEntity> = notUsed()
    override fun deleteById(id: String) = notUsed()
    override fun deleteAll() = notUsed()
    override fun findWithMissingDuration(): List<SessionEntity> = notUsed()
    override fun updateStatus(id: String, status: String) = notUsed()
    override fun updateError(id: String, type: String?, message: String?) = notUsed()
    override fun updateQueuedPromptIds(id: String, ids: String?) = notUsed()
    override fun clearAudioFilePath(id: String) = notUsed()
    override fun updateAudioFilePath(id: String, path: String) = notUsed()

    private fun notUsed(): Nothing = error("FakeSessionDao: method not expected in these tests")
}
