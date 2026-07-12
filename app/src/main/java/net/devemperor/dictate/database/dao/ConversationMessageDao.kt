package net.devemperor.dictate.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import net.devemperor.dictate.database.entity.ConversationMessageEntity

@Dao
interface ConversationMessageDao {

    @Insert
    fun insert(entity: ConversationMessageEntity)

    /** Full conversation in wire order (SYSTEM first, then USER turns by seq). */
    @Query("SELECT * FROM conversation_messages WHERE session_id = :sessionId ORDER BY seq")
    fun getBySession(sessionId: String): List<ConversationMessageEntity>

    /** USER rows only, ordered by turn — the per-turn user message content. */
    @Query("SELECT * FROM conversation_messages WHERE session_id = :sessionId AND role = 'USER' ORDER BY turn_index")
    fun getUserMessages(sessionId: String): List<ConversationMessageEntity>

    /**
     * The USER content persisted for the turn at [turnIndex], or null when
     * absent. The error-resume path replays this exact message so the
     * regenerated output stays consistent with the persisted conversation (K3).
     */
    @Query("SELECT content FROM conversation_messages WHERE session_id = :sessionId AND role = 'USER' AND turn_index = :turnIndex LIMIT 1")
    fun getUserMessageAt(sessionId: String, turnIndex: Int): String?

    /** The persisted SYSTEM row content (turn 0's system prompt), or null. */
    @Query("SELECT content FROM conversation_messages WHERE session_id = :sessionId AND role = 'SYSTEM' ORDER BY seq LIMIT 1")
    fun getSystemContent(sessionId: String): String?

    @Query("SELECT COALESCE(MAX(seq), -1) FROM conversation_messages WHERE session_id = :sessionId")
    fun getMaxSeq(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM conversation_messages WHERE session_id = :sessionId")
    fun countBySession(sessionId: String): Int
}
