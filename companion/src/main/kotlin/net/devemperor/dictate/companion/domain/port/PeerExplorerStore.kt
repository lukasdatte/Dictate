package net.devemperor.dictate.companion.domain.port

import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire

/**
 * The Peer Explorer's view of the local `peers` / `subscriptions` tables plus the provenance columns
 * of the entity copies (peer-katalog.md §8) — what the consumer side *stored*, not what the peer
 * currently offers (that live half comes from
 * [net.devemperor.dictate.companion.catalog.PeerIndexSource]).
 *
 * A port for the same reason [HistoryRepository] is one: the ViewModel derives the §8.1 state matrix
 * from these rows and must be testable against a fake. The production impl is
 * [net.devemperor.dictate.companion.data.SqlDelightPeerExplorerStore].
 *
 * ## The two Explorer actions that mutate
 *
 * - [unsubscribe] deletes the subscription row and nothing else: the copy stays, frozen at its last
 *   synced content, still marked as originating from the peer (§8.1 "Kopie bleibt eingefroren").
 * - [fork] deletes the subscription row AND flips the copy's `subscription_mode` to `LOCAL` in one
 *   transaction (§5.3): the copy becomes locally editable and — because the sync query selects only
 *   `SUBSCRIBE` rows — permanently invisible to every future sync run (AC8, spec D3).
 */
interface PeerExplorerStore {

    /** Every known peer, stable order. */
    fun peers(): List<PeerRecord>

    /** Add a peer after a successful §9.1 pairing. The caller has already stored the secret. */
    fun addPeer(peer: PeerRecord)

    /** All copies originating from [peerId]: subscription rows joined with their local entity row. */
    fun copiesFrom(peerId: String): List<SubscribedCopy>

    /** Delete the subscription row of [localEntityId]; the local copy is untouched. */
    fun unsubscribe(localEntityId: String)

    /**
     * Fork the copy [localEntityId] of [kind]: subscription row deleted + entity
     * `subscription_mode = 'LOCAL'`, atomically. No-op when no such copy exists.
     */
    fun fork(localEntityId: String, kind: CatalogEntityKindWire)
}

/** One row of `peers` (§5.1). Staleness is DERIVED from [lastSuccessAt] in the ViewModel, never stored. */
data class PeerRecord(
    val peerId: String,
    val displayName: String,
    /** MagicDNS name + port, e.g. `heim-pc.tail1234.ts.net:8756`. */
    val address: String,
    /** Our pairing identity FOR this peer; the secret sits in the SecretStore under [secretRef]. */
    val deviceId: String,
    val secretRef: String,
    val addedAt: Long,
    val lastContactAt: Long?,
    val lastSuccessAt: Long?,
    val lastRootHash: String?,
)

/**
 * One subscribed (or forked) local copy as the Explorer shows it: the subscription row's identity
 * and watermark, plus the entity row's display fields.
 *
 * [mode] is the copy's `subscription_mode` — `SUBSCRIBE`/`ONE_SHOT` for live bindings (which have a
 * subscription row, per the §5.3 CHECK), `LOCAL` for a fork that still carries its `source_peer_id`
 * provenance (the §8.1 `FORKED` state). Only a FORK has no subscription row left, so for it
 * [lastHash]/[lastCheckedAt] are the entity's own content hash and null respectively.
 */
data class SubscribedCopy(
    val localEntityId: String,
    val sourceEntityId: String,
    val kind: CatalogEntityKindWire,
    val mode: SubscriptionMode,
    val label: String,
    /** The watermark the last sync landed on (subscription `last_hash`; entity hash for forks). */
    val lastHash: String,
    val lastCheckedAt: Long?,
)
