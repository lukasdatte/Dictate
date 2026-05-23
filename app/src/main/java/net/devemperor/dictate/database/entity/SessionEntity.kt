package net.devemperor.dictate.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.devemperor.dictate.ai.AIProviderException

/**
 * Persistent session row (Spec 1 §6.1, M4 schema).
 *
 * **FK semantics — `parent_session_id ON DELETE SET NULL`
 * (B3-VAL-W1 F-1):** any row-level DELETE on a parent session (cleanup
 * policy, user-driven delete, future paths) preserves the children;
 * their `parent_session_id` becomes NULL and they surface as
 * root-level history items. CASCADE would have allowed
 * `deleteInsertedOlderThan` to silently take fresh POST_PROCESSING
 * children with their aged-out parent — see Spec 1 §6.5 +
 * `research/b3-cleanup-cascade-and-backfill-policy.md` §3.
 *
 * History UI shows sessions as a flat list, so the lost parent-child
 * link has no user-visible regression. The data-preservation
 * guarantee outweighs it.
 *
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §6.5
 */
@Entity(
    tableName = "sessions",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"], childColumns = ["parent_session_id"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [
        Index("parent_session_id"),
        Index("type"),
        Index("created_at"),
        Index("origin"),  // NEW — for getLastKeyboardSession query
        Index("status")   // NEW — for history list filtering
    ]
)
data class SessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "target_app_package") val targetAppPackage: String?,
    @ColumnInfo(name = "language") val language: String?,
    @ColumnInfo(name = "audio_file_path") val audioFilePath: String?,
    /**
     * Pipe-delimited list of audio segment paths (ADR-0007).
     *
     * **Phase 1 — dual-column window:** lives alongside [audioFilePath]
     * during the multi-file-repository rollout. New code reads through
     * [effectiveAudioFilePaths]; the legacy [audioFilePath] column will
     * be removed in `MIGRATION_5_6` once every writer has been
     * converted to populate this list.
     *
     * **Default `emptyList()` round-trips with SQL `DEFAULT ''`** —
     * `Converters.fromStringList(emptyList())` produces `""`, which
     * matches the column default declared in `MIGRATION_4_5`.
     */
    @ColumnInfo(name = "audio_file_paths") val audioFilePaths: List<String> = emptyList(),
    @ColumnInfo(name = "audio_duration_seconds") val audioDurationSeconds: Long = 0,
    @ColumnInfo(name = "parent_session_id") val parentSessionId: String? = null,

    // NEW — terminal status (Double-Enum, see docs/DATABASE-PATTERNS.md)
    @ColumnInfo(name = "status") val status: String = SessionStatus.RECORDED.name,

    // NEW — where the session was started from (Double-Enum)
    @ColumnInfo(name = "origin") val origin: String = SessionOrigin.KEYBOARD.name,

    // NEW — queued prompts at the time of session creation (comma-separated IDs)
    @ColumnInfo(name = "queued_prompt_ids") val queuedPromptIds: String? = null,

    // NEW — last error context (only for status == FAILED)
    @ColumnInfo(name = "last_error_type") val lastErrorType: String? = null,
    @ColumnInfo(name = "last_error_message") val lastErrorMessage: String? = null,

    // Denormalized fields — cache for fast search/display in HistoryActivity
    // Updated after each pipeline step
    @ColumnInfo(name = "final_output_text") val finalOutputText: String? = null,
    @ColumnInfo(name = "input_text") val inputText: String? = null,

    /**
     * Milliseconds-since-epoch when the COMPLETED result was inserted
     * into the editor.
     *
     * **NULL semantics (Spec 1 §6.5 + B3-VAL-W1 F-2):** NULL means
     * either "result available but not yet surfaced to the user" (live
     * post-M4 flow) OR "pre-M4 legacy row whose insertion timestamp is
     * not reconstructable" (migration-time backfill). Both classes are
     * **immune to `deleteInsertedOlderThan`**, which filters
     * `inserted_at IS NOT NULL`. The two callers disambiguate by
     * adding their own filter:
     *
     *  - [net.devemperor.dictate.database.dao.SessionDao.findPendingInsertion]
     *    gates the legacy class with a freshness floor on `created_at`
     *    (caller passes `Pref.PendingInsertionFreshnessMs` cutoff) so
     *    months of pre-M4 history don't flood manual-paste notifications
     *    on the first post-upgrade boot.
     *  - [net.devemperor.dictate.database.dao.SessionDao.deleteInsertedOlderThan]
     *    leaves NULL rows alone (the cleanup-marker doesn't apply).
     *
     * Pre-existing rows are backfilled to NULL in MIGRATION_3_4 —
     * see [net.devemperor.dictate.database.migration.MIGRATION_3_4]
     * step 2 + `research/b3-cleanup-cascade-and-backfill-policy.md` §4.
     * Intentionally NOT indexed — see §6.1 "Warum kein
     * `index_sessions_inserted_at`?" for the cost/benefit reasoning.
     */
    @ColumnInfo(name = "inserted_at") val insertedAt: Long? = null
) {
    // Convenience enum accessors (boundary conversion — handles DB values unknown to this build)
    val statusEnum: SessionStatus
        get() = runCatching { SessionStatus.valueOf(status) }.getOrDefault(SessionStatus.RECORDED)

    val originEnum: SessionOrigin
        get() = runCatching { SessionOrigin.valueOf(origin) }.getOrDefault(SessionOrigin.KEYBOARD)

    // Finding SEC-1-2 / K1: Double-Enum accessor for lastErrorType.
    // Reuses AIProviderException.ErrorType as the single source of truth — see
    // docs/DATABASE-PATTERNS.md (table row: sessions.last_error_type).
    // Note: CANCELLED is an ErrorType value (used by the AI layer for aborted
    // calls) but is NEVER persisted here — cancellation lives in `status`.
    val errorTypeEnum: AIProviderException.ErrorType?
        get() = lastErrorType?.let {
            runCatching { AIProviderException.ErrorType.valueOf(it) }.getOrNull()
        }

}
