package net.devemperor.dictate.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import net.devemperor.dictate.database.entity.PromptEntity

@Dao
interface PromptDao {

    @Query("SELECT * FROM prompts ORDER BY pos ASC")
    fun getAll(): List<PromptEntity>

    @Query("SELECT * FROM prompts WHERE id = :id")
    fun getById(id: Int): PromptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: PromptEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entities: List<PromptEntity>)

    @Update
    fun update(entity: PromptEntity)

    @Query("DELETE FROM prompts WHERE id = :id")
    fun deleteById(id: Int)

    @Query("DELETE FROM prompts")
    fun deleteAll()

    @Query("SELECT id FROM prompts WHERE auto_apply = 1 ORDER BY pos ASC")
    fun getAutoApplyIds(): List<Int>

    @Query("SELECT COUNT(*) FROM prompts")
    fun count(): Int
}
