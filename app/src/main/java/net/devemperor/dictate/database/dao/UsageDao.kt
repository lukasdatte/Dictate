package net.devemperor.dictate.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.devemperor.dictate.database.entity.UsageEntity

@Dao
interface UsageDao {

    @Query("SELECT * FROM usage")
    fun getAll(): List<UsageEntity>

    @Query("SELECT * FROM usage WHERE model_name = :modelName")
    fun getByModelName(modelName: String): UsageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: UsageEntity)

    /**
     * Increments usage values (like UsageDatabaseHelper.edit()).
     * Uses UPSERT semantics: insert if not exists, update if exists.
     */
    @Query("""
        INSERT INTO usage (model_name, audio_time, input_tokens, output_tokens, model_provider)
        VALUES (:modelName, :audioTime, :inputTokens, :outputTokens, :provider)
        ON CONFLICT(model_name) DO UPDATE SET
            audio_time = audio_time + :audioTime,
            input_tokens = input_tokens + :inputTokens,
            output_tokens = output_tokens + :outputTokens
    """)
    fun addUsage(modelName: String, audioTime: Long, inputTokens: Long, outputTokens: Long, provider: String)

    @Query("DELETE FROM usage")
    fun deleteAll()

    @Query("SELECT SUM(audio_time) FROM usage")
    fun getTotalAudioTime(): Long?
}
