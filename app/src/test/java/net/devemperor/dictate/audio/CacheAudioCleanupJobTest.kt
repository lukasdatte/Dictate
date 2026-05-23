package net.devemperor.dictate.audio

import net.devemperor.dictate.testutil.EmptySessionDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for the four-phase cleanup algorithm in
 * [CacheAudioCleanupJob.run] (recording-stack-completion §4.5.2).
 *
 * Style mirrors [net.devemperor.dictate.state.PipelineOrphanCleanerTest]:
 * pure JVM, hand-rolled fakes for `AudioFileRepository` + `SessionDao`,
 * no Robolectric. Files are real (via [TemporaryFolder]) so the
 * algorithm's `lastModified()` + `delete()` paths run end-to-end.
 */
class CacheAudioCleanupJobTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val now = 1_000_000_000L
    private val ttl = 10_000L

    /** Hand-rolled repo that exposes [files] as `listAllOwnedFiles`. */
    private class StubRepo(
        val groups: Map<String, List<File>>,
    ) : AudioFileRepository {
        override fun allocateFirst(sessionId: String): File =
            error("not exercised by cleanup tests")
        override fun allocateNext(sessionId: String): File =
            error("not exercised by cleanup tests")
        override fun segments(sessionId: String): List<File> = emptyList()
        override suspend fun readForPipeline(sessionId: String): PipelineAudioResult? = null
        override fun deleteAll(sessionId: String) = Unit
        override fun listOrphanSessionIds(knownSessionIds: Set<String>): Set<String> =
            emptySet()
        override fun listAllOwnedFiles(): Map<String, List<File>> = groups
    }

    /** Stub DAO exposing a fixed alive-set. */
    private class AliveDao(private val alive: List<String>) :
        net.devemperor.dictate.database.dao.SessionDao by EmptySessionDao {
        override fun findActiveSessionIds(): List<String> = alive
    }

    private fun freshFile(name: String, mtime: Long): File {
        val f = tmp.newFile(name)
        f.writeText("data")
        f.setLastModified(mtime)
        return f
    }

    @Test
    fun `alive-session files are never deleted regardless of age`() {
        // mtime far below the cutoff — would normally be deleted, but
        // the session is alive.
        val seg = freshFile("alive-seg.m4a", mtime = now - ttl - 1_000_000L)
        val repo = StubRepo(mapOf("alive-sid" to listOf(seg)))
        val dao = AliveDao(alive = listOf("alive-sid"))

        val result = CacheAudioCleanupJob.run(repo, dao, nowMs = now, ttlMs = ttl)

        assertTrue("alive-session file should survive", seg.exists())
        assertEquals(1, result.scanned)
        assertEquals(0, result.deleted)
        assertEquals(1, result.kept)
    }

    @Test
    fun `terminal-session files older than the cutoff are deleted`() {
        val old = freshFile("old-seg.m4a", mtime = now - ttl - 1L)
        val repo = StubRepo(mapOf("old-sid" to listOf(old)))
        val dao = AliveDao(alive = emptyList())

        val result = CacheAudioCleanupJob.run(repo, dao, nowMs = now, ttlMs = ttl)

        assertFalse("old terminal-session file should be deleted", old.exists())
        assertEquals(1, result.scanned)
        assertEquals(1, result.deleted)
        assertEquals(0, result.kept)
    }

    @Test
    fun `terminal-session files younger than the cutoff are kept`() {
        // mtime just inside the cutoff window.
        val young = freshFile("young-seg.m4a", mtime = now - ttl + 1_000L)
        val repo = StubRepo(mapOf("young-sid" to listOf(young)))
        val dao = AliveDao(alive = emptyList())

        val result = CacheAudioCleanupJob.run(repo, dao, nowMs = now, ttlMs = ttl)

        assertTrue("young file should survive — reprocess-window still open", young.exists())
        assertEquals(1, result.scanned)
        assertEquals(0, result.deleted)
        assertEquals(1, result.kept)
    }

    @Test
    fun `mixed alive plus stale plus young — only stale gets deleted`() {
        val staleSeg = freshFile("stale.m4a", mtime = now - ttl - 1L)
        val youngSeg = freshFile("young.m4a", mtime = now - ttl + 1L)
        val aliveSeg = freshFile("alive.m4a", mtime = now - ttl - 1_000_000L)
        val repo = StubRepo(
            mapOf(
                "stale-sid" to listOf(staleSeg),
                "young-sid" to listOf(youngSeg),
                "alive-sid" to listOf(aliveSeg),
            ),
        )
        val dao = AliveDao(alive = listOf("alive-sid"))

        val result = CacheAudioCleanupJob.run(repo, dao, nowMs = now, ttlMs = ttl)

        assertFalse(staleSeg.exists())
        assertTrue(youngSeg.exists())
        assertTrue(aliveSeg.exists())
        assertEquals(3, result.scanned)
        assertEquals(1, result.deleted)
        assertEquals(2, result.kept)
    }

    @Test
    fun `empty disk produces zero counters and no exception`() {
        val repo = StubRepo(emptyMap())
        val dao = AliveDao(alive = emptyList())

        val result = CacheAudioCleanupJob.run(repo, dao, nowMs = now, ttlMs = ttl)

        assertEquals(0, result.scanned)
        assertEquals(0, result.deleted)
        assertEquals(0, result.kept)
    }

    @Test
    fun `findActiveSessionIds failure bails — no files are touched`() {
        val seg = freshFile("would-delete.m4a", mtime = now - ttl - 1L)
        val repo = StubRepo(mapOf("sid" to listOf(seg)))
        val dao = object : net.devemperor.dictate.database.dao.SessionDao by EmptySessionDao {
            override fun findActiveSessionIds(): List<String> =
                throw RuntimeException("simulated DAO failure")
        }

        val result = CacheAudioCleanupJob.run(repo, dao, nowMs = now, ttlMs = ttl)

        assertTrue("file must NOT be deleted when alive-set unknown", seg.exists())
        assertEquals(1, result.scanned)
        assertEquals(0, result.deleted)
        assertEquals(1, result.kept)
    }

    @Test
    fun `listAllOwnedFiles failure bails — zero counters`() {
        val repo = object : AudioFileRepository by StubRepo(emptyMap()) {
            override fun listAllOwnedFiles(): Map<String, List<File>> =
                throw RuntimeException("simulated repo failure")
        }
        val dao = AliveDao(alive = emptyList())

        val result = CacheAudioCleanupJob.run(repo, dao, nowMs = now, ttlMs = ttl)

        assertEquals(0, result.scanned)
        assertEquals(0, result.deleted)
        assertEquals(0, result.kept)
    }
}
