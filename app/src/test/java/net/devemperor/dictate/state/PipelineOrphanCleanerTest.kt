package net.devemperor.dictate.state

import kotlinx.coroutines.runBlocking
import net.devemperor.dictate.database.dao.OrphanedAudioRow
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.testutil.FakeSessionDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for [PipelineOrphanCleaner] (C10) — exercising the
 * §6.2 R.17 + §6.3.1 KG-SST-2 cleanup paths.
 *
 * Six sub-cases per the chunk scope:
 *
 *  1. Empty DB → no-op.
 *  2. `deleteInsertedOlderThan` removes COMPLETED rows older than cutoff.
 *  3. `deleteInsertedOlderThan` keeps fresh COMPLETED rows.
 *  4. KG-SST-2 orphan path deletes audio files + clears `audio_file_path`
 *     for old FAILED/CANCELLED rows.
 *  5. RECORDED + COMPLETED rows with audio files are NOT touched
 *     (orphan path filters by status).
 *  6. Fresh FAILED rows (within cutoff) are NOT touched.
 *
 * Plus:
 *
 *  - Best-effort behaviour: missing audio files counted as "deleted"
 *    successes (idempotent re-runs).
 *  - DAO failures are absorbed (logged, not propagated).
 */
class PipelineOrphanCleanerTest {

    private val dao = FakeSessionDao()
    private val fixedNow = 10_000_000L
    private val cleaner = PipelineOrphanCleaner(
        sessionDao = dao,
        nowProvider = { fixedNow },
    )

    private fun seed(
        id: String,
        status: SessionStatus,
        createdAt: Long = 0L,
        insertedAt: Long? = null,
        audioFilePath: String? = null,
    ) = dao.seed(
        SessionEntity(
            id = id,
            type = "RECORDING",
            createdAt = createdAt,
            targetAppPackage = null,
            language = null,
            audioFilePath = audioFilePath,
            status = status.name,
            insertedAt = insertedAt,
        )
    )

    // ── Case 1: empty DB ───────────────────────────────────────────

    @Test
    fun `cleanup on empty DB returns zero counts`() {
        val result = runBlocking { cleaner.cleanup(gracePeriodMs = 1000L) }
        assertEquals(
            PipelineOrphanCleaner.CleanupResult(0, 0, 0),
            result,
        )
    }

    // ── Case 2: COMPLETED + old inserted → deleted ─────────────────

    @Test
    fun `cleanup deletes COMPLETED rows whose insertedAt is older than cutoff`() {
        // cutoff = now - 1000 = 9_999_000. inserted_at = 1000 < cutoff → eligible.
        seed("c-old", SessionStatus.COMPLETED, insertedAt = 1000L)
        seed("c-fresh", SessionStatus.COMPLETED, insertedAt = fixedNow - 100)

        val result = runBlocking { cleaner.cleanup(gracePeriodMs = 1000L) }

        assertEquals(1, result.deletedCompletedRows)
        assertNull("old COMPLETED row deleted", dao.getById("c-old"))
        assertNotNull("fresh COMPLETED row kept", dao.getById("c-fresh"))
    }

    // ── Case 3: COMPLETED with NULL insertedAt is never deleted ──

    @Test
    fun `cleanup does not delete COMPLETED rows with NULL insertedAt`() {
        seed("c-pending", SessionStatus.COMPLETED, insertedAt = null)

        val result = runBlocking { cleaner.cleanup(gracePeriodMs = 1000L) }

        assertEquals(0, result.deletedCompletedRows)
        assertNotNull(dao.getById("c-pending"))
    }

    // ── Case 4: KG-SST-2 orphan path ──────────────────────────────

    @Test
    fun `cleanup deletes audio files for old FAILED rows and clears DB path`() {
        val tmp = File.createTempFile("dictate-orphan", ".m4a")
        // FAILED row, created_at = 0 (way older than cutoff at fixedNow - 1000).
        seed(
            id = "failed-1",
            status = SessionStatus.FAILED,
            createdAt = 0L,
            audioFilePath = tmp.absolutePath,
        )

        val result = runBlocking { cleaner.cleanup(gracePeriodMs = 1000L) }

        assertEquals(1, result.clearedAudioPathRows)
        assertEquals(1, result.clearedAudioPathRows)
        assertFalse("audio file removed from disk", tmp.exists())
        assertNull("audio_file_path cleared in DB", dao.getById("failed-1")!!.audioFilePath)
        // Status NOT changed — DB row stays as a history entry.
        assertEquals(SessionStatus.FAILED.name, dao.getById("failed-1")!!.status)
    }

    @Test
    fun `cleanup deletes orphan audio for CANCELLED rows too`() {
        val tmp = File.createTempFile("dictate-cancelled", ".m4a")
        seed(
            id = "cancelled-1",
            status = SessionStatus.CANCELLED,
            createdAt = 0L,
            audioFilePath = tmp.absolutePath,
        )

        val result = runBlocking { cleaner.cleanup(gracePeriodMs = 1000L) }

        assertEquals(1, result.clearedAudioPathRows)
        assertFalse(tmp.exists())
        assertNull(dao.getById("cancelled-1")!!.audioFilePath)
    }

    // ── Case 5: RECORDED + COMPLETED with audio NOT touched ───────

    @Test
    fun `cleanup does not touch RECORDED or COMPLETED audio files (only FAILED CANCELLED)`() {
        val recAudio = File.createTempFile("dictate-rec-keep", ".m4a")
        val complAudio = File.createTempFile("dictate-compl-keep", ".m4a")
        seed("recorded-keep", SessionStatus.RECORDED, createdAt = 0L,
            audioFilePath = recAudio.absolutePath)
        seed("compl-keep", SessionStatus.COMPLETED, createdAt = 0L,
            audioFilePath = complAudio.absolutePath, insertedAt = null)

        val result = runBlocking { cleaner.cleanup(gracePeriodMs = 1000L) }

        // No orphan-audio paths cleared (RECORDED/COMPLETED-without-insertedAt
        // are NOT eligible).
        assertEquals(0, result.clearedAudioPathRows)
        assertEquals(0, result.clearedAudioPathRows)
        assertTrue("RECORDED audio kept", recAudio.exists())
        assertTrue("COMPLETED audio kept", complAudio.exists())

        recAudio.delete()
        complAudio.delete()
    }

    // ── Case 6: Fresh FAILED rows are NOT touched ─────────────────

    @Test
    fun `cleanup does not delete audio for FAILED rows fresher than cutoff`() {
        val tmp = File.createTempFile("dictate-fresh-fail", ".m4a")
        // FAILED row with created_at very close to now.
        seed(
            id = "failed-fresh",
            status = SessionStatus.FAILED,
            createdAt = fixedNow - 100, // newer than cutoff (fixedNow - 1000)
            audioFilePath = tmp.absolutePath,
        )

        val result = runBlocking { cleaner.cleanup(gracePeriodMs = 1000L) }

        assertEquals(0, result.clearedAudioPathRows)
        assertEquals(0, result.clearedAudioPathRows)
        assertTrue(tmp.exists())
        assertNotNull(dao.getById("failed-fresh")!!.audioFilePath)

        tmp.delete()
    }

    // ── Idempotence + best-effort ─────────────────────────────────

    @Test
    fun `cleanup with already-missing audio file still clears DB path (idempotent)`() {
        // Seed a FAILED row pointing at a never-existed file path.
        seed(
            id = "failed-ghost",
            status = SessionStatus.FAILED,
            createdAt = 0L,
            audioFilePath = "/tmp/never-existed-${System.nanoTime()}.m4a",
        )

        val result = runBlocking { cleaner.cleanup(gracePeriodMs = 1000L) }

        // File didn't exist → counted as success (no-op delete is ok).
        assertEquals(1, result.clearedAudioPathRows)
        assertEquals(1, result.clearedAudioPathRows)
        assertNull(dao.getById("failed-ghost")!!.audioFilePath)
    }

    @Test
    fun `cleanup running twice is no-op on second pass`() {
        val tmp = File.createTempFile("dictate-twice", ".m4a")
        seed("failed-twice", SessionStatus.FAILED, createdAt = 0L,
            audioFilePath = tmp.absolutePath)

        val r1 = runBlocking { cleaner.cleanup(gracePeriodMs = 1000L) }
        assertEquals(1, r1.clearedAudioPathRows)

        val r2 = runBlocking { cleaner.cleanup(gracePeriodMs = 1000L) }
        // Second pass: audio_file_path was cleared, no orphans found.
        assertEquals(0, r2.clearedAudioPathRows)
        assertEquals(0, r2.clearedAudioPathRows)
    }

    // ── DAO failure absorption ────────────────────────────────────

    @Test
    fun `cleanup absorbs deleteInsertedOlderThan failures and still runs orphan pass`() {
        val tmp = File.createTempFile("dictate-absorb", ".m4a")
        // Seed a regular orphan that should be processed even when the
        // deleteInsertedOlderThan call throws.
        seed("failed-absorb", SessionStatus.FAILED, createdAt = 0L,
            audioFilePath = tmp.absolutePath)

        val throwingDao = object : net.devemperor.dictate.database.dao.SessionDao by dao {
            override fun deleteInsertedOlderThan(cutoff: Long): Int {
                throw RuntimeException("simulated SQL failure")
            }
        }
        val resilient = PipelineOrphanCleaner(
            sessionDao = throwingDao,
            nowProvider = { fixedNow },
        )

        val result = runBlocking { resilient.cleanup(gracePeriodMs = 1000L) }

        assertEquals(0, result.deletedCompletedRows) // delete path failed silently
        assertEquals(1, result.clearedAudioPathRows) // orphan path still ran
        assertNull(throwingDao.getById("failed-absorb")!!.audioFilePath)
    }

    @Test
    fun `cleanup absorbs findOrphanedTerminalAudio failures and returns zero orphan counts`() {
        val throwingDao = object : net.devemperor.dictate.database.dao.SessionDao by dao {
            override fun findOrphanedTerminalAudio(cutoff: Long): List<OrphanedAudioRow> {
                throw RuntimeException("simulated SQL failure")
            }
        }
        val resilient = PipelineOrphanCleaner(
            sessionDao = throwingDao,
            nowProvider = { fixedNow },
        )

        val result = runBlocking { resilient.cleanup(gracePeriodMs = 1000L) }

        assertEquals(0, result.clearedAudioPathRows)
        assertEquals(0, result.clearedAudioPathRows)
        // deleteInsertedOlderThan still ran (returns 0 since no eligible rows).
        assertEquals(0, result.deletedCompletedRows)
    }
}
