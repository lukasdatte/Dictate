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
}
