package net.devemperor.dictate.config

import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.shared.config.contentHash

/**
 * Computes the shareable `content_hash` of an Android `prompts` row (spec §8.5).
 *
 * The hash is taken over the [PromptV3Entity] **payload** projection — name/text/flags — NOT over
 * the Android-only pill `type` (ADR-0024): a TEXT pill is a literal snippet, never a shareable AI
 * prompt (§13 D3), so `type` is deliberately outside the shared identity. `CanonicalJson` strips the
 * envelope (`id`/`updatedAt`/…) before hashing, so the uuid passed here does not affect the result.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §8.5
 */
object PromptHashing {

    fun contentHashOf(uuid: String, row: PromptEntity): String =
        contentHash(
            PromptV3Entity(
                id = uuid,
                name = row.name.orEmpty(),
                text = row.prompt.orEmpty(),
                requiresSelection = row.requiresSelection,
                autoApply = row.autoApply,
            ),
            PromptV3Entity.serializer(),
        )
}
