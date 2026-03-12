package net.devemperor.dictate.database.dao

import androidx.room.Dao
import androidx.room.Insert
import net.devemperor.dictate.database.entity.TextInsertionEntity

@Dao
interface TextInsertionDao {

    @Insert
    fun insert(entity: TextInsertionEntity)
}
