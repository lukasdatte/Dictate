package net.devemperor.dictate.peers

import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.config.CatalogImport
import net.devemperor.dictate.config.ConfigEntityMapper
import net.devemperor.dictate.config.ConfigRepository
import net.devemperor.dictate.config.ConfigSecrets
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.shared.config.CatalogPayloadGraft
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.SourceRef
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.config.Visibility
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import net.devemperor.dictate.shared.sync.CatalogSubscriberStore
import net.devemperor.dictate.shared.sync.CatalogSubscriptionRef
import net.devemperor.dictate.shared.sync.VerifiedCredentialUpdate
import net.devemperor.dictate.shared.sync.VerifiedEntityUpdate
import java.security.MessageDigest

/**
 * The phone's [CatalogSubscriberStore] — the write path the shared
 * [net.devemperor.dictate.shared.sync.CatalogSyncEngine] drives on Android (peer-katalog.md §6, §5).
 *
 * The engine is pure and platform-free (ADR-0015); this is its Room adapter, the twin of the
 * companion's `SqlDelightCatalogSubscriberStore`. It leans on [ConfigRepository] for every entity
 * write (so `content_hash` is recomputed on write, never trusted from the wire) and reuses
 * [CatalogImport.upsertPromptRow] for the prompt kind — the exact same row⇄DTO path a v3 file import
 * takes, so the two receive paths cannot drift.
 *
 * ## Fork protection is the query (AC8)
 *
 * [activeSubscriptions] maps only `mode = 'SUBSCRIBE'` rows ([SubscriptionDao.activeForPeer]). A fork
 * deleted its subscription row (and set the entity `subscription_mode = 'LOCAL'`); a `ONE_SHOT` copy
 * never had a SUBSCRIBE row. Neither is ever handed to the engine.
 *
 * ## Graft, not raw write
 *
 * [applyEntityUpdate] rebuilds the local copy via [CatalogPayloadGraft]: the verified, envelope-stripped
 * payload replaces the copy's payload half while its LOCAL envelope (id, provenance, `SUBSCRIBE`) is
 * kept; the repository then recomputes the hash. A credential's plaintext goes only to the
 * [SecretStore], never a column (F12, §6.2).
 */
class AndroidCatalogSubscriberStore(
    private val db: DictateDatabase,
    private val secretStore: SecretStore,
    private val repo: ConfigRepository = ConfigRepository(db),
) : CatalogSubscriberStore {

    private val peers get() = db.peerDao()
    private val subs get() = db.subscriptionDao()

    override fun activeSubscriptions(peerId: String): List<CatalogSubscriptionRef> =
        subs.activeForPeer(peerId).map { row ->
            CatalogSubscriptionRef(
                localEntityId = row.localEntityId,
                sourceEntityId = row.sourceEntityId,
                kind = row.kindEnum,
                lastHash = row.lastHash,
            )
        }

    override fun recordContact(peerId: String, at: Long) = peers.recordContact(peerId, at)

    override fun recordSuccess(peerId: String, at: Long, rootHash: String?) = peers.recordSuccess(peerId, at, rootHash)

    override fun applyEntityUpdate(update: VerifiedEntityUpdate) {
        db.runInTransaction {
            when (update.kind) {
                CatalogEntityKindWire.PROVIDER_CONFIG ->
                    db.providerConfigDao().byId(update.localEntityId)?.let { row ->
                        repo.upsertProviderConfig(CatalogPayloadGraft.graft(ConfigEntityMapper.toDto(row), ProviderConfigEntity.serializer(), update.payload))
                    }
                CatalogEntityKindWire.MODEL_REF ->
                    db.modelRefDao().byId(update.localEntityId)?.let { row ->
                        repo.upsertModelRef(CatalogPayloadGraft.graft(ConfigEntityMapper.toDto(row), ModelRefEntity.serializer(), update.payload))
                    }
                CatalogEntityKindWire.PROFILE ->
                    db.profileDao().byId(update.localEntityId)?.let { row ->
                        val existing = ConfigEntityMapper.toDto(row, db.profileDao().promptsOf(update.localEntityId))
                        repo.upsertProfile(CatalogPayloadGraft.graft(existing, ProfileEntity.serializer(), update.payload))
                    }
                CatalogEntityKindWire.PROMPT ->
                    promptDtoByUuid(update.localEntityId)?.let { existing ->
                        CatalogImport.upsertPromptRow(db, repo.clock, CatalogPayloadGraft.graft(existing, PromptV3Entity.serializer(), update.payload))
                    }
                // A credential is never an entity update (the engine routes it to applyCredentialUpdate);
                // UNKNOWN is a kind a newer provider introduced that this build cannot materialize.
                CatalogEntityKindWire.CREDENTIAL, CatalogEntityKindWire.UNKNOWN -> Unit
            }
            subs.advance(update.localEntityId, update.contentHash, update.at)
        }
    }

    override fun applyCredentialUpdate(update: VerifiedCredentialUpdate) {
        db.runInTransaction {
            // The plaintext goes ONLY to the SecretStore, addressed by the local copy's id (F12, §6.2).
            secretStore.put(ConfigSecrets.credentialRef(update.localEntityId), update.secret.toByteArray())
            // Refresh the api_credentials mirror's fingerprint if a copy row exists (never the key).
            db.apiCredentialDao().byId(update.localEntityId)?.let { row ->
                repo.upsertCredential(ConfigEntityMapper.toDto(row).copy(label = update.label, keyFingerprint = fingerprintOf(update.secret)))
            }
            subs.advance(update.localEntityId, update.contentHash, update.at)
        }
    }

    override fun markSourceRemoved(localEntityId: String, at: Long) = subs.touchChecked(localEntityId, at)

    /** Reconstruct the [PromptV3Entity] envelope of the Android `prompts` row identified by [uuid]. */
    private fun promptDtoByUuid(uuid: String): PromptV3Entity? {
        val row = db.promptDao().getAll().firstOrNull { it.uuid == uuid } ?: return null
        return PromptV3Entity(
            id = row.uuid,
            contentHash = row.contentHash,
            updatedAt = row.updatedAt,
            visibility = runCatching { Visibility.valueOf(row.visibility) }.getOrDefault(Visibility.PRIVATE),
            sourceRef = if (row.sourcePeerId != null && row.sourceOriginalId != null && row.sourceOriginalHash != null)
                SourceRef(row.sourcePeerId!!, row.sourceOriginalId!!, row.sourceOriginalHash!!) else null,
            subscriptionMode = runCatching { SubscriptionMode.valueOf(row.subscriptionMode) }.getOrDefault(SubscriptionMode.LOCAL),
            name = row.name.orEmpty(),
            text = row.prompt.orEmpty(),
            requiresSelection = row.requiresSelection,
            autoApply = row.autoApply,
        )
    }

    /** The credential fingerprint stored in the `api_credentials` mirror: `sha256(key)`-hex, first 16. */
    private fun fingerprintOf(secret: String): String =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .take(16)
}
