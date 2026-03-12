package net.devemperor.dictate.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "processing_steps",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"], childColumns = ["session_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("session_id"),
        Index(value = ["session_id", "chain_index", "version"], unique = true),
        Index("previous_step_id"),
        Index("previous_transcription_id"),
        Index("source_session_id")
    ]
)
data class ProcessingStepEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "step_type") val stepType: String,
    @ColumnInfo(name = "chain_index") val chainIndex: Int,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "is_current") val isCurrent: Boolean,
    @ColumnInfo(name = "input_text") val inputText: String,
    @ColumnInfo(name = "output_text") val outputText: String?,
    @ColumnInfo(name = "model_used") val modelUsed: String,
    @ColumnInfo(name = "provider") val provider: String,
    @ColumnInfo(name = "prompt_used") val promptUsed: String?,
    @ColumnInfo(name = "prompt_entity_id") val promptEntityId: Int?,
    @ColumnInfo(name = "previous_step_id") val previousStepId: String?,
    @ColumnInfo(name = "previous_transcription_id") val previousTranscriptionId: String?,
    @ColumnInfo(name = "source_session_id") val sourceSessionId: String?,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Long = 0,
    @ColumnInfo(name = "completion_tokens") val completionTokens: Long = 0,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
