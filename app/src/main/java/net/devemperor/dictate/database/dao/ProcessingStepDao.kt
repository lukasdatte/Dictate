package net.devemperor.dictate.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import net.devemperor.dictate.database.entity.ProcessingStepEntity

@Dao
interface ProcessingStepDao {

    @Insert
    fun insert(entity: ProcessingStepEntity)

    @Query("SELECT COALESCE(MAX(chain_index), -1) FROM processing_steps WHERE session_id = :sessionId AND is_current = 1")
    fun getMaxChainIndex(sessionId: String): Int

    @Query("SELECT COALESCE(MAX(version), 0) FROM processing_steps WHERE session_id = :sessionId AND chain_index = :chainIndex")
    fun getMaxVersion(sessionId: String, chainIndex: Int): Int

    @Query("UPDATE processing_steps SET is_current = 0 WHERE session_id = :sessionId AND chain_index = :chainIndex AND is_current = 1")
    fun clearCurrentAtIndex(sessionId: String, chainIndex: Int)

    @Query("UPDATE processing_steps SET is_current = 0 WHERE session_id = :sessionId AND chain_index > :chainIndex AND is_current = 1")
    fun invalidateDownstream(sessionId: String, chainIndex: Int)

    @Query("SELECT * FROM processing_steps WHERE id = :stepId")
    fun getById(stepId: String): ProcessingStepEntity?

    @Query("SELECT * FROM processing_steps WHERE session_id = :sessionId AND is_current = 1 ORDER BY chain_index")
    fun getCurrentChain(sessionId: String): List<ProcessingStepEntity>

    @Query("SELECT * FROM processing_steps WHERE session_id = :sessionId AND chain_index = :chainIndex ORDER BY version")
    fun getVersionsAtIndex(sessionId: String, chainIndex: Int): List<ProcessingStepEntity>

    @Query("UPDATE processing_steps SET is_current = 1 WHERE id = :stepId")
    fun setCurrentById(stepId: String)
}
