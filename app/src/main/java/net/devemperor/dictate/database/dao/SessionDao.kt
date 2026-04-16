package net.devemperor.dictate.database.dao

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

    @Query("UPDATE sessions SET final_output_text = :text WHERE id = :sessionId")
    fun updateFinalOutputText(sessionId: String, text: String?)

    @Query("UPDATE sessions SET input_text = :text WHERE id = :sessionId")
    fun updateInputText(sessionId: String, text: String?)

    @Query("UPDATE sessions SET audio_duration_seconds = :durationSeconds WHERE id = :sessionId")
    fun updateAudioDuration(sessionId: String, durationSeconds: Long)

    @Query("SELECT * FROM sessions ORDER BY created_at DESC")
    fun getAll(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE type = :type ORDER BY created_at DESC")
    fun getByType(type: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE final_output_text LIKE '%' || :query || '%' OR input_text LIKE '%' || :query || '%' ORDER BY created_at DESC")
    fun search(query: String): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :id")
    fun deleteById(id: String)

    @Query("DELETE FROM sessions")
    fun deleteAll()

    // ── NEW (reprocess refactor) ────────────────────────────────────────────

    /**
     * Returns the most recent session that was initiated from the given origin.
     * Used by [net.devemperor.dictate.core.SessionTracker.getLastKeyboardSession]
     * with origin = SessionOrigin.KEYBOARD.name.
     */
    @Query("SELECT * FROM sessions WHERE origin = :origin ORDER BY created_at DESC LIMIT 1")
    fun findLatestByOrigin(origin: String): SessionEntity?

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

    /** Clears the audio file path (used when the audio file is deleted but the session is kept). */
    @Query("UPDATE sessions SET audio_file_path = NULL WHERE id = :id")
    fun clearAudioFilePath(id: String)

    /**
     * Updates the audio file path after copy from cache -> persistent storage,
     * or after format conversion (e.g. .m4a -> .opus).
     *
     * Finding SEC-0-8: required after the RecordingRepository promotes the file
     * out of the cache directory.
     */
    @Query("UPDATE sessions SET audio_file_path = :path WHERE id = :id")
    fun updateAudioFilePath(id: String, path: String)
}
