package net.devemperor.dictate.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import net.devemperor.dictate.database.entity.TranscriptionEntity

@Dao
interface TranscriptionDao {

    @Insert
    fun insert(entity: TranscriptionEntity)

    @Query("SELECT COALESCE(MAX(version), 0) FROM transcriptions WHERE session_id = :sessionId")
    fun getMaxVersion(sessionId: String): Int

    @Query("UPDATE transcriptions SET is_current = 0 WHERE session_id = :sessionId AND is_current = 1")
    fun clearCurrent(sessionId: String)

    @Query("SELECT * FROM transcriptions WHERE id = :transcriptionId")
    fun getById(transcriptionId: String): TranscriptionEntity?

    @Query("SELECT * FROM transcriptions WHERE session_id = :sessionId AND is_current = 1")
    fun getCurrent(sessionId: String): TranscriptionEntity?

    @Query("SELECT * FROM transcriptions WHERE session_id = :sessionId ORDER BY version")
    fun getAllVersions(sessionId: String): List<TranscriptionEntity>

    // ── Reprocess versioning helpers (Phase 5.1.1) ──────────────────────────

    /** Promotes a specific transcription row to current. */
    @Query("UPDATE transcriptions SET is_current = 1 WHERE id = :transcriptionId")
    fun setCurrentById(transcriptionId: String)
}
