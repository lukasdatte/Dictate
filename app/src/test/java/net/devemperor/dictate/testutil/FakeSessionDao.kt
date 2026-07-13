package net.devemperor.dictate.testutil

import androidx.paging.PagingSource
import androidx.paging.PagingState
import net.devemperor.dictate.database.dao.OrphanedAudioRow
import net.devemperor.dictate.database.dao.SessionDao
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.history.isPendingInsertion

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

    /** Ids passed to [deleteById] — lets tests assert a blocked delete never hit the DAO. */
    val deletedIds = mutableListOf<String>()

    /** Records each [deleteAll] invocation — asserts the unguarded wipe path fired. */
    var deleteAllCalls = 0
        private set

    /** Exemption lists passed to [deleteAllExcept] — asserts active ids were skipped. */
    val deleteAllExceptCalls = mutableListOf<List<String>>()

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

    /**
     * In-memory analogue of the paged history query. Filter semantics
     * approximate the SQL: [searchPattern] arrives pre-escaped (see
     * `LikeEscape`), so the backslashes are stripped before the
     * substring check. NOT a full `LIKE` emulation — tests that assert
     * real wildcard/ESCAPE behaviour run against Room
     * (`SessionDaoHistoryTest`).
     */
    override fun pagedHistory(
        type: String?,
        searchPattern: String?,
    ): PagingSource<Int, SessionEntity> {
        val needle = searchPattern?.let(::unescapeLike)
        val snapshot = rows.values
            .filter { type == null || it.type == type }
            .filter {
                needle == null ||
                    (it.finalOutputText?.contains(needle) == true) ||
                    (it.inputText?.contains(needle) == true)
            }
            .sortedByDescending { it.createdAt }
        return ListPagingSource(snapshot)
    }

    /**
     * In-memory analogue of the in-keyboard history-panel query
     * (Paket 3 / ADR-0014): excludes review-refinement carriers, sorts
     * pending insertions first (reusing the production predicate), then
     * newest-first. Uses the `"REVIEW_REFINEMENT"` string literal to match the
     * SQL's literal comparison.
     */
    override fun pagedHistoryPanel(): PagingSource<Int, SessionEntity> {
        val snapshot = rows.values
            .filter { it.origin != "REVIEW_REFINEMENT" }
            .sortedWith(
                compareByDescending<SessionEntity> { it.isPendingInsertion() }
                    .thenByDescending { it.createdAt }
            )
        return ListPagingSource(snapshot)
    }

    /** Reverses `LikeEscape.escape`: drops each escape-backslash, keeps the escaped char. */
    private fun unescapeLike(pattern: String): String = buildString {
        var i = 0
        while (i < pattern.length) {
            if (pattern[i] == '\\' && i + 1 < pattern.length) {
                append(pattern[i + 1])
                i += 2
            } else {
                append(pattern[i])
                i++
            }
        }
    }

    /**
     * In-memory analogue of the lazy-sync cursor query (ADR-0020): COMPLETED sessions with text,
     * excluding REVIEW_REFINEMENT carriers, strictly after `(afterCreatedAt, afterSessionId)`,
     * totally ordered by `(created_at, id)` ascending, capped at [limit].
     */
    override fun sessionsAfterCursor(
        afterCreatedAt: Long,
        afterSessionId: String,
        limit: Int,
    ): List<SessionEntity> =
        syncEligible()
            .filter {
                it.createdAt > afterCreatedAt ||
                    (it.createdAt == afterCreatedAt && it.id > afterSessionId)
            }
            .take(limit)

    override fun sessionsFromStart(limit: Int): List<SessionEntity> =
        syncEligible().take(limit)

    private fun syncEligible(): List<SessionEntity> =
        rows.values
            .filter {
                it.status == "COMPLETED" &&
                    it.finalOutputText != null &&
                    it.origin != "REVIEW_REFINEMENT"
            }
            .sortedWith(compareBy<SessionEntity> { it.createdAt }.thenBy { it.id })

    override fun deleteById(id: String) {
        deletedIds += id
        rows.remove(id)
    }

    override fun deleteAll() {
        deleteAllCalls++
        rows.clear()
    }

    override fun deleteAllExcept(exemptIds: List<String>) {
        deleteAllExceptCalls += exemptIds
        val exempt = exemptIds.toSet()
        rows.keys.toList().forEach { if (it !in exempt) rows.remove(it) }
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

    override fun deleteCancelledOlderThan(cutoff: Long): Int {
        val victims = rows.values.filter {
            it.status == "CANCELLED" && it.createdAt < cutoff
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

/**
 * Minimal offset-keyed [PagingSource] over a pre-filtered list —
 * backs [FakeSessionDao.pagedHistory] (and [EmptySessionDao]) so DAO
 * fakes satisfy the paging DAO surface without Room. Mirrors the
 * key semantics of Room's `LimitOffsetPagingSource`: key == start
 * offset, `nextKey == null` at the end of the list.
 */
class ListPagingSource(
    private val items: List<SessionEntity>,
) : PagingSource<Int, SessionEntity>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SessionEntity> {
        val start = params.key ?: 0
        val page = items.drop(start).take(params.loadSize)
        return LoadResult.Page(
            data = page,
            prevKey = if (start == 0) null else (start - params.loadSize).coerceAtLeast(0),
            nextKey = if (start + page.size >= items.size) null else start + page.size,
        )
    }

    override fun getRefreshKey(state: PagingState<Int, SessionEntity>): Int? = null
}
