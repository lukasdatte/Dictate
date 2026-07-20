package net.devemperor.dictate.companion.data

import net.devemperor.dictate.companion.db.DictateCompanionDb
import net.devemperor.dictate.companion.domain.port.PeerExplorerStore
import net.devemperor.dictate.companion.domain.port.PeerRecord
import net.devemperor.dictate.companion.domain.port.SubscribedCopy
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire

/**
 * [PeerExplorerStore] over the `peers`/`subscriptions` tables plus the entity copies' provenance
 * columns (peer-katalog.md §8, §5.1/§5.3).
 *
 * Built on [CompanionConfigRepository] for everything that touches an entity row — labels and the
 * fork's `subscription_mode` flip — because that repository owns the row⇄DTO mapping and the
 * `content_hash` invariant (the same reason [SqlDelightCatalogRepository] rides on it). Flipping the
 * mode through `save()` cannot change the hash: `subscriptionMode` is an envelope field, excluded
 * from canonical serialization (`CanonicalJson`), so a fork is provenance surgery, never a content
 * edit.
 *
 * ## What a "copy from peer X" is, concretely
 *
 * The union of two queries (§8.1): the peer's `subscriptions` rows (live `SUBSCRIBE`/`ONE_SHOT`
 * bindings, joined with their entity for the label) — plus the entities whose `source_peer_id`
 * points at the peer while their mode is `LOCAL`: the forks, which by design no longer have a
 * subscription row (§5.3) but still belong in the Explorer as `FORKED`.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md §8
 * @see docs/decisions/0034-peer-catalog.md
 */
class SqlDelightPeerExplorerStore(
    private val database: DictateCompanionDb,
    private val config: CompanionConfigRepository,
) : PeerExplorerStore {

    private val queries = database.companionQueries

    override fun peers(): List<PeerRecord> =
        queries.allPeers().executeAsList().map { row ->
            PeerRecord(
                peerId = row.peer_id,
                displayName = row.display_name,
                address = row.address,
                deviceId = row.device_id,
                secretRef = row.secret_ref,
                addedAt = row.added_at,
                lastContactAt = row.last_contact_at,
                lastSuccessAt = row.last_success_at,
                lastRootHash = row.last_root_hash,
            )
        }

    override fun addPeer(peer: PeerRecord) {
        queries.insertPeer(
            peerId = peer.peerId,
            displayName = peer.displayName,
            address = peer.address,
            deviceId = peer.deviceId,
            secretRef = peer.secretRef,
            addedAt = peer.addedAt,
        )
    }

    override fun copiesFrom(peerId: String): List<SubscribedCopy> {
        val subscribed = queries.subscriptionsForPeer(peerId).executeAsList().map { row ->
            SubscribedCopy(
                localEntityId = row.local_entity_id,
                sourceEntityId = row.source_entity_id,
                kind = row.kind,
                mode = row.mode,
                label = labelOf(row.local_entity_id, row.kind) ?: row.source_entity_id,
                lastHash = row.last_hash,
                lastCheckedAt = row.last_checked_at,
            )
        }
        return subscribed + forksFrom(peerId, alreadyListed = subscribed.mapTo(HashSet()) { it.localEntityId })
    }

    override fun unsubscribe(localEntityId: String) {
        queries.deleteSubscription(localEntityId)
    }

    override fun fork(localEntityId: String, kind: CatalogEntityKindWire) {
        database.transaction {
            when (kind) {
                CatalogEntityKindWire.PROVIDER_CONFIG ->
                    config.providerConfig(localEntityId)?.let { config.save(it.copy(subscriptionMode = SubscriptionMode.LOCAL)) }
                CatalogEntityKindWire.MODEL_REF ->
                    config.modelRef(localEntityId)?.let { config.save(it.copy(subscriptionMode = SubscriptionMode.LOCAL)) }
                CatalogEntityKindWire.PROMPT ->
                    config.prompt(localEntityId)?.let { config.save(it.copy(subscriptionMode = SubscriptionMode.LOCAL)) }
                CatalogEntityKindWire.PROFILE ->
                    config.profile(localEntityId)?.let { config.save(it.copy(subscriptionMode = SubscriptionMode.LOCAL)) }
                // A credential copy has no entity row on the companion (the value lives in the
                // SecretStore, F12) — forking it is dropping the binding, nothing more.
                CatalogEntityKindWire.CREDENTIAL, CatalogEntityKindWire.UNKNOWN -> Unit
            }
            queries.deleteSubscription(localEntityId)
        }
    }

    // ── internals ───────────────────────────────────────────────────────────────────────────────

    /** Forked copies of [peerId]: provenance points there, mode is LOCAL, no subscription row left. */
    private fun forksFrom(peerId: String, alreadyListed: Set<String>): List<SubscribedCopy> = buildList {
        config.providerConfigs().forEach {
            if (it.sourceRef?.peerId == peerId && it.subscriptionMode == SubscriptionMode.LOCAL && it.id !in alreadyListed) {
                add(fork(it.id, it.sourceRef!!.originalId, CatalogEntityKindWire.PROVIDER_CONFIG, it.label, it.contentHash))
            }
        }
        config.modelRefs().forEach {
            if (it.sourceRef?.peerId == peerId && it.subscriptionMode == SubscriptionMode.LOCAL && it.id !in alreadyListed) {
                add(fork(it.id, it.sourceRef!!.originalId, CatalogEntityKindWire.MODEL_REF, it.label ?: it.modelId, it.contentHash))
            }
        }
        config.prompts().forEach {
            if (it.sourceRef?.peerId == peerId && it.subscriptionMode == SubscriptionMode.LOCAL && it.id !in alreadyListed) {
                add(fork(it.id, it.sourceRef!!.originalId, CatalogEntityKindWire.PROMPT, it.name, it.contentHash))
            }
        }
        config.profiles().forEach {
            if (it.sourceRef?.peerId == peerId && it.subscriptionMode == SubscriptionMode.LOCAL && it.id !in alreadyListed) {
                add(fork(it.id, it.sourceRef!!.originalId, CatalogEntityKindWire.PROFILE, it.name, it.contentHash))
            }
        }
    }

    private fun fork(id: String, sourceId: String, kind: CatalogEntityKindWire, label: String, hash: String) =
        SubscribedCopy(
            localEntityId = id,
            sourceEntityId = sourceId,
            kind = kind,
            mode = SubscriptionMode.LOCAL,
            label = label,
            lastHash = hash,
            lastCheckedAt = null,
        )

    private fun labelOf(localEntityId: String, kind: CatalogEntityKindWire): String? = when (kind) {
        CatalogEntityKindWire.PROVIDER_CONFIG -> config.providerConfig(localEntityId)?.label
        CatalogEntityKindWire.MODEL_REF -> config.modelRef(localEntityId)?.let { it.label ?: it.modelId }
        CatalogEntityKindWire.PROMPT -> config.prompt(localEntityId)?.name
        CatalogEntityKindWire.PROFILE -> config.profile(localEntityId)?.name
        CatalogEntityKindWire.CREDENTIAL, CatalogEntityKindWire.UNKNOWN -> null
    }
}
