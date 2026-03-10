package net.devemperor.dictate.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "text_insertions",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"], childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("session_id"),
        Index("timestamp"),
        Index("source_step_id"),
        Index("source_transcription_id")
    ]
)
data class TextInsertionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    val timestamp: Long,
    @ColumnInfo(name = "inserted_text") val insertedText: String,
    @ColumnInfo(name = "replaced_text") val replacedText: String?,
    @ColumnInfo(name = "target_app_package") val targetAppPackage: String?,
    @ColumnInfo(name = "cursor_position") val cursorPosition: Int?,
    @ColumnInfo(name = "source_step_id") val sourceStepId: String?,
    @ColumnInfo(name = "source_transcription_id") val sourceTranscriptionId: String?,
    @ColumnInfo(name = "insertion_method") val insertionMethod: String
)
