package net.devemperor.dictate.core

import kotlinx.coroutines.runBlocking
import net.devemperor.dictate.audio.PipelineAudioResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure-JVM tests for [CacheDirAudioFileRepository] — segment naming,
 * sorting, deletion, orphan detection, and the zero-copy
 * single-segment fast path of [CacheDirAudioFileRepository.readForPipeline].
 *
 * **Out of scope here:** MediaMuxer-based multi-segment concatenation.
 * That path depends on the real Android media stack and is verified
 * in `app/src/androidTest/` (planned `CacheDirAudioFileRepositoryConcatTest`).
 * The pure-JVM tier covers everything else.
 */
class CacheDirAudioFileRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var repo: CacheDirAudioFileRepository
    private lateinit var audioDir: File

    @Before
    fun setUp() {
        repo = CacheDirAudioFileRepository(cacheDirProvider = { tmp.root })
        audioDir = File(tmp.root, "audio")
    }

    // ── allocateFirst / allocateNext ────────────────────────────────────

    @Test
    fun `allocateFirst returns seg1 for a fresh session`() {
        val f = repo.allocateFirst("abc-123")
        assertEquals("sess_abc-123_seg1.m4a", f.name)
        assertEquals(audioDir.absolutePath, f.parentFile?.absolutePath)
        // File MUST NOT be created — MediaRecorder.start() does that.
        assertTrue("allocate must not materialise the file", !f.exists())
    }

    @Test
    fun `allocateFirst creates the audio sub-directory lazily`() {
        assertTrue("pre-condition: audio dir does not exist yet", !audioDir.exists())
        repo.allocateFirst("x")
        assertTrue("audio dir must be created on first allocate", audioDir.isDirectory)
    }

    @Test
    fun `allocateNext returns seg1 when no segments exist`() {
        val f = repo.allocateNext("abc")
        assertEquals("sess_abc_seg1.m4a", f.name)
    }

    @Test
    fun `allocateNext returns seg2 when seg1 exists`() {
        audioDir.mkdirs()
        File(audioDir, "sess_abc_seg1.m4a").createNewFile()
        val f = repo.allocateNext("abc")
        assertEquals("sess_abc_seg2.m4a", f.name)
    }

    @Test
    fun `allocateNext skips over highest existing index`() {
        audioDir.mkdirs()
        File(audioDir, "sess_x_seg1.m4a").createNewFile()
        File(audioDir, "sess_x_seg3.m4a").createNewFile()
        File(audioDir, "sess_x_seg2.m4a").createNewFile()
        val f = repo.allocateNext("x")
        assertEquals("sess_x_seg4.m4a", f.name)
    }

    @Test
    fun `allocateNext ignores malformed segment names`() {
        audioDir.mkdirs()
        File(audioDir, "sess_x_seg.m4a").createNewFile()         // missing index
        File(audioDir, "sess_x_segabc.m4a").createNewFile()      // non-numeric
        val f = repo.allocateNext("x")
        // Both malformed entries are skipped when computing maxIndex,
        // so the next allocation goes to seg1.
        assertEquals("sess_x_seg1.m4a", f.name)
    }

    @Test
    fun `allocateNext is per-session`() {
        audioDir.mkdirs()
        File(audioDir, "sess_a_seg3.m4a").createNewFile()
        // Session 'b' has no segments yet — should start at 1 even
        // though session 'a' is up to seg3.
        val f = repo.allocateNext("b")
        assertEquals("sess_b_seg1.m4a", f.name)
    }

    // ── segments() ──────────────────────────────────────────────────────

    @Test
    fun `segments returns ascending sorted list`() {
        audioDir.mkdirs()
        File(audioDir, "sess_x_seg3.m4a").createNewFile()
        File(audioDir, "sess_x_seg1.m4a").createNewFile()
        File(audioDir, "sess_x_seg10.m4a").createNewFile()
        File(audioDir, "sess_x_seg2.m4a").createNewFile()
        val segs = repo.segments("x")
        assertEquals(
            listOf("sess_x_seg1.m4a", "sess_x_seg2.m4a", "sess_x_seg3.m4a", "sess_x_seg10.m4a"),
            segs.map { it.name }
        )
    }

    @Test
    fun `segments empty list when no segments`() {
        audioDir.mkdirs()
        File(audioDir, "sess_other_seg1.m4a").createNewFile()
        assertTrue(repo.segments("nonexistent").isEmpty())
    }

    @Test
    fun `segments empty list when audio dir is missing`() {
        // Cache dir not yet created — segments() must NOT throw.
        assertTrue(repo.segments("any").isEmpty())
    }

    @Test
    fun `segments isolates sessions by id prefix`() {
        audioDir.mkdirs()
        File(audioDir, "sess_x_seg1.m4a").createNewFile()
        File(audioDir, "sess_xy_seg1.m4a").createNewFile()  // longer id starting with same prefix
        val segsX = repo.segments("x")
        assertEquals(listOf("sess_x_seg1.m4a"), segsX.map { it.name })
    }

    @Test
    fun `segments filters legacy rec-prefixed files`() {
        audioDir.mkdirs()
        File(audioDir, "rec_1234567890_abcd1234.m4a").createNewFile()  // legacy factory naming
        File(audioDir, "sess_x_seg1.m4a").createNewFile()
        val segs = repo.segments("x")
        assertEquals(listOf("sess_x_seg1.m4a"), segs.map { it.name })
    }

    // ── readForPipeline() — single-segment fast path + null path ────────

    @Test
    fun `readForPipeline returns Complete with the single segment`() = runBlocking {
        audioDir.mkdirs()
        val single = File(audioDir, "sess_x_seg1.m4a").also { it.createNewFile() }
        val result = repo.readForPipeline("x")
        assertTrue("expected Complete, got $result", result is PipelineAudioResult.Complete)
        assertEquals(single, (result as PipelineAudioResult.Complete).file)
    }

    @Test
    fun `readForPipeline returns null when no segments`() = runBlocking {
        audioDir.mkdirs()
        assertNull(repo.readForPipeline("nonexistent"))
    }

    @Test
    fun `readForPipeline single-segment fast path skips track validation`() = runBlocking {
        // Empty file would be rejected by MediaExtractor — but the
        // single-segment fast path is zero-copy by design. Corrupted
        // single segments surface as Whisper 4xx errors, not as a
        // recovery decision. This locks the fast-path contract.
        audioDir.mkdirs()
        val single = File(audioDir, "sess_x_seg1.m4a").also { it.createNewFile() }
        val result = repo.readForPipeline("x")
        assertTrue(result is PipelineAudioResult.Complete)
        assertEquals(single, (result as PipelineAudioResult.Complete).file)
    }

    // ── deleteAll() ─────────────────────────────────────────────────────

    @Test
    fun `deleteAll removes every segment of the session`() {
        audioDir.mkdirs()
        File(audioDir, "sess_x_seg1.m4a").createNewFile()
        File(audioDir, "sess_x_seg2.m4a").createNewFile()
        File(audioDir, "sess_x_seg3.m4a").createNewFile()
        repo.deleteAll("x")
        assertTrue("all segments removed", repo.segments("x").isEmpty())
    }

    @Test
    fun `deleteAll removes merged transient`() {
        audioDir.mkdirs()
        File(audioDir, "sess_x_seg1.m4a").createNewFile()
        val merged = File(audioDir, "sess_x_merged.m4a").also { it.createNewFile() }
        repo.deleteAll("x")
        assertTrue("merged transient gone", !merged.exists())
    }

    @Test
    fun `deleteAll leaves other sessions untouched`() {
        audioDir.mkdirs()
        File(audioDir, "sess_x_seg1.m4a").createNewFile()
        File(audioDir, "sess_other_seg1.m4a").createNewFile()
        repo.deleteAll("x")
        assertEquals(1, repo.segments("other").size)
    }

    @Test
    fun `deleteAll on unknown session is a no-op`() {
        audioDir.mkdirs()
        File(audioDir, "sess_kept_seg1.m4a").createNewFile()
        repo.deleteAll("unknown")
        assertEquals(1, repo.segments("kept").size)
    }

    // ── listOrphanSessionIds() ──────────────────────────────────────────

    @Test
    fun `listOrphanSessionIds returns disk-only sessions`() {
        audioDir.mkdirs()
        File(audioDir, "sess_known_seg1.m4a").createNewFile()
        File(audioDir, "sess_orphan1_seg1.m4a").createNewFile()
        File(audioDir, "sess_orphan2_seg3.m4a").createNewFile()
        File(audioDir, "sess_orphan2_merged.m4a").createNewFile()
        val orphans = repo.listOrphanSessionIds(knownSessionIds = setOf("known"))
        assertEquals(setOf("orphan1", "orphan2"), orphans)
    }

    @Test
    fun `listOrphanSessionIds ignores foreign files`() {
        audioDir.mkdirs()
        File(audioDir, "stuff.txt").createNewFile()
        File(audioDir, "rec_1234567890_abcd1234.m4a").createNewFile()  // legacy factory naming
        val orphans = repo.listOrphanSessionIds(emptySet())
        assertTrue("foreign content not classified as session", orphans.isEmpty())
    }

    @Test
    fun `listOrphanSessionIds returns empty when audio dir is missing`() {
        // Cache dir not yet created — must NOT throw.
        assertTrue(repo.listOrphanSessionIds(emptySet()).isEmpty())
    }

    // ── Defensive ──────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `null cacheDir surfaces a clear IllegalArgumentException`() {
        val brokenRepo = CacheDirAudioFileRepository(cacheDirProvider = { null })
        brokenRepo.allocateFirst("any")  // forces audioCacheDir resolution
    }
}
