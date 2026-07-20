package net.devemperor.dictate.config.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.devemperor.dictate.shared.config.ModelFunction
import net.devemperor.dictate.shared.config.ProviderKind
import net.devemperor.dictate.shared.config.ProviderType
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.config.Visibility

/**
 * Room persistence classes for the five shareable config entities (spec §7).
 *
 * The `:shared` DTOs ([net.devemperor.dictate.shared.config.ProviderConfigEntity] etc.) are NOT
 * Room entities — Room cannot annotate `:shared` classes and the envelope/payload split does not
 * map onto `@Entity`. So each entity gets its own Room class here plus a thin [ConfigEntityMapper]
 * ⇄ DTO — the same pattern the codebase uses for `SessionEntity` ⇄ domain (spec §7.1, Plan-D3).
 *
 * All finite-set columns store the enum `name()` as `String` and expose a `xxxEnum` convenience
 * accessor with a `getOrDefault` fallback — the **Double-Enum** rule (docs/DATABASE-PATTERNS.md);
 * the matching SQL `CHECK` constraints live in [net.devemperor.dictate.database.migration.MIGRATION_11_12].
 *
 * ## No key value ever (F12)
 * [ApiCredentialRoomEntity] holds only a `key_fingerprint`, never the key. The clear-text key lives
 * only in the SecretStore (Block B) under `SecretRef("credential", id)`.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §7.2, §7.3
 */

@Entity(tableName = "provider_configs")
data class ProviderConfigRoomEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "provider_type") val providerType: String,
    @ColumnInfo(name = "kind") val kind: String = ProviderKind.LOCAL.name,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "base_url") val baseUrl: String? = null,
    @ColumnInfo(name = "credential_ref") val credentialRef: String? = null,
    // ── Envelope / provenance ──
    @ColumnInfo(name = "visibility") val visibility: String = Visibility.PRIVATE.name,
    @ColumnInfo(name = "subscription_mode") val subscriptionMode: String = SubscriptionMode.LOCAL.name,
    @ColumnInfo(name = "source_peer_id") val sourcePeerId: String? = null,
    @ColumnInfo(name = "source_original_id") val sourceOriginalId: String? = null,
    @ColumnInfo(name = "source_original_hash") val sourceOriginalHash: String? = null,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    val providerTypeEnum: ProviderType
        get() = runCatching { ProviderType.valueOf(providerType) }.getOrDefault(ProviderType.OPENAI)
    val kindEnum: ProviderKind
        get() = runCatching { ProviderKind.valueOf(kind) }.getOrDefault(ProviderKind.LOCAL)
    val visibilityEnum: Visibility
        get() = runCatching { Visibility.valueOf(visibility) }.getOrDefault(Visibility.PRIVATE)
    val subscriptionModeEnum: SubscriptionMode
        get() = runCatching { SubscriptionMode.valueOf(subscriptionMode) }.getOrDefault(SubscriptionMode.LOCAL)
}

@Entity(tableName = "api_credentials")
data class ApiCredentialRoomEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "provider_type") val providerType: String,
    @ColumnInfo(name = "label") val label: String,
    // Fingerprint only — never the key (F12). The clear-text key lives in the SecretStore.
    @ColumnInfo(name = "key_fingerprint") val keyFingerprint: String,
    // ── Envelope / provenance ──
    @ColumnInfo(name = "visibility") val visibility: String = Visibility.PRIVATE.name,
    @ColumnInfo(name = "subscription_mode") val subscriptionMode: String = SubscriptionMode.LOCAL.name,
    @ColumnInfo(name = "source_peer_id") val sourcePeerId: String? = null,
    @ColumnInfo(name = "source_original_id") val sourceOriginalId: String? = null,
    @ColumnInfo(name = "source_original_hash") val sourceOriginalHash: String? = null,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    val providerTypeEnum: ProviderType
        get() = runCatching { ProviderType.valueOf(providerType) }.getOrDefault(ProviderType.OPENAI)
    val visibilityEnum: Visibility
        get() = runCatching { Visibility.valueOf(visibility) }.getOrDefault(Visibility.PRIVATE)
    val subscriptionModeEnum: SubscriptionMode
        get() = runCatching { SubscriptionMode.valueOf(subscriptionMode) }.getOrDefault(SubscriptionMode.LOCAL)
}

@Entity(
    tableName = "model_refs",
    indices = [Index(value = ["provider_ref"], name = "index_model_refs_provider_ref")],
)
data class ModelRefRoomEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "provider_ref") val providerRef: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "function") val function: String,
    @ColumnInfo(name = "label") val label: String? = null,
    /** Canonical JSON (Map<String,String>) of default parameters — see [ConfigEntityMapper]. */
    @ColumnInfo(name = "parameter_defaults") val parameterDefaults: String = "{}",
    // ── Envelope / provenance ──
    @ColumnInfo(name = "visibility") val visibility: String = Visibility.PRIVATE.name,
    @ColumnInfo(name = "subscription_mode") val subscriptionMode: String = SubscriptionMode.LOCAL.name,
    @ColumnInfo(name = "source_peer_id") val sourcePeerId: String? = null,
    @ColumnInfo(name = "source_original_id") val sourceOriginalId: String? = null,
    @ColumnInfo(name = "source_original_hash") val sourceOriginalHash: String? = null,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    val functionEnum: ModelFunction
        get() = runCatching { ModelFunction.valueOf(function) }.getOrDefault(ModelFunction.COMPLETION)
    val visibilityEnum: Visibility
        get() = runCatching { Visibility.valueOf(visibility) }.getOrDefault(Visibility.PRIVATE)
    val subscriptionModeEnum: SubscriptionMode
        get() = runCatching { SubscriptionMode.valueOf(subscriptionMode) }.getOrDefault(SubscriptionMode.LOCAL)
}

@Entity(tableName = "profiles")
data class ProfileRoomEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "transcription_model_ref") val transcriptionModelRef: String? = null,
    @ColumnInfo(name = "completion_model_ref") val completionModelRef: String? = null,
    @ColumnInfo(name = "style_prompt_mode") val stylePromptMode: String = "PREDEFINED",
    @ColumnInfo(name = "style_prompt_custom_text") val stylePromptCustomText: String = "",
    @ColumnInfo(name = "system_prompt_mode") val systemPromptMode: String = "PREDEFINED",
    @ColumnInfo(name = "system_prompt_custom_text") val systemPromptCustomText: String = "",
    @ColumnInfo(name = "ambiguity_mode") val ambiguityMode: String = "ALWAYS_INSERT",
    /** Canonical JSON (Map<String,String>) of completion parameter overrides. */
    @ColumnInfo(name = "parameter_overrides") val parameterOverrides: String = "{}",
    // ── Envelope / provenance ──
    @ColumnInfo(name = "visibility") val visibility: String = Visibility.PRIVATE.name,
    @ColumnInfo(name = "subscription_mode") val subscriptionMode: String = SubscriptionMode.LOCAL.name,
    @ColumnInfo(name = "source_peer_id") val sourcePeerId: String? = null,
    @ColumnInfo(name = "source_original_id") val sourceOriginalId: String? = null,
    @ColumnInfo(name = "source_original_hash") val sourceOriginalHash: String? = null,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    val visibilityEnum: Visibility
        get() = runCatching { Visibility.valueOf(visibility) }.getOrDefault(Visibility.PRIVATE)
    val subscriptionModeEnum: SubscriptionMode
        get() = runCatching { SubscriptionMode.valueOf(subscriptionMode) }.getOrDefault(SubscriptionMode.LOCAL)
}

/**
 * The ordered post-processing prompts of a [ProfileRoomEntity]. A separate row-per-position table
 * (not a JSON column) so the ordering + auto-apply flags stay first-class and queryable; the
 * `(profile_id, pos)` primary key pins the order and the CASCADE FK drops the rows with the profile.
 */
@Entity(
    tableName = "profile_prompts",
    primaryKeys = ["profile_id", "pos"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileRoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["prompt_ref"], name = "index_profile_prompts_prompt_ref")],
)
data class ProfilePromptRoomEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "pos") val pos: Int,
    @ColumnInfo(name = "prompt_ref") val promptRef: String,
    @ColumnInfo(name = "auto_apply") val autoApply: Boolean = false,
)
