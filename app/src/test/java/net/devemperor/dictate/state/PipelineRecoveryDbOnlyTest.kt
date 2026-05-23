package net.devemperor.dictate.state

import kotlinx.coroutines.runBlocking
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.testutil.EmptySessionDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Tests for [PipelineRecovery.recoverDbOnly] — the boot-time
 * DB-cleanup pass that runs without a [DictateUiStateStore] or a
 * real [PipelineSessionRepoSubsystem]. The receiver
 * [net.devemperor.dictate.core.BootCompletedReceiver] uses it after
 * ACTION_BOOT_COMPLETED so the next IME-bind starts on a clean
 * RECORDING/TRANSCRIBING set (B1.4).
 *
 * Verifies:
 *   - RECORDING rows are promoted to FAILED with the right error
 *     marker and audio_file_path is cleared (matches the documented
 *     §6.3 algorithm).
 *   - Re-running `recoverDbOnly` is a no-op (idempotency vs. the
 *     full `recover()` pass that runs later from the IME-bind).
 */
class PipelineRecoveryDbOnlyTest {

    /**
     * In-memory DAO that captures every state mutation so the test
     * can assert on the §6.3 promotion outcomes.
     */
    private class RecordingDao(initial: List<SessionEntity>) :
        net.devemperor.dictate.database.dao.SessionDao by EmptySessionDao {

        var rows = initial.toMutableList()

        override fun getSessionsByStatuses(statuses: List<String>): List<SessionEntity> =
            rows.filter { it.status in statuses }

        override fun updateStatus(id: String, status: String) {
            rows.replaceAll { if (it.id == id) it.copy(status = status) else it }
        }

        override fun updateError(id: String, type: String?, message: String?) {
            rows.replaceAll {
                if (it.id == id) it.copy(lastErrorType = type, lastErrorMessage = message) else it
            }
        }

        override fun clearAudioFilePath(id: String) {
            rows.replaceAll {
                if (it.id == id) it.copy(audioFilePath = null, audioFilePaths = emptyList()) else it
            }
        }
    }

    private fun row(
        id: String,
        status: SessionStatus,
        audioPath: String? = null,
    ): SessionEntity = SessionEntity(
        id = id,
        type = "RECORDING",
        createdAt = 0L,
        targetAppPackage = null,
        language = null,
        audioFilePath = audioPath,
        audioFilePaths = audioPath?.let { listOf(it) } ?: emptyList(),
        audioDurationSeconds = 0L,
        parentSessionId = null,
        status = status.name,
        origin = "KEYBOARD",
        queuedPromptIds = null,
        lastErrorType = null,
        lastErrorMessage = null,
        finalOutputText = null,
        inputText = null,
        insertedAt = null,
    )

    @Test
    fun `recoverDbOnly promotes RECORDING rows to FAILED`() {
        val dao = RecordingDao(listOf(row("rec-1", SessionStatus.RECORDING)))
        val recovery = PipelineRecovery(
            sessionDao = dao,
            // sessionRepo + emitAction left at defaults — recoverDbOnly
            // does not touch them.
            ioContext = EmptyCoroutineContext,
        )

        runBlocking { recovery.recoverDbOnly() }

        val promoted = dao.rows.single { it.id == "rec-1" }
        assertEquals(SessionStatus.FAILED.name, promoted.status)
        assertEquals(
            "recording-interrupted-by-process-death",
            promoted.lastErrorMessage,
        )
        assertNull("audio_file_path must be cleared after promotion", promoted.audioFilePath)
    }

    @Test
    fun `recoverDbOnly twice is a no-op on the second pass`() {
        val dao = RecordingDao(listOf(row("rec-1", SessionStatus.RECORDING)))
        val recovery = PipelineRecovery(
            sessionDao = dao,
            ioContext = EmptyCoroutineContext,
        )

        runBlocking { recovery.recoverDbOnly() }
        val afterFirst = dao.rows.toList()

        runBlocking { recovery.recoverDbOnly() }
        val afterSecond = dao.rows.toList()

        assertEquals(
            "Second recoverDbOnly pass must not mutate rows",
            afterFirst, afterSecond,
        )
    }

    @Test
    fun `recoverDbOnly leaves COMPLETED rows untouched`() {
        val completed = row("done-1", SessionStatus.COMPLETED)
        val dao = RecordingDao(listOf(completed))
        val recovery = PipelineRecovery(
            sessionDao = dao,
            ioContext = EmptyCoroutineContext,
        )

        runBlocking { recovery.recoverDbOnly() }

        assertEquals(completed, dao.rows.single())
    }
}
