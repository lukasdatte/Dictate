package net.devemperor.dictate.config

import net.devemperor.dictate.database.entity.PromptEntity
import java.util.UUID

/**
 * Keeps the v12 shareable-identity columns of `prompts` rows correct at the Android write seams
 * (spec §7.3/§8.5): every insert gets a stable `uuid` + recomputed `content_hash`, every content
 * edit re-hashes WITHOUT losing the row's uuid/provenance (the historical 7-arg constructor would
 * silently reset them). Mirrors `ConfigRepository`'s recompute-on-write invariant (§5.3) for the
 * legacy Room prompt table.
 */
object PromptProvenance {

    /** [row] with a guaranteed uuid, a recomputed content hash, and a fresh write timestamp. */
    @JvmStatic
    @JvmOverloads
    fun stamped(row: PromptEntity, now: Long = System.currentTimeMillis()): PromptEntity {
        val uuid = row.uuid.ifEmpty { UUID.randomUUID().toString() }
        val withId = row.copy(uuid = uuid, updatedAt = now)
        return withId.copy(contentHash = PromptHashing.contentHashOf(uuid, withId))
    }

    /**
     * Content edit of [existing]: new name/text/flags/type, envelope (uuid, provenance) carried
     * over, hash recomputed. For the PromptEditActivity update path.
     */
    @JvmStatic
    fun edited(
        existing: PromptEntity,
        name: String,
        prompt: String,
        requiresSelection: Boolean,
        autoApply: Boolean,
        type: String,
    ): PromptEntity = stamped(
        existing.copy(
            name = name,
            prompt = prompt,
            requiresSelection = requiresSelection,
            autoApply = autoApply,
            type = type,
        ),
    )

    /** A duplicate is a NEW local prompt: fresh uuid, no peer provenance. */
    @JvmStatic
    fun localCopy(row: PromptEntity): PromptEntity = stamped(
        row.copy(
            uuid = "",
            sourcePeerId = null,
            sourceOriginalId = null,
            sourceOriginalHash = null,
            visibility = "PRIVATE",
            subscriptionMode = "LOCAL",
        ),
    )
}
