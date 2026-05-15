package net.devemperor.dictate.state

import kotlinx.coroutines.runBlocking
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.testutil.FakeSessionDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the production [PipelineSessionRepoAdapter] (C10).
 *
 * Validates:
 *
 *  - `loadPending()` reads RECORDED rows with existing audio files +
 *    COMPLETED rows with pending insertion. RECORDING/TRANSCRIBING are
 *    intentionally **not** surfaced (post-recovery the §6.3 algorithm
 *    has already promoted those).
 *  - `markInserted` writes the timestamp into the DAO.
 *  - `markFailed` collapses to `updateStatus(FAILED) + updateError(UNKNOWN, reason)`.
 *  - `pendingFlow()` is `emptyFlow` per Phase-1 contract.
 *
 * @see net.devemperor.dictate.state.PipelineSessionRepoAdapter
 */
class PipelineSessionRepoAdapterTest {

    private val dao = FakeSessionDao()
    private val adapter = PipelineSessionRepoAdapter(dao)

    private fun seedSession(
        id: String,
        status: SessionStatus,
        audioFilePath: String? = null,
        finalOutputText: String? = null,
        insertedAt: Long? = null,
    ) {
        dao.seed(
            SessionEntity(
                id = id,
                type = "RECORDING",
                createdAt = 0L,
                targetAppPackage = null,
                language = null,
                audioFilePath = audioFilePath,
                status = status.name,
                finalOutputText = finalOutputText,
                insertedAt = insertedAt,
            )
        )
    }

    @Test
    fun `loadPending returns empty when DAO is empty`() {
        val result = runBlocking { adapter.loadPending() }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `loadPending returns RECORDED sessions with existing audio files`() {
        val tmp = createTempFile("dictate-test", ".m4a").also { it.deleteOnExit() }
        seedSession("recorded-1", SessionStatus.RECORDED, audioFilePath = tmp.absolutePath)
        seedSession("recorded-2", SessionStatus.RECORDED, audioFilePath = "/nonexistent/path.m4a")

        val result = runBlocking { adapter.loadPending() }

        // Only the row with an existing file surfaces.
        assertEquals(listOf("recorded-1"), result.map { it.sessionId })
        assertEquals(SessionStatus.RECORDED, result[0].status)
    }

    @Test
    fun `loadPending returns COMPLETED-with-pending-insertion rows`() {
        seedSession(
            id = "completed-pending",
            status = SessionStatus.COMPLETED,
            finalOutputText = "hello world",
            insertedAt = null,
        )
        // A completed-and-inserted row must NOT appear.
        seedSession(
            id = "completed-inserted",
            status = SessionStatus.COMPLETED,
            finalOutputText = "old text",
            insertedAt = 1000L,
        )

        val result = runBlocking { adapter.loadPending() }

        assertEquals(listOf("completed-pending"), result.map { it.sessionId })
        assertEquals("hello world", result[0].transcribedText)
    }

    @Test
    fun `loadPending combines RECORDED-with-file and COMPLETED-pending sets`() {
        val tmp = createTempFile("dictate-mix", ".m4a").also { it.deleteOnExit() }
        seedSession("r1", SessionStatus.RECORDED, audioFilePath = tmp.absolutePath)
        seedSession(
            id = "c1",
            status = SessionStatus.COMPLETED,
            finalOutputText = "text",
            insertedAt = null,
        )

        val ids = runBlocking { adapter.loadPending() }.map { it.sessionId }.toSet()
        assertEquals(setOf("r1", "c1"), ids)
    }

    @Test
    fun `loadPending excludes FAILED and CANCELLED rows even with audio files`() {
        val tmp = createTempFile("dictate-fail", ".m4a").also { it.deleteOnExit() }
        seedSession("failed-1", SessionStatus.FAILED, audioFilePath = tmp.absolutePath)
        seedSession("cancelled-1", SessionStatus.CANCELLED, audioFilePath = tmp.absolutePath)
        seedSession("recording-1", SessionStatus.RECORDING, audioFilePath = tmp.absolutePath)
        seedSession("transcribing-1", SessionStatus.TRANSCRIBING, audioFilePath = tmp.absolutePath)

        val result = runBlocking { adapter.loadPending() }
        assertTrue(
            "expected empty (no RECORDED + no COMPLETED-pending), got $result",
            result.isEmpty(),
        )
    }

    @Test
    fun `markInserted writes the timestamp into the DAO`() {
        seedSession("s1", SessionStatus.COMPLETED, finalOutputText = "x")

        runBlocking { adapter.markInserted("s1", at = 12345L) }

        val row = dao.getById("s1")
        assertNotNull(row)
        assertEquals(12345L, row!!.insertedAt)
        // markInserted call recorded for diagnostic assertions.
        assertEquals(listOf("s1" to 12345L), dao.markInsertedCalls)
    }

    @Test
    fun `markFailed sets status FAILED and writes UNKNOWN error with reason`() {
        seedSession("s1", SessionStatus.TRANSCRIBING)

        runBlocking { adapter.markFailed("s1", "network-broken") }

        val row = dao.getById("s1")
        assertNotNull(row)
        assertEquals(SessionStatus.FAILED.name, row!!.status)
        assertEquals(AIProviderException.ErrorType.UNKNOWN.name, row.lastErrorType)
        assertEquals("network-broken", row.lastErrorMessage)
    }

    @Test
    fun `pendingFlow returns an emptyFlow (Phase-1 contract)`() {
        // The Phase-1 contract is documented in the class KDoc — the flow
        // is intentionally empty. A live observer is a future-phase addition.
        // Smoke-assert that the returned Flow completes without emission.
        val collected = mutableListOf<List<PendingSession>>()
        runBlocking {
            adapter.pendingFlow().collect { collected += it }
        }
        assertTrue(collected.isEmpty())
    }

    @Test
    fun `toPendingSession boundary mapping preserves id, status, text, createdAt`() {
        val entity = SessionEntity(
            id = "abc",
            type = "RECORDING",
            createdAt = 42L,
            targetAppPackage = null,
            language = "en",
            audioFilePath = "/tmp/x",
            status = SessionStatus.COMPLETED.name,
            finalOutputText = "result",
        )

        val mapped = entity.toPendingSession()

        assertEquals("abc", mapped.sessionId)
        assertEquals(SessionStatus.COMPLETED, mapped.status)
        assertEquals("result", mapped.transcribedText)
        assertEquals(42L, mapped.createdAt)
    }

    @Test
    fun `toPendingSession falls back to RECORDED for unknown status string`() {
        val entity = SessionEntity(
            id = "x",
            type = "RECORDING",
            createdAt = 0L,
            targetAppPackage = null,
            language = null,
            audioFilePath = null,
            status = "MARS_ROVER_STATUS",
        )

        // Status string unknown to this build → fallback per SessionEntity.statusEnum.
        assertEquals(SessionStatus.RECORDED, entity.toPendingSession().status)
    }

    private fun createTempFile(prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix)

    // Unused, but document the type the test imports use.
    @Suppress("unused")
    private fun assertNullPlaceholder() = assertNull(null)
}
