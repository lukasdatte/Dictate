package net.devemperor.dictate.companion.data

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.devemperor.dictate.companion.db.DictateCompanionDb
import net.devemperor.dictate.companion.db.Model_refs
import net.devemperor.dictate.companion.db.Profile_prompts
import net.devemperor.dictate.companion.db.Profiles
import net.devemperor.dictate.companion.db.Prompts
import net.devemperor.dictate.companion.db.Provider_configs
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.ProfilePromptRef
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.SourceRef
import net.devemperor.dictate.shared.config.contentHash

/**
 * Local read/write of the shareable config-entity mirror (desktop-host.md §9, C1 model).
 *
 * The management screens (§9.2) and — from Block E — the catalog sync speak the `:shared.config` DTOs
 * ([ProviderConfigEntity] etc.), NOT the raw SQLDelight rows, so this repository is the one
 * translation seam between the two. It follows C2's `ConfigRepository` contract on the one rule that
 * matters for catalog correctness: **`content_hash` is a denormalised cache recomputed on every write**
 * (§5.3, docs/DATABASE-PATTERNS.md) — an incoming hash is never trusted, so a hand-edited entity and
 * its shared copy always agree byte-for-byte.
 *
 * Provenance (`source_*`, `subscription_mode`) round-trips through [SourceRef]; a locally created
 * entity has none. The FK to `peers` is E1's (Companion.sq header) — until then `source_peer_id` is
 * only ever written NULL from here (local create/edit never sets provenance; that is Block E's job).
 */
class CompanionConfigRepository(
    private val database: DictateCompanionDb,
    private val now: () -> Long,
) {

    private val queries = database.companionQueries

    // ── provider configs ────────────────────────────────────────────────────────────────────────

    fun providerConfigs(): List<ProviderConfigEntity> = queries.allProviderConfigs().executeAsList().map { it.toEntity() }

    fun providerConfig(id: String): ProviderConfigEntity? =
        queries.providerConfigById(id).executeAsOneOrNull()?.toEntity()

    /** Recomputes [ProviderConfigEntity.contentHash] + stamps `updated_at`, then upserts (§5.3). */
    fun save(entity: ProviderConfigEntity): ProviderConfigEntity {
        val stamped = entity.copy(contentHash = contentHash(entity, ProviderConfigEntity.serializer()), updatedAt = now())
        queries.upsertProviderConfig(
            id = stamped.id,
            providerType = stamped.providerType,
            kind = stamped.kind,
            label = stamped.label,
            baseUrl = stamped.baseUrl,
            credentialRef = stamped.credentialRef,
            visibility = stamped.visibility,
            subscriptionMode = stamped.subscriptionMode,
            sourcePeerId = stamped.sourceRef?.peerId,
            sourceOriginalId = stamped.sourceRef?.originalId,
            sourceOriginalHash = stamped.sourceRef?.originalContentHash,
            contentHash = stamped.contentHash,
            updatedAt = stamped.updatedAt,
        )
        return stamped
    }

    fun deleteProviderConfig(id: String) = queries.deleteProviderConfig(id)

    // ── model refs ──────────────────────────────────────────────────────────────────────────────

    fun modelRefs(): List<ModelRefEntity> = queries.allModelRefs().executeAsList().map { it.toEntity() }

    fun modelRef(id: String): ModelRefEntity? = queries.modelRefById(id).executeAsOneOrNull()?.toEntity()

    fun save(entity: ModelRefEntity): ModelRefEntity {
        val stamped = entity.copy(contentHash = contentHash(entity, ModelRefEntity.serializer()), updatedAt = now())
        queries.upsertModelRef(
            id = stamped.id,
            providerRef = stamped.providerRef,
            modelId = stamped.modelId,
            function = stamped.function,
            label = stamped.label,
            parameterDefaults = encodeParams(stamped.parameterDefaults),
            visibility = stamped.visibility,
            subscriptionMode = stamped.subscriptionMode,
            sourcePeerId = stamped.sourceRef?.peerId,
            sourceOriginalId = stamped.sourceRef?.originalId,
            sourceOriginalHash = stamped.sourceRef?.originalContentHash,
            contentHash = stamped.contentHash,
            updatedAt = stamped.updatedAt,
        )
        return stamped
    }

    fun deleteModelRef(id: String) = queries.deleteModelRef(id)

    // ── prompts ─────────────────────────────────────────────────────────────────────────────────

    fun prompts(): List<PromptV3Entity> = queries.allPrompts().executeAsList().map { it.toEntity() }

    fun prompt(id: String): PromptV3Entity? = queries.promptById(id).executeAsOneOrNull()?.toEntity()

    fun save(entity: PromptV3Entity): PromptV3Entity {
        val stamped = entity.copy(contentHash = contentHash(entity, PromptV3Entity.serializer()), updatedAt = now())
        queries.upsertPrompt(
            id = stamped.id,
            name = stamped.name,
            text = stamped.text,
            requiresSelection = stamped.requiresSelection,
            autoApply = stamped.autoApply,
            visibility = stamped.visibility,
            subscriptionMode = stamped.subscriptionMode,
            sourcePeerId = stamped.sourceRef?.peerId,
            sourceOriginalId = stamped.sourceRef?.originalId,
            sourceOriginalHash = stamped.sourceRef?.originalContentHash,
            contentHash = stamped.contentHash,
            updatedAt = stamped.updatedAt,
        )
        return stamped
    }

    fun deletePrompt(id: String) = queries.deletePrompt(id)

    // ── profiles (+ ordered prompts) ──────────────────────────────────────────────────────────────

    fun profiles(): List<ProfileEntity> = queries.allProfiles().executeAsList().map { profile ->
        profile.toEntity(queries.promptsForProfile(profile.id).executeAsList())
    }

    fun profile(id: String): ProfileEntity? = queries.profileById(id).executeAsOneOrNull()?.let { profile ->
        profile.toEntity(queries.promptsForProfile(profile.id).executeAsList())
    }

    /** Upserts a profile and its ordered prompt list in one transaction (order is hash-significant, §5.1). */
    fun save(entity: ProfileEntity): ProfileEntity {
        val stamped = entity.copy(contentHash = contentHash(entity, ProfileEntity.serializer()), updatedAt = now())
        database.transaction {
            queries.upsertProfile(
                id = stamped.id,
                name = stamped.name,
                transcriptionModelRef = stamped.transcriptionModelRef,
                completionModelRef = stamped.completionModelRef,
                stylePromptMode = stamped.stylePromptMode,
                stylePromptCustomText = stamped.stylePromptCustomText,
                systemPromptMode = stamped.systemPromptMode,
                systemPromptCustomText = stamped.systemPromptCustomText,
                ambiguityMode = stamped.ambiguityMode,
                parameterOverrides = encodeParams(stamped.parameterOverrides),
                visibility = stamped.visibility,
                subscriptionMode = stamped.subscriptionMode,
                sourcePeerId = stamped.sourceRef?.peerId,
                sourceOriginalId = stamped.sourceRef?.originalId,
                sourceOriginalHash = stamped.sourceRef?.originalContentHash,
                contentHash = stamped.contentHash,
                updatedAt = stamped.updatedAt,
            )
            queries.deleteProfilePrompts(stamped.id)
            stamped.orderedPrompts.forEachIndexed { pos, ref ->
                queries.insertProfilePrompt(
                    profileId = stamped.id,
                    pos = pos.toLong(),
                    promptRef = ref.promptRef,
                    autoApply = ref.autoApply,
                )
            }
        }
        return stamped
    }

    fun deleteProfile(id: String) = queries.deleteProfile(id)

    // ── row ⇄ DTO ─────────────────────────────────────────────────────────────────────────────

    private fun Provider_configs.toEntity() = ProviderConfigEntity(
        id = id,
        contentHash = content_hash,
        updatedAt = updated_at,
        visibility = visibility,
        sourceRef = sourceRef(source_peer_id, source_original_id, source_original_hash),
        subscriptionMode = subscription_mode,
        providerType = provider_type,
        kind = kind,
        label = label,
        baseUrl = base_url,
        credentialRef = credential_ref,
    )

    private fun Model_refs.toEntity() = ModelRefEntity(
        id = id,
        contentHash = content_hash,
        updatedAt = updated_at,
        visibility = visibility,
        sourceRef = sourceRef(source_peer_id, source_original_id, source_original_hash),
        subscriptionMode = subscription_mode,
        providerRef = provider_ref,
        modelId = model_id,
        function = function,
        label = label,
        parameterDefaults = decodeParams(parameter_defaults),
    )

    private fun Prompts.toEntity() = PromptV3Entity(
        id = id,
        contentHash = content_hash,
        updatedAt = updated_at,
        visibility = visibility,
        sourceRef = sourceRef(source_peer_id, source_original_id, source_original_hash),
        subscriptionMode = subscription_mode,
        name = name,
        text = text,
        requiresSelection = requires_selection,
        autoApply = auto_apply,
    )

    private fun Profiles.toEntity(prompts: List<Profile_prompts>) = ProfileEntity(
        id = id,
        contentHash = content_hash,
        updatedAt = updated_at,
        visibility = visibility,
        sourceRef = sourceRef(source_peer_id, source_original_id, source_original_hash),
        subscriptionMode = subscription_mode,
        name = name,
        transcriptionModelRef = transcription_model_ref,
        completionModelRef = completion_model_ref,
        orderedPrompts = prompts.sortedBy { it.pos }.map { ProfilePromptRef(promptRef = it.prompt_ref, autoApply = it.auto_apply) },
        stylePromptMode = style_prompt_mode,
        stylePromptCustomText = style_prompt_custom_text,
        systemPromptMode = system_prompt_mode,
        systemPromptCustomText = system_prompt_custom_text,
        ambiguityMode = ambiguity_mode,
        parameterOverrides = decodeParams(parameter_overrides),
    )

    private fun sourceRef(peerId: String?, originalId: String?, originalHash: String?): SourceRef? =
        if (peerId != null && originalId != null && originalHash != null) SourceRef(peerId, originalId, originalHash) else null

    private fun encodeParams(map: Map<String, String>): String =
        Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), map)

    private fun decodeParams(raw: String): Map<String, String> =
        runCatching { Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw) }.getOrDefault(emptyMap())
}
