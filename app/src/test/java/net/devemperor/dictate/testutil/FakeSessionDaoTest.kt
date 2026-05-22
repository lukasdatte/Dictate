package net.devemperor.dictate.testutil

import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionOrigin
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.database.entity.SessionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the C9 / M4 query additions on [FakeSessionDao] — the
 * production Room queries are verified end-to-end by the instrumented
 * [net.devemperor.dictate.database.migration.MigrationTo4Test]
 * (local-only); these JVM tests pin the in-memory fake's filter / sort
 * semantics so SessionManager-tests + Module-tests in subsequent
 * chunks (notably C10 recovery/cleanup) have a trustworthy fixture.
 *
 * Each test is named after the production query it mirrors so a
 * future maintainer can grep `findPendingInsertion` and find both the
 * Room query and the in-memory fixture together.
 */
class FakeSessionDaoTest {

    private lateinit var dao: FakeSessionDao

    @Before
    fun setUp() {
        dao = FakeSessionDao()
    }

    private fun entity(
        id: String,
        status: SessionStatus,
        createdAt: Long,
        finalOutputText: String? = null,
        insertedAt: Long? = null,
        audioFilePath: String? = null
    ) = SessionEntity(
        id = id,
        type = SessionType.RECORDING.name,
        createdAt = createdAt,
        targetAppPackage = null,
        language = null,
        audioFilePath = audioFilePath,
        audioDurationSeconds = 0L,
        parentSessionId = null,
        status = status.name,
        origin = SessionOrigin.KEYBOARD.name,
        finalOutputText = finalOutputText,
        insertedAt = insertedAt
    )

    @Test
    fun `markInserted sets insertedAt and records the call`() {
        dao.seed(entity("s1", SessionStatus.COMPLETED, 1000L, finalOutputText = "x"))

        dao.markInserted("s1", 5000L)

        assertEquals(5000L, dao.getById("s1")!!.insertedAt)
        assertEquals(listOf("s1" to 5000L), dao.markInsertedCalls)
    }

    @Test
    fun `findPendingInsertion returns COMPLETED rows with output and null insertedAt newest-first`() {
        // COMPLETED + output + insertedAt NULL → eligible.
        dao.seed(entity("pending-old", SessionStatus.COMPLETED, 1000L, finalOutputText = "a"))
        dao.seed(entity("pending-new", SessionStatus.COMPLETED, 3000L, finalOutputText = "b"))
        // COMPLETED but already inserted → not eligible.
        dao.seed(entity("done", SessionStatus.COMPLETED, 2000L, finalOutputText = "c", insertedAt = 2500L))
        // COMPLETED without output → not eligible (defensive).
        dao.seed(entity("no-output", SessionStatus.COMPLETED, 4000L, finalOutputText = null))
        // Non-COMPLETED → not eligible.
        dao.seed(entity("recorded", SessionStatus.RECORDED, 5000L, finalOutputText = "d"))

        // freshnessFloor = 0 admits all rows (matches pre-F-2 semantics).
        val pending = dao.findPendingInsertion(freshnessFloor = 0L)

        assertEquals(listOf("pending-new", "pending-old"), pending.map { it.id })
    }

    @Test
    fun `findPendingInsertion filters out legacy rows below the freshness floor`() {
        // B3-VAL-W1 F-2: pre-M4 rows backfilled to inserted_at=NULL would
        // otherwise flood NotifyManualPasteNeeded on first post-upgrade
        // boot. The freshness floor on created_at gates them.
        dao.seed(entity("legacy", SessionStatus.COMPLETED, createdAt = 100L, finalOutputText = "old"))
        dao.seed(entity("recent", SessionStatus.COMPLETED, createdAt = 9_000L, finalOutputText = "new"))

        val pending = dao.findPendingInsertion(freshnessFloor = 5_000L)

        // Only the recent row passes the floor.
        assertEquals(listOf("recent"), pending.map { it.id })
    }

    @Test
    fun `deleteInsertedOlderThan removes only inserted rows older than cutoff`() {
        dao.seed(entity("old", SessionStatus.COMPLETED, 1000L, insertedAt = 1000L))
        dao.seed(entity("fresh", SessionStatus.COMPLETED, 2000L, insertedAt = 9_000L))
        dao.seed(entity("never-inserted", SessionStatus.COMPLETED, 500L, insertedAt = null))

        val removed = dao.deleteInsertedOlderThan(5000L)

        assertEquals(1, removed)
        assertNull(dao.getById("old"))
        assertEquals("fresh", dao.getById("fresh")?.id)
        assertEquals("never-inserted", dao.getById("never-inserted")?.id)
    }

    @Test
    fun `findOrphanedTerminalAudio returns FAILED and CANCELLED rows with audio older than cutoff`() {
        dao.seed(entity("failed-old", SessionStatus.FAILED, 1000L, audioFilePath = "/a/1.m4a"))
        dao.seed(entity("cancelled-old", SessionStatus.CANCELLED, 1500L, audioFilePath = "/a/2.m4a"))
        // Same status, fresher → not orphan yet.
        dao.seed(entity("failed-fresh", SessionStatus.FAILED, 9_000L, audioFilePath = "/a/3.m4a"))
        // Eligible age but no audio file.
        dao.seed(entity("failed-no-audio", SessionStatus.FAILED, 1000L, audioFilePath = null))
        // Wrong status.
        dao.seed(entity("completed-with-audio", SessionStatus.COMPLETED, 1000L, audioFilePath = "/a/4.m4a"))

        val orphans = dao.findOrphanedTerminalAudio(5000L)

        assertEquals(
            setOf("failed-old" to "/a/1.m4a", "cancelled-old" to "/a/2.m4a"),
            orphans.map { it.id to it.audioFilePath }.toSet()
        )
    }

    @Test
    fun `clearAudioFilePathBulk nulls only the supplied ids`() {
        dao.seed(entity("keep", SessionStatus.FAILED, 1000L, audioFilePath = "/a/keep.m4a"))
        dao.seed(entity("clear-a", SessionStatus.FAILED, 1000L, audioFilePath = "/a/a.m4a"))
        dao.seed(entity("clear-b", SessionStatus.CANCELLED, 1000L, audioFilePath = "/a/b.m4a"))

        dao.clearAudioFilePathBulk(listOf("clear-a", "clear-b"))

        assertEquals("/a/keep.m4a", dao.getById("keep")?.audioFilePath)
        assertNull(dao.getById("clear-a")?.audioFilePath)
        assertNull(dao.getById("clear-b")?.audioFilePath)
    }

    @Test
    fun `getSessionsByStatuses returns rows matching any of the supplied statuses`() {
        dao.seed(entity("r1", SessionStatus.RECORDING, 1000L))
        dao.seed(entity("t1", SessionStatus.TRANSCRIBING, 2000L))
        dao.seed(entity("c1", SessionStatus.COMPLETED, 3000L))
        dao.seed(entity("f1", SessionStatus.FAILED, 4000L))

        val live = dao.getSessionsByStatuses(
            listOf(SessionStatus.RECORDING.name, SessionStatus.TRANSCRIBING.name)
        )

        assertEquals(setOf("r1", "t1"), live.map { it.id }.toSet())
    }

    @Test
    fun `findAllAudioFilePaths returns only non-null paths`() {
        dao.seed(entity("with-audio", SessionStatus.COMPLETED, 1000L, audioFilePath = "/a/x.m4a"))
        dao.seed(entity("no-audio", SessionStatus.COMPLETED, 1000L, audioFilePath = null))

        val paths = dao.findAllAudioFilePaths()

        assertEquals(listOf("/a/x.m4a"), paths)
    }

    @Test
    fun `markLegacyAudioSessionsFailed updates only live-state rows pointing at the legacy path`() {
        val legacy = "/cache/audio.m4a"
        dao.seed(entity("recording", SessionStatus.RECORDING, 1000L, audioFilePath = legacy))
        dao.seed(entity("recorded", SessionStatus.RECORDED, 2000L, audioFilePath = legacy))
        dao.seed(entity("transcribing", SessionStatus.TRANSCRIBING, 3000L, audioFilePath = legacy))
        // Already terminal — preserve last_error_message (Phase-B S-7 idempotence).
        dao.seed(entity("already-failed", SessionStatus.FAILED, 4000L, audioFilePath = legacy))
        // COMPLETED with same path (extremely unlikely, but defensive).
        dao.seed(entity("already-completed", SessionStatus.COMPLETED, 5000L, audioFilePath = legacy))
        // Different path.
        dao.seed(entity("other-recording", SessionStatus.RECORDING, 6000L, audioFilePath = "/cache/other.m4a"))

        val updated = dao.markLegacyAudioSessionsFailed(legacy, "legacy_cache_recording", SessionStatus.FAILED.name)

        assertEquals(3, updated)
        listOf("recording", "recorded", "transcribing").forEach { id ->
            val row = dao.getById(id)!!
            assertEquals(SessionStatus.FAILED.name, row.status)
            assertEquals("UNKNOWN", row.lastErrorType)
            assertEquals("legacy_cache_recording", row.lastErrorMessage)
        }
        // Untouched rows — both the "already terminal" pair and the unrelated path.
        assertEquals(SessionStatus.FAILED.name, dao.getById("already-failed")!!.status)
        assertNull(dao.getById("already-failed")!!.lastErrorMessage)
        assertEquals(SessionStatus.COMPLETED.name, dao.getById("already-completed")!!.status)
        assertEquals(SessionStatus.RECORDING.name, dao.getById("other-recording")!!.status)
    }

    @Test
    fun `markLegacyAudioSessionsFailed is idempotent across reruns`() {
        val legacy = "/cache/audio.m4a"
        dao.seed(entity("live", SessionStatus.RECORDING, 1000L, audioFilePath = legacy))

        val firstRun = dao.markLegacyAudioSessionsFailed(legacy, "reason-A", SessionStatus.FAILED.name)
        val secondRun = dao.markLegacyAudioSessionsFailed(legacy, "reason-B", SessionStatus.FAILED.name)

        assertEquals(1, firstRun)
        // Second run sees status = FAILED and skips the row (WHERE filter).
        assertEquals(0, secondRun)
        // First-run reason preserved — second run did not overwrite it.
        assertEquals("reason-A", dao.getById("live")!!.lastErrorMessage)
    }

    @Test
    fun `findLatestByOrigin returns newest matching row`() {
        dao.seed(entity("k-old", SessionStatus.RECORDED, 1000L).copy(origin = SessionOrigin.KEYBOARD.name))
        dao.seed(entity("k-new", SessionStatus.RECORDED, 9000L).copy(origin = SessionOrigin.KEYBOARD.name))
        dao.seed(entity("h-fresh", SessionStatus.RECORDED, 5000L).copy(origin = SessionOrigin.HISTORY_REPROCESS.name))

        val latest = dao.findLatestByOrigin(SessionOrigin.KEYBOARD.name)

        assertEquals("k-new", latest?.id)
    }

    @Test
    fun `updateStatus mutates the row in place`() {
        dao.seed(entity("s", SessionStatus.RECORDING, 1000L))

        dao.updateStatus("s", SessionStatus.RECORDED.name)

        assertEquals(SessionStatus.RECORDED.name, dao.getById("s")!!.status)
    }

    @Test
    fun `updateAudioFilePath sets the path`() {
        dao.seed(entity("s", SessionStatus.RECORDED, 1000L))

        dao.updateAudioFilePath("s", "/files/audio/s.m4a")

        assertEquals("/files/audio/s.m4a", dao.getById("s")!!.audioFilePath)
    }

    @Test
    fun `finalizeRecordedMetadata transitions to RECORDED without clobbering audio_file_paths`() {
        // Recovery-chain SEND-path reconciliation (2026-05-22): when the
        // row was inserted at recording-start, the SEND path transitions
        // it to RECORDED + fills metadata, but MUST leave the rolling
        // audio_file_paths list intact — a multi-segment recording would
        // otherwise collapse back to its first segment.
        dao.seed(
            SessionEntity(
                id = "rec-1",
                type = SessionType.RECORDING.name,
                createdAt = 100L,
                targetAppPackage = null,
                language = null,
                audioFilePath = "/a/seg1.m4a",
                audioFilePaths = listOf("/a/seg1.m4a", "/a/seg2.m4a"),
                status = SessionStatus.RECORDING.name,
                origin = SessionOrigin.KEYBOARD.name,
            )
        )

        dao.finalizeRecordedMetadata(
            id = "rec-1",
            status = SessionStatus.RECORDED.name,
            targetApp = "com.example.app",
            language = "de",
            durationSeconds = 17L,
            queuedPromptIds = "3,7",
        )

        val row = dao.getById("rec-1")!!
        assertEquals(SessionStatus.RECORDED.name, row.status)
        assertEquals("com.example.app", row.targetAppPackage)
        assertEquals("de", row.language)
        assertEquals(17L, row.audioDurationSeconds)
        assertEquals("3,7", row.queuedPromptIds)
        // The rolling-segment list survives the RECORDED transition.
        assertEquals(listOf("/a/seg1.m4a", "/a/seg2.m4a"), row.audioFilePaths)
        assertEquals("/a/seg1.m4a", row.audioFilePath)
    }

    @Test
    fun `unknown ids are no-ops on update paths`() {
        // None of these should throw; the production Room DAO returns
        // 0 affected rows for missing IDs and the fake mirrors that.
        dao.updateStatus("ghost", SessionStatus.FAILED.name)
        dao.markInserted("ghost", 1L)
        dao.clearAudioFilePath("ghost")
        dao.clearAudioFilePathBulk(listOf("ghost-a", "ghost-b"))
        assertNull(dao.getById("ghost"))
        assertTrue(dao.markInsertedCalls.contains("ghost" to 1L))
    }
}
