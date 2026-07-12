package net.devemperor.dictate.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persisted message of a session's post-processing conversation (ADR-0012).
 *
 * The foundation writes a single `SYSTEM` row (turn_index = -1) plus one `USER`
 * row per turn (turn_index == the assistant step's `chain_index`). Assistant
 * turns are NOT stored here — they are reconstructed from the current
 * [ProcessingStepEntity] chain (design decision D-B), so there is no duplicated,
 * mutable assistant-text cache to keep coherent. `ASSISTANT` is a permitted
 * role for a future self-contained log (Paket 3).
 *
 * [content] of a USER row is the fully built consolidated user message, so a
 * regenerate replays it verbatim instead of rebuilding it (byte-faithful).
 * [content] of the SYSTEM row is the system prompt in effect at turn 0, so a
 * later continuation reuses it even if the prompt template changed since.
 *
 * `role` follows the Double-Enum pattern (see docs/DATABASE-PATTERNS.md).
 */
@Entity(
    tableName = "conversation_messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"], childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProcessingStepEntity::class,
            parentColumns = ["id"], childColumns = ["step_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("session_id"),
        Index(value = ["session_id", "seq"], unique = true),
        Index("step_id")
    ]
)
data class ConversationMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "turn_index") val turnIndex: Int,
    @ColumnInfo(name = "seq") val seq: Int,
    // Stores MessageRole.name — SQL CHECK enforces the finite set (see migration).
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "content") val content: String,
    // Nullable back-reference to the producing step; reserved for ASSISTANT rows.
    @ColumnInfo(name = "step_id") val stepId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long
) {
    /** Boundary accessor with a safe default (Double-Enum convention). */
    val roleEnum: MessageRole
        get() = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.USER)
}
