package net.devemperor.dictate.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcriptions",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"], childColumns = ["session_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("session_id"), Index(value = ["session_id", "version"], unique = true)]
)
data class TranscriptionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "is_current") val isCurrent: Boolean,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "model_used") val modelUsed: String,
    @ColumnInfo(name = "provider") val provider: String,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Long = 0,
    @ColumnInfo(name = "completion_tokens") val completionTokens: Long = 0,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
