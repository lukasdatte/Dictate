package net.devemperor.dictate.testutil

import net.devemperor.dictate.database.dao.OrphanedAudioRow
import net.devemperor.dictate.database.dao.SessionDao
import net.devemperor.dictate.database.entity.SessionEntity

/**
 * In-memory [SessionDao] for JVM unit tests (Spec 1 §11.7.3) — backs
 * `SessionManager` tests and any module-level test that needs to drive
 * persistence through a deterministic backing store without spinning
 * up Room.
 *
 * Backed by a single `LinkedHashMap` so insertion order is preserved
 * (the production Room implementation orders queries explicitly via
 * `ORDER BY`, so the in-memory fake's ordering happens to match the
 * common `created_at DESC` shape after the test setup mirrors that
 * order — production queries with custom ORDER BY still need
 * test-side ordering of inputs).
 *
 * Not thread-safe by design — tests drive it from the main thread.
 */
class FakeSessionDao : SessionDao {

    private val rows = LinkedHashMap<String, SessionEntity>()
    val markInsertedCalls = mutableListOf<Pair<String, Long>>()

    /** Test helper — seed a row without going through INSERT. */
    fun seed(entity: SessionEntity) {
        rows[entity.id] = entity
    }

    override fun insert(entity: SessionEntity) {
        rows[entity.id] = entity
    }

    override fun getById(id: String): SessionEntity? = rows[id]

    override fun updateFinalOutputText(sessionId: String, text: String?) {
        rows[sessionId]?.let { rows[sessionId] = it.copy(finalOutputText = text) }
    }

    override fun updateInputText(sessionId: String, text: String?) {
        rows[sessionId]?.let { rows[sessionId] = it.copy(inputText = text) }
    }

    override fun updateAudioDuration(sessionId: String, durationSeconds: Long) {
        rows[sessionId]?.let { rows[sessionId] = it.copy(audioDurationSeconds = durationSeconds) }
    }

    override fun getAll(): List<SessionEntity> = rows.values.toList()

    override fun getByType(type: String): List<SessionEntity> =
        rows.values.filter { it.type == type }

    override fun search(query: String): List<SessionEntity> =
        rows.values.filter {
            (it.finalOutputText?.contains(query) == true) ||
                (it.inputText?.contains(query) == true)
        }

    override fun deleteById(id: String) {
        rows.remove(id)
    }

    override fun deleteAll() {
        rows.clear()
    }

    override fun findLatestByOrigin(origin: String): SessionEntity? =
        rows.values.filter { it.origin == origin }.maxByOrNull { it.createdAt }

    override fun findLatestUnfinishedRecording(createdAtFloor: Long): SessionEntity? =
        rows.values
            .filter {
                it.status in setOf("RECORDING_INTERRUPTED", "RECORDED") &&
                    it.origin == "KEYBOARD" &&
                    it.createdAt >= createdAtFloor
            }
            .maxByOrNull { it.createdAt }

    override fun findActiveSessionIds(): List<String> =
        rows.values
            .filter {
                it.status in setOf(
                    "RECORDING",
                    "RECORDING_INTERRUPTED",
                    "RECORDED",
                    "TRANSCRIBING",
                )
            }
            .map { it.id }

    override fun findWithMissingDuration(): List<SessionEntity> =
        rows.values.filter { it.audioFilePath != null && it.audioDurationSeconds == 0L }

    override fun updateStatus(id: String, status: String) {
        rows[id]?.let { rows[id] = it.copy(status = status) }
    }

    override fun updateError(id: String, type: String?, message: String?) {
        rows[id]?.let {
            rows[id] = it.copy(lastErrorType = type, lastErrorMessage = message)
        }
    }

    override fun updateQueuedPromptIds(id: String, ids: String?) {
        rows[id]?.let { rows[id] = it.copy(queuedPromptIds = ids) }
    }

    override fun clearAudioFilePath(id: String) {
        rows[id]?.let { rows[id] = it.copy(audioFilePath = null) }
    }

    override fun updateAudioFilePath(id: String, path: String) {
        rows[id]?.let { rows[id] = it.copy(audioFilePath = path) }
    }

    /**
     * Block A1 — encoded paths string is split on the pipe delimiter
     * (matches `Converters.fromStringList`). Empty string round-trips
     * to empty list (consumer's job is to write the pre-encoded value).
     */
    override fun updateAudioFilePaths(id: String, paths: String) {
        val decoded = if (paths.isEmpty()) emptyList() else paths.split("|")
        rows[id]?.let { rows[id] = it.copy(audioFilePaths = decoded) }
    }

    // ── M4 additions ──

    override fun markInserted(id: String, timestamp: Long) {
        markInsertedCalls += id to timestamp
        rows[id]?.let { rows[id] = it.copy(insertedAt = timestamp) }
    }

    /**
     * Recovery-chain SEND-path reconciliation (2026-05-22) — mirrors the
     * production `UPDATE`: writes status + metadata, leaves the
     * `audio_file_path` / `audio_file_paths` columns untouched.
     */
    override fun finalizeRecordedMetadata(
        id: String,
        status: String,
        targetApp: String?,
        language: String?,
        durationSeconds: Long,
        queuedPromptIds: String?,
    ) {
        rows[id]?.let {
            rows[id] = it.copy(
                status = status,
                targetAppPackage = targetApp,
                language = language,
                audioDurationSeconds = durationSeconds,
                queuedPromptIds = queuedPromptIds,
            )
        }
    }

    override fun findPendingInsertion(freshnessFloor: Long): List<SessionEntity> =
        rows.values
            .filter {
                it.status == "COMPLETED" &&
                    it.finalOutputText != null &&
                    it.insertedAt == null &&
                    it.createdAt >= freshnessFloor
            }
            .sortedByDescending { it.createdAt }

    override fun deleteInsertedOlderThan(cutoff: Long): Int {
        val victims = rows.values.filter {
            it.insertedAt != null && (it.insertedAt!! < cutoff)
        }
        victims.forEach { rows.remove(it.id) }
        return victims.size
    }

    override fun findOrphanedTerminalAudio(cutoff: Long): List<OrphanedAudioRow> =
        rows.values
            .filter {
                (it.status == "FAILED" || it.status == "CANCELLED") &&
                    it.audioFilePath != null &&
                    it.createdAt < cutoff
            }
            .map { OrphanedAudioRow(id = it.id, audioFilePath = it.audioFilePath!!) }

    override fun clearAudioFilePathBulk(ids: List<String>) {
        ids.forEach { id ->
            rows[id]?.let { rows[id] = it.copy(audioFilePath = null) }
        }
    }

    override fun getSessionsByStatuses(statuses: List<String>): List<SessionEntity> =
        rows.values.filter { it.status in statuses }

    override fun findAllAudioFilePaths(): List<String?> =
        rows.values.mapNotNull { it.audioFilePath }

    override fun markLegacyAudioSessionsFailed(
        legacyPath: String,
        reason: String,
        failedStatus: String
    ): Int {
        val protectedStates = setOf("FAILED", "CANCELLED", "COMPLETED")
        var updated = 0
        rows.values.toList().forEach { entity ->
            if (entity.audioFilePath == legacyPath && entity.status !in protectedStates) {
                rows[entity.id] = entity.copy(
                    status = failedStatus,
                    lastErrorType = "UNKNOWN",
                    lastErrorMessage = reason
                )
                updated++
            }
        }
        return updated
    }
}
