package net.devemperor.dictate.companion.catalog

import net.devemperor.dictate.shared.protocol.CatalogEntry

/**
 * The *live* half of the Peer Explorer's state matrix (peer-katalog.md §8.1): the peer's most recent
 * catalog index — "der letzte Index" the CURRENT/UPDATE_AVAILABLE/SOURCE_REMOVED rows compare
 * against. The *stored* half (subscriptions, watermarks, staleness) comes from
 * [net.devemperor.dictate.companion.domain.port.PeerExplorerStore].
 *
 * A port because fetching an index needs a [net.devemperor.dictate.shared.client.CatalogClient]
 * built for the peer — address, device id, SecretStore-resolved secret — which is exactly the
 * credential-touching wiring the [CatalogSyncTargets] seam owns. The production implementation
 * arrives with that adapter (delegated E2 persistence work); the ViewModel tests drive fakes.
 */
fun interface PeerIndexSource {

    /**
     * The peer's current index entries, or null when no index is available (unreachable, never
     * synced, no catalog route). Null is "unknown", never "empty offer" — the matrix treats the two
     * differently (§8.1: SOURCE_REMOVED needs a present index that lacks the entry).
     */
    fun entries(peerId: String): List<CatalogEntry>?
}

/** Triggers one engine run against one peer ("sync-now", §8.1) — the scheduler side of the seam. */
fun interface CatalogSyncRunner {
    fun syncNow(peerId: String)
}

/**
 * Takes over one offered entry from a peer: pull, verify, create the local copy + subscription row
 * (the "subscribe from peer" action of the Explorer/editor tab, §8.3/§9.1). Same seam family as
 * [PeerIndexSource]: the implementation needs the peer's client and the subscriber-store write path,
 * so it ships with the delegated E2 persistence adapter; a null binding disables the action in the UI.
 */
fun interface CatalogSubscriber {
    fun subscribe(peerId: String, entry: net.devemperor.dictate.shared.protocol.CatalogEntry)
}
