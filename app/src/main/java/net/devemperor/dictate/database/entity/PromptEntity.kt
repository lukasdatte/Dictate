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
    val autoApply: Boolean = false,

    // Column stores PromptType.name — see MigrationTo11 for the SQL CHECK.
    // (Double-Enum pattern, docs/DATABASE-PATTERNS.md.)
    @ColumnInfo(name = "type")
    val type: String = PromptType.PROMPT.name
) {
    /**
     * Boundary accessor: parses [type] into a [PromptType], falling back to
     * [PromptType.PROMPT] for values a downgrade/rollback left unknown to this
     * build (Double-Enum convenience accessor, docs/DATABASE-PATTERNS.md).
     */
    val typeEnum: PromptType
        get() = runCatching { PromptType.valueOf(type) }.getOrDefault(PromptType.PROMPT)
}
