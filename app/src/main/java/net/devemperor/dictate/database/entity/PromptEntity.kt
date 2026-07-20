package net.devemperor.dictate.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "prompts")
data class PromptEntity @JvmOverloads constructor(
    // @JvmOverloads keeps the historical 7-arg constructor (…, type) Java-visible after the v12
    // envelope columns were appended — Java callers cannot use Kotlin default arguments, so every
    // `new PromptEntity(id, pos, name, prompt, requiresSelection, autoApply, type)` call site keeps
    // compiling while the new provenance columns default. Room uses the full primary constructor.
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
    val type: String = PromptType.PROMPT.name,

    /**
     * Shareable identity of this prompt (v12, spec §7.3). [id] stays the Room autoincrement PK
     * (backward-compatible with every existing reference); [uuid] is the STABLE identity that
     * profiles (`profile_prompts.prompt_ref`) and the v3 catalog export use. Backfilled per row by
     * the config-entity migration (§8.5); `""` means "not yet backfilled".
     */
    @ColumnInfo(name = "uuid")
    val uuid: String = "",

    // ── Envelope / provenance (v12) — Double-Enum CHECKs live in MIGRATION_11_12 ──
    @ColumnInfo(name = "visibility")
    val visibility: String = "PRIVATE",

    @ColumnInfo(name = "subscription_mode")
    val subscriptionMode: String = "LOCAL",

    @ColumnInfo(name = "source_peer_id")
    val sourcePeerId: String? = null,

    @ColumnInfo(name = "source_original_id")
    val sourceOriginalId: String? = null,

    @ColumnInfo(name = "source_original_hash")
    val sourceOriginalHash: String? = null,

    /** `contentHash` of the shareable prompt payload (name/text/flags). Backfilled in §8.5. */
    @ColumnInfo(name = "content_hash")
    val contentHash: String = "",

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0
) {
    /**
     * Boundary accessor: parses [type] into a [PromptType], falling back to
     * [PromptType.PROMPT] for values a downgrade/rollback left unknown to this
     * build (Double-Enum convenience accessor, docs/DATABASE-PATTERNS.md).
     */
    val typeEnum: PromptType
        get() = runCatching { PromptType.valueOf(type) }.getOrDefault(PromptType.PROMPT)
}
