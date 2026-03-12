package net.devemperor.dictate.database.dao

import androidx.room.Dao
import androidx.room.Insert
import net.devemperor.dictate.database.entity.CompletionLogEntity

@Dao
interface CompletionLogDao {

    @Insert
    fun insert(entity: CompletionLogEntity)
}
