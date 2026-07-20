package net.devemperor.dictate.config

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import net.devemperor.dictate.config.entity.ApiCredentialRoomEntity
import net.devemperor.dictate.config.entity.ModelRefRoomEntity
import net.devemperor.dictate.config.entity.ProfilePromptRoomEntity
import net.devemperor.dictate.config.entity.ProfileRoomEntity
import net.devemperor.dictate.config.entity.ProviderConfigRoomEntity
import net.devemperor.dictate.shared.config.ApiCredentialEntity
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.ProfilePromptRef
import net.devemperor.dictate.shared.config.ProviderConfigEntity

/**
 * The thin, pure Room ⇄ `:shared`-DTO mapper (spec §7.1). Same role as `SessionEntityMapper`:
 * the DTOs are the shareable, hashable form (envelope + payload); the Room classes are the local
 * persistence form (flat columns, enum strings). No I/O, no hashing here — [ConfigRepository] owns
 * the `contentHash`/`updatedAt` recompute (§5.3) and the transactional write.
 *
 * `parameter_defaults`/`parameter_overrides` are stored as a **key-sorted** JSON object so the
 * column value is stable across writes; ordering is irrelevant to the `contentHash` (which is
 * computed over the DTO map, canonicalised recursively by `CanonicalJson`).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §7.1, §7.4
 */
object ConfigEntityMapper {

    private val json = Json { encodeDefaults = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    /** Deterministic (key-sorted) JSON object for a parameter map. */
    fun encodeParams(map: Map<String, String>): String =
        json.encodeToString(mapSerializer, map.toSortedMap())

    fun decodeParams(raw: String): Map<String, String> =
        if (raw.isBlank()) emptyMap() else runCatching { json.decodeFromString(mapSerializer, raw) }.getOrDefault(emptyMap())

    /**
     * The single canonical string form of a decimal parameter value (§8.3 / §10.2): shortest
     * lossless decimal, '.' separator, no exponent, no trailing zeros (e.g. `0.7`, `1`, `1.5`).
     * This string feeds `parameterDefaults`/`parameterOverrides` → `contentHash`, so the migration
     * (`ConfigEntityMigration`) and the settings editor (`ParameterMapEditor`) MUST both route
     * through this one helper — two copies would be a silent hash-divergence hazard.
     */
    fun canonicalDecimal(value: Float): String =
        BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

    // ── ProviderConfig ──────────────────────────────────────────────────────────────────────

    fun toRoom(dto: ProviderConfigEntity): ProviderConfigRoomEntity = ProviderConfigRoomEntity(
        id = dto.id,
        providerType = dto.providerType.name,
        kind = dto.kind.name,
        label = dto.label,
        baseUrl = dto.baseUrl,
        credentialRef = dto.credentialRef,
        visibility = dto.visibility.name,
        subscriptionMode = dto.subscriptionMode.name,
        sourcePeerId = dto.sourceRef?.peerId,
        sourceOriginalId = dto.sourceRef?.originalId,
        sourceOriginalHash = dto.sourceRef?.originalContentHash,
        contentHash = dto.contentHash,
        updatedAt = dto.updatedAt,
    )

    fun toDto(row: ProviderConfigRoomEntity): ProviderConfigEntity = ProviderConfigEntity(
        id = row.id,
        contentHash = row.contentHash,
        updatedAt = row.updatedAt,
        visibility = row.visibilityEnum,
        sourceRef = sourceRefOrNull(row.sourcePeerId, row.sourceOriginalId, row.sourceOriginalHash),
        subscriptionMode = row.subscriptionModeEnum,
        providerType = row.providerTypeEnum,
        kind = row.kindEnum,
        label = row.label,
        baseUrl = row.baseUrl,
        credentialRef = row.credentialRef,
    )

    // ── ApiCredential ───────────────────────────────────────────────────────────────────────

    fun toRoom(dto: ApiCredentialEntity): ApiCredentialRoomEntity = ApiCredentialRoomEntity(
        id = dto.id,
        providerType = dto.providerType.name,
        label = dto.label,
        keyFingerprint = dto.keyFingerprint,
        visibility = dto.visibility.name,
        subscriptionMode = dto.subscriptionMode.name,
        sourcePeerId = dto.sourceRef?.peerId,
        sourceOriginalId = dto.sourceRef?.originalId,
        sourceOriginalHash = dto.sourceRef?.originalContentHash,
        contentHash = dto.contentHash,
        updatedAt = dto.updatedAt,
    )

    fun toDto(row: ApiCredentialRoomEntity): ApiCredentialEntity = ApiCredentialEntity(
        id = row.id,
        contentHash = row.contentHash,
        updatedAt = row.updatedAt,
        visibility = row.visibilityEnum,
        sourceRef = sourceRefOrNull(row.sourcePeerId, row.sourceOriginalId, row.sourceOriginalHash),
        subscriptionMode = row.subscriptionModeEnum,
        providerType = row.providerTypeEnum,
        label = row.label,
        keyFingerprint = row.keyFingerprint,
    )

    // ── ModelRef ────────────────────────────────────────────────────────────────────────────

    fun toRoom(dto: ModelRefEntity): ModelRefRoomEntity = ModelRefRoomEntity(
        id = dto.id,
        providerRef = dto.providerRef,
        modelId = dto.modelId,
        function = dto.function.name,
        label = dto.label,
        parameterDefaults = encodeParams(dto.parameterDefaults),
        visibility = dto.visibility.name,
        subscriptionMode = dto.subscriptionMode.name,
        sourcePeerId = dto.sourceRef?.peerId,
        sourceOriginalId = dto.sourceRef?.originalId,
        sourceOriginalHash = dto.sourceRef?.originalContentHash,
        contentHash = dto.contentHash,
        updatedAt = dto.updatedAt,
    )

    fun toDto(row: ModelRefRoomEntity): ModelRefEntity = ModelRefEntity(
        id = row.id,
        contentHash = row.contentHash,
        updatedAt = row.updatedAt,
        visibility = row.visibilityEnum,
        sourceRef = sourceRefOrNull(row.sourcePeerId, row.sourceOriginalId, row.sourceOriginalHash),
        subscriptionMode = row.subscriptionModeEnum,
        providerRef = row.providerRef,
        modelId = row.modelId,
        function = row.functionEnum,
        label = row.label,
        parameterDefaults = decodeParams(row.parameterDefaults),
    )

    // ── Profile (+ profile_prompts) ─────────────────────────────────────────────────────────

    fun toRoom(dto: ProfileEntity): ProfileRoomEntity = ProfileRoomEntity(
        id = dto.id,
        name = dto.name,
        transcriptionModelRef = dto.transcriptionModelRef,
        completionModelRef = dto.completionModelRef,
        stylePromptMode = dto.stylePromptMode.name,
        stylePromptCustomText = dto.stylePromptCustomText,
        systemPromptMode = dto.systemPromptMode.name,
        systemPromptCustomText = dto.systemPromptCustomText,
        ambiguityMode = dto.ambiguityMode.name,
        parameterOverrides = encodeParams(dto.parameterOverrides),
        visibility = dto.visibility.name,
        subscriptionMode = dto.subscriptionMode.name,
        sourcePeerId = dto.sourceRef?.peerId,
        sourceOriginalId = dto.sourceRef?.originalId,
        sourceOriginalHash = dto.sourceRef?.originalContentHash,
        contentHash = dto.contentHash,
        updatedAt = dto.updatedAt,
    )

    fun profilePromptRows(dto: ProfileEntity): List<ProfilePromptRoomEntity> =
        dto.orderedPrompts.mapIndexed { index, ref ->
            ProfilePromptRoomEntity(
                profileId = dto.id,
                pos = index,
                promptRef = ref.promptRef,
                autoApply = ref.autoApply,
            )
        }

    fun toDto(row: ProfileRoomEntity, prompts: List<ProfilePromptRoomEntity>): ProfileEntity = ProfileEntity(
        id = row.id,
        contentHash = row.contentHash,
        updatedAt = row.updatedAt,
        visibility = row.visibilityEnum,
        sourceRef = sourceRefOrNull(row.sourcePeerId, row.sourceOriginalId, row.sourceOriginalHash),
        subscriptionMode = row.subscriptionModeEnum,
        name = row.name,
        transcriptionModelRef = row.transcriptionModelRef,
        completionModelRef = row.completionModelRef,
        orderedPrompts = prompts.sortedBy { it.pos }.map { ProfilePromptRef(it.promptRef, it.autoApply) },
        stylePromptMode = row.stylePromptModeEnum,
        stylePromptCustomText = row.stylePromptCustomText,
        systemPromptMode = row.systemPromptModeEnum,
        systemPromptCustomText = row.systemPromptCustomText,
        ambiguityMode = row.ambiguityModeEnum,
        parameterOverrides = decodeParams(row.parameterOverrides),
    )
}
