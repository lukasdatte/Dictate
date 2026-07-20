package net.devemperor.dictate.companion.data

import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.companion.ai.CredentialSecrets
import net.devemperor.dictate.companion.db.DictateCompanionDb
import net.devemperor.dictate.shared.config.CatalogPayloadGraft
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import net.devemperor.dictate.shared.sync.CatalogSubscriberStore
import net.devemperor.dictate.shared.sync.CatalogSubscriptionRef
import net.devemperor.dictate.shared.sync.VerifiedCredentialUpdate
import net.devemperor.dictate.shared.sync.VerifiedEntityUpdate

/**
 * The companion's [CatalogSubscriberStore] — the write path the shared [net.devemperor.dictate.shared.sync.CatalogSyncEngine]
 * drives on this host (peer-katalog.md §6, §5.1/§5.3).
 *
 * Built on [CompanionConfigRepository] for every entity write (the twin of [SqlDelightPeerExplorerStore]),
 * because that repository owns the row⇄DTO mapping AND the `content_hash` recompute-on-write rule — so a
 * pulled copy's stored hash is derived from its canonical bytes, never trusted from the wire a second
 * time. The `peers`/`subscriptions` bookkeeping goes through raw queries here.
 *
 * ## Fork protection is the query (AC8)
 *
 * [activeSubscriptions] selects only `mode = 'SUBSCRIBE'`. A fork DELETED its subscription row (and set
 * the entity `subscription_mode = 'LOCAL'`) in one transaction (§5.3); a `ONE_SHOT` copy never had a
 * SUBSCRIBE row. So neither is ever handed to the engine and no run can overwrite them — the engine
 * contains no fork test, it simply never sees them.
 *
 * ## Why a graft, not a raw write
 *
 * [applyEntityUpdate] rebuilds the local copy via [CatalogPayloadGraft]: the verified, envelope-stripped
 * payload replaces the copy's payload half while its LOCAL envelope (id, provenance, `subscription_mode
 * = SUBSCRIBE`) is kept, then [CompanionConfigRepository.save] recomputes the hash (== the verified
 * source hash, because the hash is payload-only) and advances the subscription watermark in the SAME
 * transaction. A credential never touches an entity column — its plaintext goes straight to the
 * [SecretStore] and only the subscription watermark moves (F12, §6.2).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md §6
 * @see docs/decisions/0034-peer-catalog.md
 */
class SqlDelightCatalogSubscriberStore(
    private val database: DictateCompanionDb,
    private val config: CompanionConfigRepository,
    private val secretStore: SecretStore,
) : CatalogSubscriberStore {

    private val queries = database.companionQueries

    override fun activeSubscriptions(peerId: String): List<CatalogSubscriptionRef> =
        queries.activeSubscriptionsForPeer(peerId).executeAsList().map { row ->
            CatalogSubscriptionRef(
                localEntityId = row.local_entity_id,
                sourceEntityId = row.source_entity_id,
                kind = row.kind,
                lastHash = row.last_hash,
            )
        }

    override fun recordContact(peerId: String, at: Long) {
        queries.recordPeerContact(at, peerId)
    }

    override fun recordSuccess(peerId: String, at: Long, rootHash: String?) {
        queries.recordPeerSuccess(at = at, rootHash = rootHash, peerId = peerId)
    }

    override fun applyEntityUpdate(update: VerifiedEntityUpdate) {
        database.transaction {
            when (update.kind) {
                CatalogEntityKindWire.PROVIDER_CONFIG ->
                    config.providerConfig(update.localEntityId)?.let {
                        config.save(CatalogPayloadGraft.graft(it, ProviderConfigEntity.serializer(), update.payload))
                    }
                CatalogEntityKindWire.MODEL_REF ->
                    config.modelRef(update.localEntityId)?.let {
                        config.save(CatalogPayloadGraft.graft(it, ModelRefEntity.serializer(), update.payload))
                    }
                CatalogEntityKindWire.PROMPT ->
                    config.prompt(update.localEntityId)?.let {
                        config.save(CatalogPayloadGraft.graft(it, PromptV3Entity.serializer(), update.payload))
                    }
                CatalogEntityKindWire.PROFILE ->
                    config.profile(update.localEntityId)?.let {
                        config.save(CatalogPayloadGraft.graft(it, ProfileEntity.serializer(), update.payload))
                    }
                // A credential is never an entity update (the engine routes it to applyCredentialUpdate);
                // UNKNOWN is a kind a newer provider introduced that this build cannot materialize.
                CatalogEntityKindWire.CREDENTIAL, CatalogEntityKindWire.UNKNOWN -> Unit
            }
            queries.advanceSubscription(lastHash = update.contentHash, at = update.at, localEntityId = update.localEntityId)
        }
    }

    override fun applyCredentialUpdate(update: VerifiedCredentialUpdate) {
        database.transaction {
            // The plaintext goes ONLY to the SecretStore, addressed by the local copy's id — never a
            // column (F12). The companion keeps no api_credentials mirror (SqlDelightPeerExplorerStore
            // doc), so the subscription watermark is the whole persistent trace of the credential.
            secretStore.put(CredentialSecrets.credentialRef(update.localEntityId), update.secret.toByteArray())
            queries.advanceSubscription(lastHash = update.contentHash, at = update.at, localEntityId = update.localEntityId)
        }
    }

    override fun markSourceRemoved(localEntityId: String, at: Long) {
        queries.touchSubscriptionChecked(at, localEntityId)
    }
}
