package net.devemperor.dictate.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.devemperor.dictate.database.entity.PromptEntity

@Dao
interface PromptDao {

    @Query("SELECT * FROM prompts ORDER BY pos ASC")
    fun getAll(): List<PromptEntity>

    /**
     * Reactive stream of ALL prompts (pc-dictation-activity F7). The PC-dictation Activity's
     * text-pill row collects this and filters to [net.devemperor.dictate.database.entity.PromptType.TEXT]
     * in Kotlin (via `PcTextPills.filter`, JVM-testable) rather than in SQL, so the filter is unit-
     * tested without Room instrumentation. Room re-emits on any `prompts` write (InvalidationTracker).
     */
    @Query("SELECT * FROM prompts ORDER BY pos ASC")
    fun getAllFlow(): Flow<List<PromptEntity>>

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

    // Text pills (type = 'TEXT') are never auto-applied — their literal content
    // would otherwise be sent to the model as an instruction. Filter them out at
    // the source (plan §4.2 "Queue-Härtung"); the editor additionally hides the
    // auto-apply switch for text pills.
    @Query("SELECT id FROM prompts WHERE auto_apply = 1 AND type <> 'TEXT' ORDER BY pos ASC")
    fun getAutoApplyIds(): List<Int>

    @Query("SELECT COUNT(*) FROM prompts")
    fun count(): Int

    /**
     * The next free append position: `MAX(pos) + 1`, or 0 on an empty table. Use this — NOT
     * [count] — when appending a row: `pos` can have gaps (a middle row deleted leaves e.g. 0,2),
     * and `COUNT(*)` would then hand out an already-occupied `pos`, colliding two rows on the same
     * position (there is no UNIQUE constraint on `pos`, so both persist and their order is undefined).
     */
    @Query("SELECT COALESCE(MAX(pos), -1) + 1 FROM prompts")
    fun nextPos(): Int
}
