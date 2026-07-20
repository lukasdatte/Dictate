package net.devemperor.dictate.companion.data

import net.devemperor.dictate.companion.domain.port.CatalogEntityRepository
import net.devemperor.dictate.companion.domain.port.SharedCredential
import net.devemperor.dictate.companion.domain.port.SharedEntityPayload
import net.devemperor.dictate.shared.config.CanonicalJson
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.Visibility
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import net.devemperor.dictate.shared.protocol.CatalogEntry

/**
 * The catalog view over [CompanionConfigRepository] — SHARED config entities as index rows + canonical
 * payloads (peer-katalog.md §4.2, §10).
 *
 * Built on the D3 config repository rather than raw SQL on purpose: [CompanionConfigRepository] already
 * owns the row⇄DTO translation and the `content_hash` denormalisation, so this stays a thin SHARED
 * filter + a canonical-serialization step. The payload it emits is
 * `CanonicalJson.canonicalString(entity, serializer)` — byte-identical to what `contentHash` was
 * computed over at save time, so a receiver re-hashes it and matches (§6.3).
 *
 * Credentials are enumerated from SHARED provider configs that carry a `credentialRef`: that ref IS the
 * SecretStore id the plaintext resolves under. The secret itself never passes through here — this
 * repository holds no [net.devemperor.dictate.ai.secrets.SecretStore] (F12).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md §4.2, §10
 * @see docs/decisions/0034-peer-catalog.md
 */
class SqlDelightCatalogRepository(
    private val config: CompanionConfigRepository,
) : CatalogEntityRepository {

    override fun sharedEntries(): List<CatalogEntry> = buildList {
        config.providerConfigs().filter { it.isShared() }
            .forEach { add(entry(it.id, CatalogEntityKindWire.PROVIDER_CONFIG, it.contentHash, it.updatedAt, it.label)) }
        config.modelRefs().filter { it.isShared() }
            .forEach { add(entry(it.id, CatalogEntityKindWire.MODEL_REF, it.contentHash, it.updatedAt, it.label ?: it.modelId)) }
        config.prompts().filter { it.isShared() }
            .forEach { add(entry(it.id, CatalogEntityKindWire.PROMPT, it.contentHash, it.updatedAt, it.name)) }
        config.profiles().filter { it.isShared() }
            .forEach { add(entry(it.id, CatalogEntityKindWire.PROFILE, it.contentHash, it.updatedAt, it.name)) }
    }

    override fun sharedEntity(id: String): SharedEntityPayload? {
        config.providerConfig(id)?.takeIf { it.isShared() }?.let {
            return SharedEntityPayload(CatalogEntityKindWire.PROVIDER_CONFIG, it.contentHash, CanonicalJson.canonicalString(it, ProviderConfigEntity.serializer()))
        }
        config.modelRef(id)?.takeIf { it.isShared() }?.let {
            return SharedEntityPayload(CatalogEntityKindWire.MODEL_REF, it.contentHash, CanonicalJson.canonicalString(it, ModelRefEntity.serializer()))
        }
        config.prompt(id)?.takeIf { it.isShared() }?.let {
            return SharedEntityPayload(CatalogEntityKindWire.PROMPT, it.contentHash, CanonicalJson.canonicalString(it, PromptV3Entity.serializer()))
        }
        config.profile(id)?.takeIf { it.isShared() }?.let {
            return SharedEntityPayload(CatalogEntityKindWire.PROFILE, it.contentHash, CanonicalJson.canonicalString(it, ProfileEntity.serializer()))
        }
        return null
    }

    override fun sharedCredentials(): List<SharedCredential> =
        config.providerConfigs()
            .filter { it.isShared() && it.credentialRef != null }
            .map { SharedCredential(id = it.credentialRef!!, provider = it.providerType.name, label = it.label, updatedAt = it.updatedAt) }

    override fun sharedCredential(id: String): SharedCredential? =
        sharedCredentials().firstOrNull { it.id == id }

    private fun entry(id: String, kind: CatalogEntityKindWire, contentHash: String, updatedAt: Long, label: String) =
        CatalogEntry(id = id, kind = kind, contentHash = contentHash, updatedAt = updatedAt, label = label)

    private fun ProviderConfigEntity.isShared() = visibility == Visibility.SHARED
    private fun ModelRefEntity.isShared() = visibility == Visibility.SHARED
    private fun PromptV3Entity.isShared() = visibility == Visibility.SHARED
    private fun ProfileEntity.isShared() = visibility == Visibility.SHARED
}
