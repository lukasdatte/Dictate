package net.devemperor.dictate.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import net.devemperor.dictate.database.entity.SessionEntity

@Dao
interface SessionDao {

    @Insert
    fun insert(entity: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun getById(id: String): SessionEntity?

    /**
     * Return the session-ids of every session whose status is **not**
     * terminal — i.e. the session may still need its on-disk audio.
     * Consumed by [net.devemperor.dictate.audio.CacheAudioCleanupJob]
     * (recording-stack-completion §4.5.2) as the "alive set" gating
     * the 7-day TTL: only files belonging to sessions outside this
     * set are deletion-candidates.
     *
     * **Why these four statuses?**
     *
     *  - `RECORDING` — live recording, audio segments are actively
     *    being written.
     *  - `RECORDING_INTERRUPTED` — process-death survivor, the user
     *    may auto-continue on the next Record-click (B2 / ADR-0008
     *    §"Auto-Continuation"). Eligible for the 24h continuation
     *    window, after which `PipelineRecovery` promotes the row to
     *    `FAILED` (then files become deletion candidates).
     *  - `RECORDED` — stop-and-send was hit, pipeline has not yet
     *    consumed the audio. The audio must remain readable until
     *    the pipeline runs.
     *  - `TRANSCRIBING` — pipeline is in flight, audio file is being
     *    uploaded.
     *
     * `COMPLETED`/`FAILED`/`CANCELLED` are terminal — their audio is
     * either already persisted to `files/recordings/{sid}.m4a` (the
     * upload path under recording-stack-completion A.4c) or no
     * longer needed.
     */
    @Query(
        """
        SELECT id FROM sessions
        WHERE status IN ('RECORDING', 'RECORDING_INTERRUPTED', 'RECORDED', 'TRANSCRIBING')
        """
    )
    fun findActiveSessionIds(): List<String>

    @Query("UPDATE sessions SET final_output_text = :text WHERE id = :sessionId")
    fun updateFinalOutputText(sessionId: String, text: String?)

    @Query("UPDATE sessions SET input_text = :text WHERE id = :sessionId")
    fun updateInputText(sessionId: String, text: String?)

    @Query("UPDATE sessions SET audio_duration_seconds = :durationSeconds WHERE id = :sessionId")
    fun updateAudioDuration(sessionId: String, durationSeconds: Long)

    /**
     * Paged history query (F-054 / history-pagination-and-scale §2) —
     * replaces the former unbounded `getAll()` / `getByType()` /
     * `search()` trio. Room + room-paging generate a
     * `LimitOffsetPagingSource` that loads pages on Room's background
     * query executor, so the history screen no longer depends on
     * `allowMainThreadQueries()`.
     *
     * **Parameter contract:**
     *
     *  - [type] — `SessionType.name` filter, or `null` for all types
     *    (Double-Enum convention: enum→String at the call boundary).
     *  - [searchPattern] — pre-escaped search fragment (see
     *    [LikeEscape.escape]), or `null` for no text filter. The
     *    `ESCAPE '\'` clause makes user-typed `%`/`_` match literally.
     *
     * **Index verification (spec §2.6):** the `ORDER BY created_at
     * DESC` walk is served by `index_sessions_created_at`; the planner
     * scans it newest-first and applies the residual `type`/`LIKE`
     * filters, stopping after LIMIT+OFFSET matches — cheap for the
     * front-loaded access pattern of a paged list. A composite
     * `(type, created_at)` index was deliberately NOT added: the
     * nullable-parameter `(:type IS NULL OR ...)` shape can't use it,
     * and the substring `LIKE '%…%'` is unindexable without FTS. No
     * schema migration needed.
     */
    @Query(
        """
        SELECT * FROM sessions
        WHERE (:type IS NULL OR type = :type)
          AND (:searchPattern IS NULL
               OR final_output_text LIKE '%' || :searchPattern || '%' ESCAPE '\'
               OR input_text LIKE '%' || :searchPattern || '%' ESCAPE '\')
        ORDER BY created_at DESC
        """
    )
    fun pagedHistory(type: String?, searchPattern: String?): PagingSource<Int, SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :id")
    fun deleteById(id: String)

    @Query("DELETE FROM sessions")
    fun deleteAll()

    /**
     * "Delete all" with an exemption list (F-114 / history-ui-overhaul
     * §3.5) — deletes every session **except** the given [exemptIds],
     * which the caller populates with the currently-active
     * (`ActiveJobRegistry`) session ids so a running pipeline is never
     * deleted out from under itself (D7: block, don't cancel-first).
     *
     * CASCADE removes the matching `transcriptions` + `processing_steps`
     * child rows, mirroring [deleteAll]. Callers pass an empty list to
     * fall back to a full wipe (though [deleteAll] is preferred for that
     * path — an empty `NOT IN ()` is a valid but needless round-trip).
     */
    @Query("DELETE FROM sessions WHERE id NOT IN (:exemptIds)")
    fun deleteAllExcept(exemptIds: List<String>)

    // ── NEW (reprocess refactor) ────────────────────────────────────────────

    /**
     * Returns the most recent session that was initiated from the given origin.
     * Used by [net.devemperor.dictate.core.SessionTracker.getLastKeyboardSession]
     * with origin = SessionOrigin.KEYBOARD.name.
     */
    @Query("SELECT * FROM sessions WHERE origin = :origin ORDER BY created_at DESC LIMIT 1")
    fun findLatestByOrigin(origin: String): SessionEntity?

    /**
     * Returns the most recent "unfinished recording" — either
     * `RECORDING_INTERRUPTED` (process-death survivor) or `RECORDED`
     * (recording closed but transcription never completed, e.g. a
     * `TRANSCRIBING → RECORDED` recovery-downgrade) — whose
     * `created_at` is at least [createdAtFloor].
     *
     * Both statuses now flow through the **same** surfacing path
     * (`PipelineRecovery` Phase 5 → [net.devemperor.dictate.state.RecordingState.Interrupted];
     * `ActionResolvers` auto-continuation, 2026-05-23). From the user's
     * perspective both are "an unfinished recording I can continue
     * (record-click → append segment N+1) or discard (trash button)" —
     * the audio-completion distinction is irrelevant at this seam
     * because the continuation machinery (`allocateNext` +
     * MediaMuxer-concat) handles `RECORDED` and `RECORDING_INTERRUPTED`
     * identically: it allocates a fresh next segment under the same
     * session-id and concatenates whatever was already on disk.
     *
     * Filter is `origin = KEYBOARD` only — only IME-driven recordings
     * are eligible; history-reprocess and post-processing sessions are
     * out of scope.
     *
     * Returns null when no fresh unfinished session exists.
     */
    @Query(
        """
        SELECT * FROM sessions
        WHERE status IN ('RECORDING_INTERRUPTED', 'RECORDED')
          AND origin = 'KEYBOARD'
          AND created_at >= :createdAtFloor
        ORDER BY created_at DESC
        LIMIT 1
        """
    )
    fun findLatestUnfinishedRecording(createdAtFloor: Long): SessionEntity?

    /**
     * Returns all sessions whose audio file exists on disk but whose duration
     * field is still 0. Used by [net.devemperor.dictate.database.DurationHealingJob].
     *
     * NOTE (SEC-0-5): Does NOT filter by status — COMPLETED sessions migrated
     * from legacy data may also have audio_duration_seconds = 0 despite having
     * a valid audio file. The status-agnostic query catches all cases.
     */
    @Query(
        """
        SELECT * FROM sessions
        WHERE audio_file_path IS NOT NULL
          AND audio_duration_seconds = 0
        """
    )
    fun findWithMissingDuration(): List<SessionEntity>

    /** Terminal status update (Double-Enum: pass [net.devemperor.dictate.database.entity.SessionStatus].name). */
    @Query("UPDATE sessions SET status = :status WHERE id = :id")
    fun updateStatus(id: String, status: String)

    /**
     * Updates the error context. Caller passes [net.devemperor.dictate.ai.AIProviderException.ErrorType].name
     * for [type] (or null to clear). Only meaningful when [status] == FAILED.
     */
    @Query("UPDATE sessions SET last_error_type = :type, last_error_message = :message WHERE id = :id")
    fun updateError(id: String, type: String?, message: String?)

    /** Updates the comma-separated list of queued prompt IDs. */
    @Query("UPDATE sessions SET queued_prompt_ids = :ids WHERE id = :id")
    fun updateQueuedPromptIds(id: String, ids: String?)

    /**
     * Clears the audio file path (used when the audio file is deleted but the session is kept).
     *
     * **Dual-column write (ADR-0007 Phase 1):** clears both the legacy
     * `audio_file_path` and the new `audio_file_paths` (set to '' →
     * empty list). The two columns stay consistent so
     * `SessionEntity.effectiveAudioFilePaths` reflects the cleared
     * state regardless of which side a downstream reader consults.
     */
    @Query("UPDATE sessions SET audio_file_path = NULL, audio_file_paths = '' WHERE id = :id")
    fun clearAudioFilePath(id: String)

    /**
     * Updates the audio file path after copy from cache -> persistent storage,
     * or after format conversion (e.g. .m4a -> .opus).
     *
     * Finding SEC-0-8: required after the RecordingRepository promotes the file
     * out of the cache directory.
     *
     * **Dual-column write (ADR-0007 Phase 1):** mirrors the path into
     * `audio_file_paths` so the new and legacy columns stay consistent
     * during the migration window. A single-element list needs no pipe
     * delimiter.
     */
    @Query("UPDATE sessions SET audio_file_path = :path, audio_file_paths = :path WHERE id = :id")
    fun updateAudioFilePath(id: String, path: String)

    /**
     * Write the multi-segment audio-file paths into `audio_file_paths`
     * (ADR-0007 Phase-2 / recording-stack-completion Block A1).
     *
     * `paths` is the **already pipe-delimited** string the caller built via
     * `paths.joinToString(Converters.DELIMITER)`. Room's `UPDATE` queries
     * do not invoke `@TypeConverter` on bound parameters, so the encoding
     * must happen at the call site (`PipelineSessionRepoAdapter.syncAudioFilePaths`).
     *
     * **Single-column write.** Unlike [updateAudioFilePath], this does NOT
     * touch `audio_file_path` — the legacy column is frozen at allocate-
     * time (set by RecordingHardwareAdapter via the initial allocate path)
     * and only the new column tracks rolling-segment growth. Readers go
     * through `audioFilePaths` directly (Block A3 removes the
     * `effectiveAudioFilePaths` bridge).
     */
    @Query("UPDATE sessions SET audio_file_paths = :paths WHERE id = :id")
    fun updateAudioFilePaths(id: String, paths: String)

    // ── NEW (M4 pipeline-service refactor, Spec 1 §6.1) ────────────────────

    /**
     * Atomic INSERTED-transition for COMPLETED sessions — sets the
     * `inserted_at` timestamp once the result text has been pushed into
     * the editor. Idempotent (a later replay with a fresh timestamp
     * just shifts the value forward; the cleanup policy still picks it
     * up).
     *
     * Called from `PipelineModule.runEffect(Effect.ConfirmInsertion)`
     * (Spec 1 §6.2). The companion `SessionEntity.insertedAt` accessor
     * is `null` until this method runs.
     */
    @Query("UPDATE sessions SET inserted_at = :timestamp WHERE id = :id")
    fun markInserted(id: String, timestamp: Long)

    /**
     * Recovery-chain SEND-path reconciliation (2026-05-22). Transitions
     * a row that was inserted at recording-start (`status = RECORDING`,
     * via [net.devemperor.dictate.state.PipelineSessionRepoSubsystem.createRecordingSession])
     * to `RECORDED` and fills the session metadata the SEND path
     * resolved.
     *
     * **Why a dedicated query (not [updateStatus] + N setters):** the
     * SEND path must NOT touch `audio_file_path` / `audio_file_paths` —
     * those are already owned by the recording-start insert + the
     * `SyncAudioSegments` effects. A combined `UPDATE` that writes only
     * the SEND-known columns leaves the rolling-segment list intact and
     * is a single atomic statement. Re-inserting through `@Insert`
     * instead would hit the default `ABORT` conflict strategy and throw.
     *
     * Called via `SessionManager.finalizeRecordedFromRecordingRow` from
     * `PipelineOrchestrator.persistNewSession` when the row already
     * exists.
     */
    @Query(
        """
        UPDATE sessions
        SET status = :status,
            target_app_package = :targetApp,
            language = :language,
            audio_duration_seconds = :durationSeconds,
            queued_prompt_ids = :queuedPromptIds
        WHERE id = :id
        """
    )
    fun finalizeRecordedMetadata(
        id: String,
        status: String,
        targetApp: String?,
        language: String?,
        durationSeconds: Long,
        queuedPromptIds: String?,
    )

    /**
     * Pending-insertion query for the recovery pass — returns the
     * sessions whose pipeline completed but whose result has not yet
     * been surfaced to the user. Drives the restart-button + recovery
     * UI on cold-start (Spec 1 §6.3, consumed by `PipelineRecovery.recover()`).
     *
     * Ordered newest-first so the UI shows the most recent pending
     * result at the top.
     *
     * **Freshness floor (B3-VAL-W1 F-2 + Spec 1 §6.5):** the
     * `created_at >= :freshnessFloor` clause excludes legacy pre-M4
     * rows whose `inserted_at` was backfilled to NULL. Without this
     * filter every legacy COMPLETED row would surface as a
     * pending-paste candidate on the first post-upgrade boot, flooding
     * `Action.ResendAction.NotifyManualPasteNeeded`. Callers pass
     * `now - Pref.PendingInsertionFreshnessMs` (default 24h).
     */
    @Query(
        """
        SELECT * FROM sessions
        WHERE status = 'COMPLETED'
          AND final_output_text IS NOT NULL
          AND inserted_at IS NULL
          AND created_at >= :freshnessFloor
        ORDER BY created_at DESC
        """
    )
    fun findPendingInsertion(freshnessFloor: Long): List<SessionEntity>

    /**
     * Cleanup query for the idle-stop slot — deletes COMPLETED sessions
     * whose result has been inserted long enough ago that we no longer
     * need it (default cutoff: now − 7 days − 1 hour safety buffer,
     * see `Pref.SessionCleanupGracePeriodMs` + Spec 1 §6.2 R.17).
     *
     * Returns the row count for diagnostics. CASCADE deletes the
     * matching `transcriptions` + `processing_steps` rows (declared
     * `ON DELETE CASCADE` in MIGRATION_1_2).
     */
    @Query("DELETE FROM sessions WHERE inserted_at IS NOT NULL AND inserted_at < :cutoff")
    fun deleteInsertedOlderThan(cutoff: Long): Int

    /**
     * Long-horizon retention for never-inserted terminal rows
     * (history-pagination-and-scale §2.5, Information-Gap-1 fallback):
     * deletes CANCELLED sessions whose `created_at` is older than
     * [cutoff] (callers pass `now − 60 d`).
     *
     * **Why CANCELLED only:** FAILED rows are user-visible "what went
     * wrong" entries and are kept forever per the documented
     * non-destructive philosophy ([net.devemperor.dictate.state.PipelineOrphanCleaner]
     * doc — "auto-deleting them would silently lose information").
     * A user-cancelled session carries no comparable diagnostic value
     * once it is two months old, so it may age out.
     *
     * `created_at` is a pre-existing NOT-NULL column, so the
     * DATABASE-PATTERNS "NULL is the marker does not apply" backfill
     * rule is not in play; the SET-NULL FK on `parent_session_id`
     * guarantees row-level DELETEs preserve child sessions (they
     * resurface as root-level history entries).
     *
     * Returns the deleted-row count for diagnostics. CASCADE removes
     * the matching `transcriptions` + `processing_steps` child rows.
     */
    @Query("DELETE FROM sessions WHERE status = 'CANCELLED' AND created_at < :cutoff")
    fun deleteCancelledOlderThan(cutoff: Long): Int

    /**
     * Orphan-audio cleanup helper (KG-SST-2, Spec 1 §11.7.0 + §6.3.1).
     * Returns `(id, audio_file_path)` pairs for sessions stuck in a
     * terminal failure-state (FAILED or CANCELLED) older than [cutoff]
     * whose audio file is still on disk.
     *
     * Layer separation: this DAO returns the data only — the caller
     * (typically `DictatePipelineService.cleanupOrphanedAudio()` in
     * the idle-stop slot) is responsible for the `File.delete()` and
     * the follow-up [clearAudioFilePathBulk] call. Keeping File-IO out
     * of the DAO mirrors `RecordingRepository.deleteBySessionId()`.
     */
    @Query(
        """
        SELECT id, audio_file_path FROM sessions
        WHERE status IN ('FAILED', 'CANCELLED')
          AND audio_file_path IS NOT NULL
          AND created_at < :cutoff
        """
    )
    fun findOrphanedTerminalAudio(cutoff: Long): List<OrphanedAudioRow>

    /**
     * Bulk-clear `audio_file_path` for the supplied session IDs. Used
     * by `DictatePipelineService.cleanupOrphanedAudio()` after the
     * File.delete() pass succeeded (Spec 1 §6.3.1). Idempotent —
     * additive across retries.
     *
     * **Dual-column write (ADR-0007 Phase 1):** clears both `audio_file_path`
     * and `audio_file_paths` so the cleared state holds regardless of
     * which side a downstream reader consults.
     */
    @Query("UPDATE sessions SET audio_file_path = NULL, audio_file_paths = '' WHERE id IN (:ids)")
    fun clearAudioFilePathBulk(ids: List<String>)

    /**
     * Recovery-bulk-read: returns every session whose `status` matches
     * one of [statuses] (Double-Enum: callers pass
     * `SessionStatus.X.name` strings — Room has no built-in converter
     * for `List<SessionStatus>` in a CHECK column, and the project
     * keeps the boundary at the call site by convention; see
     * Spec 1 §6.3).
     *
     * Used by `PipelineRecovery.recover()` to find half-written
     * `RECORDING`/`TRANSCRIBING` rows that need promotion after a
     * process death.
     */
    @Query("SELECT * FROM sessions WHERE status IN (:statuses)")
    fun getSessionsByStatuses(statuses: List<String>): List<SessionEntity>

    /**
     * Returns every non-null `audio_file_path` in the table. Used by
     * `AudioFileFactory.cleanupOrphans()` (Spec 1 §4.11.5.1 step 8)
     * to compute the "still-referenced" set during the boot-time
     * orphan cleanup. The result is read-only and the order is not
     * guaranteed — callers convert to a `Set<String>` before doing
     * set-difference against the on-disk inventory.
     */
    @Query("SELECT audio_file_path FROM sessions WHERE audio_file_path IS NOT NULL")
    fun findAllAudioFilePaths(): List<String?>

    /**
     * Legacy-audio-file migration (Spec 1 §4.11.6.2 KG-AFF-2) — promotes
     * any session that points at the historical fixed-name audio file
     * (`cacheDir/audio.m4a`) to `status = FAILED` so the file can be
     * deleted without losing the user-facing entry. Runs once at
     * service boot, gated by a SharedPreferences flag.
     *
     * **Idempotence (Phase-B S-7):** the `WHERE status NOT IN (...)`
     * filter preserves the original `last_error_message` on rows that
     * have already failed (or completed / been cancelled) — without
     * it, a second run after a pref-wipe would clobber historical
     * error context. Sessions that reach this method in
     * `RECORDING`/`RECORDED`/`TRANSCRIBING` are the only ones that
     * can be safely promoted to FAILED with the legacy-migration
     * reason.
     *
     * @return the number of rows updated (diagnostic).
     */
    @Query(
        """
        UPDATE sessions
        SET status = :failedStatus,
            last_error_type = 'UNKNOWN',
            last_error_message = :reason
        WHERE audio_file_path = :legacyPath
          AND status NOT IN ('FAILED', 'CANCELLED', 'COMPLETED')
        """
    )
    fun markLegacyAudioSessionsFailed(
        legacyPath: String,
        reason: String,
        failedStatus: String
    ): Int
}

/**
 * Projection row for [SessionDao.findOrphanedTerminalAudio]. Carries
 * just the session ID + audio file path so the caller can both
 * delete the file and zero out the DB column in a follow-up bulk
 * update (`clearAudioFilePathBulk`).
 *
 * **Top-level location (B3-VAL-W1 F-30 deviation):** Sibling DAO
 * projections in the project are nested inside their DAO interface
 * (e.g. `TranscriptionDao.OrphanRow`). `OrphanedAudioRow` keeps a
 * top-level location because external consumers (`PipelineOrphanCleaner`)
 * reference the type by its short name in field signatures; nesting it
 * would force `SessionDao.OrphanedAudioRow` across two-three call
 * sites for marginal nesting benefit. Re-evaluated against the
 * project's typical 1-2-call-site projections, the top-level form
 * stays — convention drift accepted.
 *
 * Room synthesises the column mapping at compile time (`id` → `id`,
 * `audio_file_path` → `audioFilePath`).
 */
data class OrphanedAudioRow(
    @androidx.room.ColumnInfo(name = "id") val id: String,
    @androidx.room.ColumnInfo(name = "audio_file_path") val audioFilePath: String
)
