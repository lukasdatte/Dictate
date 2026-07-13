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
    @ColumnInfo(name = "insertion_method") val insertionMethod: String,

    /**
     * The paired PC a [InsertionMethod.WINDOWS_DISPATCH] row was delivered to (ADR-0019).
     *
     * NULL = "does not apply" (DATABASE-PATTERNS Migration Rule 1): every host insertion
     * (COMMIT/PASTE) has NULL here; only a Windows dispatch carries a device id. Deliberately
     * NOT a Double-Enum — a device id is an open vocabulary (a UUID), like `target_app_package`.
     */
    @ColumnInfo(name = "target_device_id") val targetDeviceId: String? = null,
) {
    /**
     * Boundary conversion — handles a DB value unknown to this build (downgrade/rollback) by
     * falling back to [InsertionMethod.COMMIT] (DATABASE-PATTERNS convenience-accessor rule).
     */
    val insertionMethodEnum: InsertionMethod
        get() = runCatching { InsertionMethod.valueOf(insertionMethod) }
            .getOrDefault(InsertionMethod.COMMIT)
}
