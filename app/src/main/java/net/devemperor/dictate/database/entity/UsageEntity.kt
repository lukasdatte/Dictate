package net.devemperor.dictate.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "usage")
data class UsageEntity(
    @PrimaryKey
    @ColumnInfo(name = "model_name")
    val modelName: String,

    @ColumnInfo(name = "audio_time")
    val audioTime: Long = 0,

    @ColumnInfo(name = "input_tokens")
    val inputTokens: Long = 0,

    @ColumnInfo(name = "output_tokens")
    val outputTokens: Long = 0,

    @ColumnInfo(name = "model_provider")
    val modelProvider: String = "OPENAI"  // AIProvider enum name as string
)
