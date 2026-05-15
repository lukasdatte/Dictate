package net.devemperor.dictate.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.devemperor.dictate.ai.AIProviderException

@Entity(
    tableName = "sessions",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"], childColumns = ["parent_session_id"],
        onDelete = ForeignKey.CASCADE
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
     * into the editor. `NULL` means "result available but not yet
     * surfaced to the user" — drives the pending-insertion query
     * (see [net.devemperor.dictate.database.dao.SessionDao.findPendingInsertion])
     * and the 7-day cleanup window (Spec 1 §6.2 R.17,
     * [net.devemperor.dictate.database.dao.SessionDao.deleteInsertedOlderThan]).
     *
     * Backfilled in MIGRATION_3_4 with `created_at` for pre-existing
     * COMPLETED rows (best-effort; the exact insertion timestamp is not
     * reconstructable, but the cutoff-based cleanup tolerates the
     * approximation). Intentionally NOT indexed — see §6.1 "Warum kein
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
