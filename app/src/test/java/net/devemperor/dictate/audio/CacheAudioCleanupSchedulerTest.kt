package net.devemperor.dictate.audio

import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.preferences.put
import net.devemperor.dictate.testutil.EmptySessionDao
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * Tests for [CacheAudioCleanupScheduler]'s gating + bookkeeping.
 *
 * Job-internal behaviour is covered by [CacheAudioCleanupJobTest];
 * this suite only verifies the scheduler wrapper:
 *
 *  - first call with no `CacheCleanupLastRunMs` triggers the job
 *  - second call within the interval is short-circuited (`lastRun`
 *    unchanged from the first)
 *  - second call past the interval triggers again (`lastRun`
 *    advances)
 *
 * `resetForTest()` is called in [After] so an in-flight executor
 * thread cannot leak between tests.
 */
class CacheAudioCleanupSchedulerTest {

    private val prefs = FakeSharedPreferences()

    private val repo = object : AudioFileRepository {
        override fun allocateFirst(sessionId: String): File =
            error("not exercised")
        override fun allocateNext(sessionId: String): File =
            error("not exercised")
        override fun segments(sessionId: String): List<File> = emptyList()
        override suspend fun readForPipeline(sessionId: String): PipelineAudioResult? = null
        override fun deleteAll(sessionId: String) = Unit
        override fun listOrphanSessionIds(knownSessionIds: Set<String>): Set<String> =
            emptySet()
        override fun listAllOwnedFiles(): Map<String, List<File>> = emptyMap()
    }
    private val dao = EmptySessionDao

    @After
    fun tearDown() {
        // Drain any in-flight executor thread before the next test
        // starts (analog to DurationHealingScheduler.resetForTest).
        CacheAudioCleanupScheduler.resetForTest()
    }

    @Test
    fun `first call with lastRun=0 triggers the job and writes the timestamp`() {
        val now = 1_000_000_000L

        CacheAudioCleanupScheduler.scheduleIfDue(prefs, repo, dao, nowMs = now)

        // The job runs on a single-thread executor; drain to ensure the
        // post-run prefs.put has hit the FakeSharedPreferences.
        CacheAudioCleanupScheduler.resetForTest()

        assertEquals(now, prefs.get(Pref.CacheCleanupLastRunMs))
    }

    @Test
    fun `second call within interval is short-circuited`() {
        val firstRun = 1_000_000_000L
        val interval = prefs.get(Pref.CacheCleanupIntervalMs)
        // Seed lastRun so the gate is closed.
        prefs.edit().put(Pref.CacheCleanupLastRunMs, firstRun).apply()
        val midInterval = firstRun + interval / 2

        CacheAudioCleanupScheduler.scheduleIfDue(prefs, repo, dao, nowMs = midInterval)
        CacheAudioCleanupScheduler.resetForTest()

        assertEquals(
            "lastRun should NOT advance — gate was closed",
            firstRun,
            prefs.get(Pref.CacheCleanupLastRunMs),
        )
    }

    @Test
    fun `second call past interval re-triggers and advances lastRun`() {
        val firstRun = 1_000_000_000L
        val interval = prefs.get(Pref.CacheCleanupIntervalMs)
        prefs.edit().put(Pref.CacheCleanupLastRunMs, firstRun).apply()
        val pastInterval = firstRun + interval + 1L

        CacheAudioCleanupScheduler.scheduleIfDue(prefs, repo, dao, nowMs = pastInterval)
        CacheAudioCleanupScheduler.resetForTest()

        val newLastRun = prefs.get(Pref.CacheCleanupLastRunMs)
        assertEquals(pastInterval, newLastRun)
        assertNotEquals(firstRun, newLastRun)
    }
}
