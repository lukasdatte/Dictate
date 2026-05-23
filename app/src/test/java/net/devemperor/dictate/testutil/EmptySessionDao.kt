package net.devemperor.dictate.testutil

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import net.devemperor.dictate.database.dao.OrphanedAudioRow
import net.devemperor.dictate.database.dao.SessionDao
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.PipelineRecovery
import net.devemperor.dictate.state.PipelineSessionRepoSubsystem

/**
 * Test-only stub [SessionDao] that returns empty / no-op for every
 * method. Useful for tests that need a recovery / adapter instance
 * but don't care about the DAO calls (e.g. ordering tests that
 * verify recovery is *invoked* but not what it does).
 *
 * **Why a test-only file (B3-VAL-W1 F-13 + F-17):** the production
 * `PipelineRecovery` previously carried a `constructor(sessionRepo)`
 * convenience overload backed by an `EmptySessionDao` defined in
 * production code — a footgun. Production wiring now uses only the
 * primary constructor; the empty-DAO fixture lives here so tests stay
 * concise without inflating the production class file.
 *
 * @see net.devemperor.dictate.state.PipelineRecovery
 */
object EmptySessionDao : SessionDao {
    override fun insert(entity: SessionEntity) = Unit
    override fun getById(id: String): SessionEntity? = null
    override fun updateFinalOutputText(sessionId: String, text: String?) = Unit
    override fun updateInputText(sessionId: String, text: String?) = Unit
    override fun updateAudioDuration(sessionId: String, durationSeconds: Long) = Unit
    override fun getAll(): List<SessionEntity> = emptyList()
    override fun getByType(type: String): List<SessionEntity> = emptyList()
    override fun search(query: String): List<SessionEntity> = emptyList()
    override fun deleteById(id: String) = Unit
    override fun deleteAll() = Unit
    override fun findLatestByOrigin(origin: String): SessionEntity? = null
    override fun findLatestUnfinishedRecording(createdAtFloor: Long): SessionEntity? = null
    override fun findActiveSessionIds(): List<String> = emptyList()
    override fun findWithMissingDuration(): List<SessionEntity> = emptyList()
    override fun updateStatus(id: String, status: String) = Unit
    override fun updateError(id: String, type: String?, message: String?) = Unit
    override fun updateQueuedPromptIds(id: String, ids: String?) = Unit
    override fun clearAudioFilePath(id: String) = Unit
    override fun updateAudioFilePath(id: String, path: String) = Unit
    override fun updateAudioFilePaths(id: String, paths: String) = Unit
    override fun markInserted(id: String, timestamp: Long) = Unit
    override fun finalizeRecordedMetadata(
        id: String,
        status: String,
        targetApp: String?,
        language: String?,
        durationSeconds: Long,
        queuedPromptIds: String?,
    ) = Unit
    override fun findPendingInsertion(freshnessFloor: Long): List<SessionEntity> = emptyList()
    override fun deleteInsertedOlderThan(cutoff: Long): Int = 0
    override fun findOrphanedTerminalAudio(cutoff: Long): List<OrphanedAudioRow> = emptyList()
    override fun clearAudioFilePathBulk(ids: List<String>) = Unit
    override fun getSessionsByStatuses(statuses: List<String>): List<SessionEntity> = emptyList()
    override fun findAllAudioFilePaths(): List<String?> = emptyList()
    override fun markLegacyAudioSessionsFailed(
        legacyPath: String,
        reason: String,
        failedStatus: String
    ): Int = 0
}

/**
 * Test-only convenience factory replacing the retired
 * `PipelineRecovery(sessionRepo)` secondary constructor (B3-VAL-W1 F-13
 * + F-17). Wires the primary [PipelineRecovery] constructor with an
 * [EmptySessionDao] so tests that don't care about §6.3 status
 * promotion stay concise.
 *
 * Use the primary [PipelineRecovery] constructor directly when a test
 * needs to inject a real fake DAO (see
 * `PipelineRecoveryFullTest`).
 */
fun testPipelineRecovery(
    sessionRepo: PipelineSessionRepoSubsystem,
    emitAction: (Action) -> Unit = {},
    ioContext: CoroutineContext = EmptyCoroutineContext,
    pendingInsertionFreshnessFloor: () -> Long = { 0L },
): PipelineRecovery = PipelineRecovery(
    sessionDao = EmptySessionDao,
    sessionRepo = sessionRepo,
    emitAction = emitAction,
    ioContext = ioContext,
    pendingInsertionFreshnessFloor = pendingInsertionFreshnessFloor,
)
