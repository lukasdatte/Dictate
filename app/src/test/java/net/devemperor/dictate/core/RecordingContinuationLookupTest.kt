package net.devemperor.dictate.core

import net.devemperor.dictate.audio.AudioFileRepository
import net.devemperor.dictate.audio.PipelineAudioResult
import net.devemperor.dictate.database.dao.OrphanedAudioRow
import net.devemperor.dictate.database.dao.SessionDao
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Unit tests for [RecordingContinuationLookup] — the production
 * composite that the resolver + Java IME consume on every Record-tap
 * (B2 / ADR-0008 §"Auto-Continuation").
 *
 * Test matrix (each row covered by one [@Test]):
 *  1. SessionTracker returns no candidate → lookup() = null
 *  2. Candidate exists, but no segments on disk → null
 *  3. Candidate + segments, but last segment unreadable → null
 *     (AudioCodecReader yields null for non-existent files, so we
 *     use a fake non-existent path to exercise this branch)
 *  4. Freshness window is threaded through (lookup pulls
 *     freshness from supplier, SessionTracker queries DAO with
 *     floor = now - freshness)
 */
class RecordingContinuationLookupTest {

    @Test
    fun `null when no candidate row exists`() {
        val dao = StubSessionDao(candidate = null)
        val tracker = SessionTracker(dao)
        val repo = StubAudioFileRepository()
        val lookup = RecordingContinuationLookup(
            sessionTracker = tracker,
            audioFileRepository = repo,
            freshnessMsSupplier = { 86_400_000L },
            nowMs = { 10_000_000L },
        )
        assertNull(lookup.lookup())
        assertEquals(
            "No allocateNext when there is no candidate at all",
            0, repo.allocateNextCallCount,
        )
    }

    @Test
    fun `null when candidate has no segments on disk`() {
        val dao = StubSessionDao(candidate = makeRow("sid-1"))
        val tracker = SessionTracker(dao)
        val repo = StubAudioFileRepository(
            stubbedSegments = mapOf("sid-1" to emptyList()),
        )
        val lookup = RecordingContinuationLookup(
            sessionTracker = tracker,
            audioFileRepository = repo,
            freshnessMsSupplier = { 86_400_000L },
        )
        assertNull(lookup.lookup())
        assertEquals(
            "Empty segment list is not continuable - no allocateNext",
            0, repo.allocateNextCallCount,
        )
    }

    @Test
    fun `null when last segment file is unreadable (codec params null)`() {
        // Path that doesn't exist on disk - AudioCodecReader returns
        // null, lookup must abort BEFORE invoking allocateNext (don't
        // burn a segment id on a session whose format can't be
        // matched).
        val nonExistentSeg = File("/tmp/non-existent-segment.m4a")
        val dao = StubSessionDao(candidate = makeRow("sid-2"))
        val tracker = SessionTracker(dao)
        val repo = StubAudioFileRepository(
            stubbedSegments = mapOf("sid-2" to listOf(nonExistentSeg)),
            stubbedNextFile = mapOf("sid-2" to File("/tmp/next.m4a")),
        )
        val lookup = RecordingContinuationLookup(
            sessionTracker = tracker,
            audioFileRepository = repo,
            freshnessMsSupplier = { 86_400_000L },
        )
        assertNull(lookup.lookup())
        assertEquals(
            "Unreadable codec params must abort BEFORE allocateNext",
            0, repo.allocateNextCallCount,
        )
    }

    @Test
    fun `freshness floor is threaded through to SessionDao`() {
        // freshness=1000ms, now=10_000 -> dao queried with floor=9_000.
        val dao = StubSessionDao(candidate = null)
        val tracker = SessionTracker(dao)
        val repo = StubAudioFileRepository()
        val lookup = RecordingContinuationLookup(
            sessionTracker = tracker,
            audioFileRepository = repo,
            freshnessMsSupplier = { 1_000L },
            nowMs = { 10_000L },
        )
        lookup.lookup()
        assertEquals(
            "freshness floor = now - freshnessMs",
            9_000L,
            dao.lastInterruptedFloor,
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun makeRow(id: String): SessionEntity = SessionEntity(
        id = id,
        type = "RECORDING",
        createdAt = 0L,
        targetAppPackage = null,
        language = null,
        audioFilePath = "/tmp/sess_${id}_seg1.m4a",
        audioDurationSeconds = 0L,
        parentSessionId = null,
    )

    /**
     * Hand-rolled SessionDao that returns [candidate] from
     * findLatestRecordingInterrupted and records the floor it was
     * queried with. All other methods throw - the lookup must not
     * touch them.
     */
    private class StubSessionDao(
        private val candidate: SessionEntity?,
    ) : SessionDao {
        var lastInterruptedFloor: Long = Long.MIN_VALUE
            private set

        override fun findLatestRecordingInterrupted(createdAtFloor: Long): SessionEntity? {
            lastInterruptedFloor = createdAtFloor
            return candidate
        }

        // ── Methods the lookup does not exercise — throw to fail-loud ──

        override fun insert(entity: SessionEntity) = unused()
        override fun getById(id: String): SessionEntity? = unused()
        override fun updateFinalOutputText(sessionId: String, text: String?) = unused()
        override fun updateInputText(sessionId: String, text: String?) = unused()
        override fun updateAudioDuration(sessionId: String, durationSeconds: Long) = unused()
        override fun getAll(): List<SessionEntity> = unused()
        override fun getByType(type: String): List<SessionEntity> = unused()
        override fun search(query: String): List<SessionEntity> = unused()
        override fun deleteById(id: String) = unused()
        override fun deleteAll() = unused()
        override fun findLatestByOrigin(origin: String): SessionEntity? = null
        override fun findWithMissingDuration(): List<SessionEntity> = unused()
        override fun updateStatus(id: String, status: String) = unused()
        override fun updateError(id: String, type: String?, message: String?) = unused()
        override fun updateQueuedPromptIds(id: String, ids: String?) = unused()
        override fun clearAudioFilePath(id: String) = unused()
        override fun updateAudioFilePath(id: String, path: String) = unused()
        override fun updateAudioFilePaths(id: String, paths: String) = unused()
        override fun markInserted(id: String, timestamp: Long) = unused()
        override fun findPendingInsertion(freshnessFloor: Long): List<SessionEntity> = unused()
        override fun deleteInsertedOlderThan(cutoff: Long): Int = unused()
        override fun findOrphanedTerminalAudio(cutoff: Long): List<OrphanedAudioRow> = unused()
        override fun clearAudioFilePathBulk(ids: List<String>) = unused()
        override fun getSessionsByStatuses(statuses: List<String>): List<SessionEntity> = unused()
        override fun findAllAudioFilePaths(): List<String?> = unused()
        override fun markLegacyAudioSessionsFailed(
            legacyPath: String,
            reason: String,
            failedStatus: String,
        ): Int = unused()

        private fun unused(): Nothing =
            error("StubSessionDao method not expected in ContinuationLookup tests")
    }

    /** Hand-rolled AudioFileRepository — only `segments` + `allocateNext` are exercised. */
    private class StubAudioFileRepository(
        private val stubbedSegments: Map<String, List<File>> = emptyMap(),
        private val stubbedNextFile: Map<String, File> = emptyMap(),
    ) : AudioFileRepository {
        var allocateNextCallCount: Int = 0
            private set

        override fun allocateFirst(sessionId: String): File =
            error("allocateFirst not exercised by lookup")

        override fun allocateNext(sessionId: String): File {
            allocateNextCallCount++
            return stubbedNextFile[sessionId]
                ?: error("No stubbed next-file for $sessionId")
        }

        override fun segments(sessionId: String): List<File> =
            stubbedSegments[sessionId].orEmpty()

        override suspend fun readForPipeline(sessionId: String): PipelineAudioResult? =
            error("readForPipeline not exercised by lookup")

        override fun deleteAll(sessionId: String) =
            error("deleteAll not exercised by lookup")

        override fun listOrphanSessionIds(knownSessionIds: Set<String>): Set<String> =
            error("listOrphanSessionIds not exercised by lookup")
    }
}
