package net.devemperor.dictate.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * JVM-only unit tests for [CacheDirAudioFileFactory] (Spec 1 §4.11).
 *
 * Coverage map (Plan-AC §10 Block 4 + §4.11.9):
 *
 *  - `allocate creates audio subdir if missing` — Step 1 (KG-AFF-5 lazy init).
 *  - `allocate produces rec_TS_UUID m4a name pattern` — Spec 1 §4.11.3 naming.
 *  - `allocate never returns the same path twice within same ms` — R.8 multi-job.
 *  - `allocate does NOT create the file (only the dir)` — Spec 1 §4.11.2.
 *  - `allocate throws IOException when mkdirs fails` — Failure F1.
 *  - `cleanupOrphans skips files younger than CUTOFF_GRACE_MS` — KG-AFF-4 race.
 *  - `cleanupOrphans skips files referenced in the DB-set` — Spec 1 §4.11.2.
 *  - `cleanupOrphans deletes orphans older than cutoff` — Spec 1 §4.11.3.
 *  - `cleanupOrphans ignores non-factory filenames` — Spec 1 §4.11.3 PREFIX/EXT.
 *  - `cleanupOrphans is no-op when audio dir is missing` — robust against fresh boot.
 *  - `requireNotNull fires when cacheDirProvider returns null` — KG-AFF-5.
 *  - `allocate uses sub-directory under cacheDir` — Spec 1 §4.11.3 ("Heimat-Sektion").
 *
 * K-1 (no mocking framework) + K-4 (pure JVM, no Android Context) compliant.
 *
 * @see net.devemperor.dictate.core.CacheDirAudioFileFactory
 */
class CacheDirAudioFileFactoryTest {

    private lateinit var cacheRoot: File

    @Before
    fun setUp() {
        // Per-test temp directory so the 60-second freshness cut-off and
        // referenced-paths assertions are isolated from one another.
        cacheRoot = Files.createTempDirectory("cacheDirAudioFactoryTest").toFile()
    }

    @After
    fun tearDown() {
        cacheRoot.deleteRecursively()
    }

    private fun newFactory(clock: () -> Long = { 1_000L }): CacheDirAudioFileFactory =
        CacheDirAudioFileFactory(cacheDirProvider = { cacheRoot }, clock = clock)

    private val audioSubdir: File
        get() = File(cacheRoot, CacheDirAudioFileFactory.AUDIO_SUBDIR)

    // ─── allocate() ────────────────────────────────────────────────────

    @Test
    fun `allocate creates audio subdir if missing`() {
        // Sanity: pre-condition — sub-dir does not exist yet.
        assertFalse("pre: audio subdir should NOT exist yet", audioSubdir.exists())
        val file = newFactory().allocate()
        assertTrue("audio subdir created on first allocate()", audioSubdir.exists())
        assertTrue("returned path lives under audio subdir", file.absolutePath.startsWith(audioSubdir.absolutePath))
    }

    @Test
    fun `allocate produces rec_TS_UUID m4a name pattern`() {
        val file = newFactory(clock = { 1_700_000_000_000L }).allocate()
        // Match name: rec_{ts-ms}_{uuid-hex8}.m4a — Spec 1 §4.11.3.
        val match = Regex("rec_1700000000000_[0-9a-f]{8}\\.m4a").matches(file.name)
        assertTrue("name '${file.name}' does not match rec_TS_UUID8.m4a", match)
    }

    @Test
    fun `allocate never returns the same path twice within same ms`() {
        val factory = newFactory(clock = { 1_000L })
        val a = factory.allocate()
        val b = factory.allocate()
        // Same timestamp prefix is expected; the UUID suffix must differ.
        assertNotEquals("two allocations within same ms collided on path", a, b)
    }

    @Test
    fun `allocate does NOT create the file (only the dir)`() {
        val file = newFactory().allocate()
        assertTrue("audio sub-dir should exist", audioSubdir.exists())
        assertFalse(
            "file MUST NOT exist after allocate — MediaRecorder creates it on first write",
            file.exists(),
        )
    }

    @Test
    fun `allocate throws IOException when audio cache dir path is a regular file`() {
        // Place a regular file at the would-be audio sub-dir path. The
        // factory must refuse to allocate against a non-directory rather
        // than silently returning a path with a file-parent (which would
        // confuse the MediaRecorder later).
        audioSubdir.parentFile?.mkdirs()
        audioSubdir.writeText("dummy")     // file blocks the directory
        try {
            newFactory().allocate()
            fail("allocate should throw IOException when audio cache dir location is occupied")
        } catch (e: IOException) {
            assertTrue(
                "exception message should mention the audio cache dir",
                e.message?.contains("audio") == true,
            )
        }
    }

    @Test
    fun `allocate throws IOException when audio cache dir cannot be created`() {
        // Make the parent (cacheRoot) a regular file so creating the
        // `audio/` sub-directory underneath is impossible.
        cacheRoot.deleteRecursively()
        cacheRoot.parentFile?.mkdirs()
        cacheRoot.writeText("blocking-file")     // file where the cacheRoot dir should be
        try {
            newFactory().allocate()
            fail("allocate should throw IOException when mkdirs fails")
        } catch (e: IOException) {
            assertTrue(
                "exception message should mention the audio cache dir",
                e.message?.contains("audio") == true,
            )
        }
    }

    // ─── cleanupOrphans() ──────────────────────────────────────────────

    @Test
    fun `cleanupOrphans skips files younger than CUTOFF_GRACE_MS (KG-AFF-4)`() {
        val factory = newFactory(clock = { 1_000_000L })
        audioSubdir.mkdirs()

        val fresh = File(audioSubdir, "rec_999_aaaaaaaa.m4a").apply {
            writeText("x")
            setLastModified(999_999L)       // 1 ms ago — within 60 s cut-off
        }
        val ancient = File(audioSubdir, "rec_100_bbbbbbbb.m4a").apply {
            writeText("y")
            setLastModified(100L)           // 999 s ago — past cut-off
        }

        factory.cleanupOrphans(referencedPaths = emptySet())

        assertTrue("fresh file (within cutoff) MUST be kept", fresh.exists())
        assertFalse("ancient file (past cutoff) MUST be deleted", ancient.exists())
    }

    @Test
    fun `cleanupOrphans skips files referenced in the DB-set`() {
        val factory = newFactory(clock = { 10_000_000L })
        audioSubdir.mkdirs()

        val referenced = File(audioSubdir, "rec_50_referenced.m4a").apply {
            writeText("r")
            setLastModified(50L)            // older than cut-off
        }
        val orphan = File(audioSubdir, "rec_60_orphan00.m4a").apply {
            writeText("o")
            setLastModified(60L)            // older than cut-off
        }

        factory.cleanupOrphans(referencedPaths = setOf(referenced.absolutePath))

        assertTrue("referenced file MUST be kept", referenced.exists())
        assertFalse("non-referenced (orphan) file MUST be deleted", orphan.exists())
    }

    @Test
    fun `cleanupOrphans ignores non-factory filenames`() {
        val factory = newFactory(clock = { 10_000_000L })
        audioSubdir.mkdirs()

        // Foreign file in the audio subdir — must not be touched even if
        // older than the cut-off, because its name does not match the
        // factory's PREFIX/EXT scheme.
        val foreignTxt = File(audioSubdir, "notes.txt").apply {
            writeText("user-notes")
            setLastModified(50L)
        }
        val foreignMp3 = File(audioSubdir, "rec_50_xxxxxxxx.mp3").apply {
            writeText("not-m4a")
            setLastModified(50L)
        }
        val factoryOrphan = File(audioSubdir, "rec_60_orphan00.m4a").apply {
            writeText("o")
            setLastModified(60L)
        }

        factory.cleanupOrphans(referencedPaths = emptySet())

        assertTrue("non-matching .txt MUST be kept", foreignTxt.exists())
        assertTrue("non-matching extension MUST be kept", foreignMp3.exists())
        assertFalse("factory-shaped orphan MUST be deleted", factoryOrphan.exists())
    }

    @Test
    fun `cleanupOrphans deletes orphans older than cutoff`() {
        val factory = newFactory(clock = { 5_000_000L })
        audioSubdir.mkdirs()

        // Three orphans, all older than cut-off, none referenced.
        val orphans = (1..3).map { i ->
            File(audioSubdir, "rec_${i}_orphan0$i.m4a").apply {
                writeText("orphan-$i")
                setLastModified(i * 100L)
            }
        }

        factory.cleanupOrphans(referencedPaths = emptySet())

        orphans.forEach {
            assertFalse("orphan ${it.name} MUST be deleted", it.exists())
        }
    }

    @Test
    fun `cleanupOrphans is no-op when audio dir is missing`() {
        // Sub-dir was never created — first service boot, never used.
        assertFalse(audioSubdir.exists())
        // Should not throw — the listFiles() returns null and the method
        // returns gracefully.
        newFactory().cleanupOrphans(referencedPaths = emptySet())
    }

    // ─── KG-AFF-5: requireNotNull behaviour ────────────────────────────

    @Test
    fun `requireNotNull fires when cacheDirProvider returns null`() {
        val factory = CacheDirAudioFileFactory(cacheDirProvider = { null })
        try {
            factory.allocate()
            fail("allocate should throw IllegalArgumentException when cacheDirProvider returns null")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should mention cacheDir + Application.onCreate",
                e.message?.contains("cacheDir") == true,
            )
        }
    }

    // ─── Sub-directory placement ───────────────────────────────────────

    @Test
    fun `allocate uses sub-directory under cacheDir not the root`() {
        val file = newFactory().allocate()
        assertEquals(
            "audio files must live in cacheDir/audio/, not the cache root",
            audioSubdir.absolutePath,
            file.parentFile?.absolutePath,
        )
    }
}
