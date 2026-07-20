package net.devemperor.dictate.companion.catalog

import net.devemperor.dictate.ai.secrets.SecretRef
import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.companion.ai.CredentialSecrets
import net.devemperor.dictate.companion.data.CompanionConfigRepository
import net.devemperor.dictate.companion.domain.port.PeerExplorerStore
import net.devemperor.dictate.companion.domain.port.PeerRecord
import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.client.CatalogClient
import net.devemperor.dictate.shared.client.DispatchResult
import net.devemperor.dictate.shared.config.CatalogPayloadGraft
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.ProviderType
import net.devemperor.dictate.shared.config.ModelFunction
import net.devemperor.dictate.shared.config.SourceRef
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.config.contentHashOfElement
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import net.devemperor.dictate.shared.protocol.CatalogEntry
import net.devemperor.dictate.shared.sync.CatalogPeer
import net.devemperor.dictate.shared.transport.DispatchTransport
import net.devemperor.dictate.shared.transport.OkHttpDispatchTransport
import kotlinx.serialization.json.Json

/**
 * The SecretStore addressing convention for a peer's pairing secret — the desktop mirror of a device
 * secret, but for the peer WE pair with (we are its HTTP client, §5.1). Parallel to [CredentialSecrets]:
 * the one place that names the `"peer"` namespace, so the write side (pair-redemption) and the read
 * side (the sync client factory) can never drift on the handle format. The Android twin uses the same
 * `"peer"` namespace (peer secrets never cross hosts, but keeping the convention identical avoids a
 * surprise if a store is ever shared in a test).
 */
object PeerSecrets {
    const val PEER_NAMESPACE = "peer"
    fun peerSecretRef(secretRef: String): SecretRef = SecretRef(PEER_NAMESPACE, secretRef)
}

/**
 * Builds a [CatalogClient] + [CatalogPeer] for one stored peer — the credential-touching seam the sync
 * loop, the Explorer's live half and the takeover all share (peer-katalog.md §6.5, §8.1).
 *
 * A peer row carries its address (→ transport), its `device_id` and the `secret_ref` behind which its
 * pairing secret sits in the [SecretStore]. The credentials are read at CALL time (a lambda, not a
 * value) so a re-pair takes effect without rebuilding the client — the same contract the phone's
 * [CatalogClient] documents.
 *
 * [transportFactory] is injectable so a two-peer E2E can point it at a `127.0.0.1:<port>` server; in
 * production it is [OkHttpDispatchTransport] over the peer's tailnet address.
 */
class PeerCatalogClientFactory(
    private val secretStore: SecretStore,
    private val transportFactory: (baseUrl: String) -> DispatchTransport = { OkHttpDispatchTransport(it) },
) {

    fun clientFor(peer: PeerRecord): CatalogClient =
        CatalogClient(transportFactory(baseUrlOf(peer.address))) {
            val secret = secretStore.get(PeerSecrets.peerSecretRef(peer.secretRef))?.decodeToString()
            secret?.let { Credentials(deviceId = peer.deviceId, deviceSecret = it) }
        }

    fun catalogPeer(peer: PeerRecord): CatalogPeer =
        CatalogPeer(peerId = peer.peerId, displayName = peer.displayName, lastRootHash = peer.lastRootHash)

    private companion object {
        /** Match the phone's WindowsPairingActivity: a scheme-less tailnet address defaults to http. */
        fun baseUrlOf(address: String): String =
            if (address.startsWith("http://") || address.startsWith("https://")) address else "http://$address"
    }
}

/**
 * The production [CatalogSyncTargets]: every stored peer as a ready-to-run target (peer-katalog.md §6.5).
 * Enumerated fresh on each tick so an added/removed peer takes effect on the next run.
 */
class PeerStoreCatalogSyncTargets(
    private val peers: PeerExplorerStore,
    private val clients: PeerCatalogClientFactory,
) : CatalogSyncTargets {
    override fun targets(): List<CatalogSyncTarget> =
        peers.peers().map { CatalogSyncTarget(clients.catalogPeer(it), clients.clientFor(it)) }
}

/** The Explorer's live index half (§8.1): the peer's current catalog index, or null when unreachable. */
class CatalogClientPeerIndexSource(
    private val peers: PeerExplorerStore,
    private val clients: PeerCatalogClientFactory,
) : PeerIndexSource {
    override fun entries(peerId: String): List<CatalogEntry>? {
        val peer = peers.peers().firstOrNull { it.peerId == peerId } ?: return null
        return when (val result = clients.clientFor(peer).index()) {
            is DispatchResult.Success -> result.value.entries
            is DispatchResult.Failure -> null
        }
    }
}

/** "Sync now" for one peer (§8.1): drive the shared engine against a single stored peer on demand. */
class EngineCatalogSyncRunner(
    private val peers: PeerExplorerStore,
    private val clients: PeerCatalogClientFactory,
    private val engine: net.devemperor.dictate.shared.sync.CatalogSyncEngine,
) : CatalogSyncRunner {
    override fun syncNow(peerId: String) {
        val peer = peers.peers().firstOrNull { it.peerId == peerId } ?: return
        runCatching { engine.sync(clients.catalogPeer(peer), clients.clientFor(peer)) }
    }
}

/**
 * Takes over one offered entry from a peer (§8.3/§9.1): pull it, verify it exactly as the sync engine
 * does (§6.3), then create the local copy + its `SUBSCRIBE` subscription row.
 *
 * The local copy REUSES the source entity id as its own id — a subscribed copy is a verbatim mirror, so
 * cross-entity references inside a payload (a profile's model refs, a provider's credential ref) keep
 * resolving, and the copy's recomputed `contentHash` equals the source's. Provenance ([SourceRef]) and
 * `subscription_mode = SUBSCRIBE` are the only envelope fields that differ from the source.
 *
 * A credential's plaintext goes straight to the [SecretStore] and never to a column (F12); everything
 * else lands through [CompanionConfigRepository.save] so the hash is recomputed on write.
 */
class TakeoverCatalogSubscriber(
    private val peers: PeerExplorerStore,
    private val clients: PeerCatalogClientFactory,
    private val config: CompanionConfigRepository,
    private val secretStore: SecretStore,
    private val database: net.devemperor.dictate.companion.db.DictateCompanionDb,
    private val clock: () -> Long,
) : CatalogSubscriber {

    private val payloadJson = Json
    private val queries get() = database.companionQueries

    override fun subscribe(peerId: String, entry: CatalogEntry) {
        val peer = peers.peers().firstOrNull { it.peerId == peerId } ?: return
        val client = clients.clientFor(peer)
        val localId = entry.id
        val now = clock()

        if (entry.kind == CatalogEntityKindWire.CREDENTIAL) {
            val response = (client.credential(entry.id) as? DispatchResult.Success)?.value ?: return
            database.transaction {
                secretStore.put(CredentialSecrets.credentialRef(localId), response.secret.toByteArray())
                insertSubscription(localId, peerId, entry, now)
            }
            return
        }

        val response = (client.entity(entry.id) as? DispatchResult.Success)?.value ?: return
        // Verify-before-write, exactly the two checks the engine runs on an update (§6.3): index↔payload
        // and payload↔recompute. A takeover that skipped them would trust an unverified first copy.
        if (response.contentHash != entry.contentHash) return
        val recomputed = runCatching { contentHashOfElement(payloadJson.parseToJsonElement(response.payload)) }.getOrNull()
        if (recomputed != response.contentHash) return

        val source = SourceRef(peerId, entry.id, entry.contentHash)
        database.transaction {
            when (entry.kind) {
                CatalogEntityKindWire.PROVIDER_CONFIG ->
                    config.save(CatalogPayloadGraft.graft(providerTemplate(localId, source), ProviderConfigEntity.serializer(), response.payload))
                CatalogEntityKindWire.MODEL_REF ->
                    config.save(CatalogPayloadGraft.graft(modelTemplate(localId, source), ModelRefEntity.serializer(), response.payload))
                CatalogEntityKindWire.PROMPT ->
                    config.save(CatalogPayloadGraft.graft(promptTemplate(localId, source), PromptV3Entity.serializer(), response.payload))
                CatalogEntityKindWire.PROFILE ->
                    config.save(CatalogPayloadGraft.graft(profileTemplate(localId, source), ProfileEntity.serializer(), response.payload))
                CatalogEntityKindWire.CREDENTIAL, CatalogEntityKindWire.UNKNOWN -> return@transaction
            }
            insertSubscription(localId, peerId, entry, now)
        }
    }

    private fun insertSubscription(localId: String, peerId: String, entry: CatalogEntry, at: Long) =
        queries.insertSubscription(
            localEntityId = localId, peerId = peerId, sourceEntityId = entry.id,
            kind = entry.kind, mode = SubscriptionMode.SUBSCRIBE, lastHash = entry.contentHash, lastCheckedAt = at,
        )

    // Templates carry only the envelope (id + provenance + SUBSCRIBE); every payload field is present in
    // the canonical wire payload (CanonicalJson encodeDefaults=true), so the graft overwrites the
    // placeholders below wholesale — they exist only to satisfy the DTOs' required constructor params.
    private fun providerTemplate(id: String, source: SourceRef) =
        ProviderConfigEntity(id = id, sourceRef = source, subscriptionMode = SubscriptionMode.SUBSCRIBE, providerType = ProviderType.OPENAI, label = "")
    private fun modelTemplate(id: String, source: SourceRef) =
        ModelRefEntity(id = id, sourceRef = source, subscriptionMode = SubscriptionMode.SUBSCRIBE, providerRef = "", modelId = "", function = ModelFunction.COMPLETION)
    private fun promptTemplate(id: String, source: SourceRef) =
        PromptV3Entity(id = id, sourceRef = source, subscriptionMode = SubscriptionMode.SUBSCRIBE, name = "", text = "")
    private fun profileTemplate(id: String, source: SourceRef) =
        ProfileEntity(id = id, sourceRef = source, subscriptionMode = SubscriptionMode.SUBSCRIBE, name = "")
}
