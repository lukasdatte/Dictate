package net.devemperor.dictate.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "prompts")
data class PromptEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Int = 0,

    @ColumnInfo(name = "pos")
    val pos: Int,

    @ColumnInfo(name = "name")
    val name: String?,

    @ColumnInfo(name = "prompt")
    val prompt: String?,

    @ColumnInfo(name = "requires_selection")
    val requiresSelection: Boolean = false,

    @ColumnInfo(name = "auto_apply")
    val autoApply: Boolean = false
)
