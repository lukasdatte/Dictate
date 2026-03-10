package net.devemperor.dictate.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "completion_log",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"], childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("session_id"), Index("timestamp"), Index("step_id"), Index("transcription_id")]
)
data class CompletionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "step_id") val stepId: String?,
    @ColumnInfo(name = "transcription_id") val transcriptionId: String?,
    @ColumnInfo(name = "system_prompt") val systemPrompt: String?,
    @ColumnInfo(name = "user_prompt") val userPrompt: String?,
    val success: Boolean,
    @ColumnInfo(name = "error_message") val errorMessage: String?
)
