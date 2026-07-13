package net.devemperor.dictate.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import net.devemperor.dictate.database.entity.TextInsertionEntity

@Dao
interface TextInsertionDao {

    @Insert
    fun insert(entity: TextInsertionEntity)

    /**
     * The subset of [sessionIds] that were ever delivered to the paired PC (ADR-0019/0020):
     * a session has a `WINDOWS_DISPATCH` audit row iff it was dispatched. The lazy sync reads
     * this so a `SessionUpsert.dispatched` is truthful — otherwise the archive sync would
     * overwrite the companion's dispatched flag back to false (ADR-0020 "an upsert overwrites").
     */
    @Query(
        "SELECT DISTINCT session_id FROM text_insertions " +
            "WHERE insertion_method = 'WINDOWS_DISPATCH' AND session_id IN (:sessionIds)"
    )
    fun dispatchedSessionIds(sessionIds: List<String>): List<String>
}
