package net.devemperor.dictate.config

import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.shared.config.ApiCredentialEntity
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.contentHash

/**
 * The **single write path** for config entities (spec §7.4, §5.3). Every create/edit/import/migration
 * goes through here so two invariants hold by construction:
 *
 * 1. `content_hash = contentHash(payload)` — recomputed on every write, never trusted from the
 *    caller (denormalised cache, docs/DATABASE-PATTERNS.md).
 * 2. `updated_at = clock()` — a monotone write timestamp.
 *
 * A profile plus its `profile_prompts` rows are written in one transaction so the ordered-prompt
 * list can never be observed half-updated (§7.4).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §7.4, §5.3
 */
class ConfigRepository(
    private val db: DictateDatabase,
    /** Write-timestamp source; exposed so the config-entity migration stamps prompts with the same clock. */
    val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Persists [dto] with a freshly computed hash + timestamp; returns the stored form. */
    fun upsertProviderConfig(dto: ProviderConfigEntity): ProviderConfigEntity {
        val stamped = dto.copy(
            contentHash = contentHash(dto, ProviderConfigEntity.serializer()),
            updatedAt = clock(),
        )
        db.providerConfigDao().upsert(ConfigEntityMapper.toRoom(stamped))
        return stamped
    }

    fun upsertCredential(dto: ApiCredentialEntity): ApiCredentialEntity {
        val stamped = dto.copy(
            contentHash = contentHash(dto, ApiCredentialEntity.serializer()),
            updatedAt = clock(),
        )
        db.apiCredentialDao().upsert(ConfigEntityMapper.toRoom(stamped))
        return stamped
    }

    fun upsertModelRef(dto: ModelRefEntity): ModelRefEntity {
        val stamped = dto.copy(
            contentHash = contentHash(dto, ModelRefEntity.serializer()),
            updatedAt = clock(),
        )
        db.modelRefDao().upsert(ConfigEntityMapper.toRoom(stamped))
        return stamped
    }

    /** Writes the profile and replaces its ordered `profile_prompts` rows atomically. */
    fun upsertProfile(dto: ProfileEntity): ProfileEntity {
        val stamped = dto.copy(
            contentHash = contentHash(dto, ProfileEntity.serializer()),
            updatedAt = clock(),
        )
        db.runInTransaction {
            val dao = db.profileDao()
            dao.upsertProfile(ConfigEntityMapper.toRoom(stamped))
            dao.deleteProfilePrompts(stamped.id)
            dao.insertProfilePrompts(ConfigEntityMapper.profilePromptRows(stamped))
        }
        return stamped
    }
}
