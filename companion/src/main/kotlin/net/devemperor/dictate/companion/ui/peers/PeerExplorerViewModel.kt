package net.devemperor.dictate.companion.ui.peers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.devemperor.dictate.companion.catalog.CatalogSubscriber
import net.devemperor.dictate.companion.catalog.CatalogSyncRunner
import net.devemperor.dictate.companion.catalog.PeerIndexSource
import net.devemperor.dictate.companion.catalog.discovery.PeerCandidate
import net.devemperor.dictate.companion.catalog.discovery.PeerDiscovery
import net.devemperor.dictate.companion.domain.port.PeerExplorerStore
import net.devemperor.dictate.companion.domain.port.PeerRecord
import net.devemperor.dictate.companion.domain.port.SubscribedCopy
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.protocol.CatalogEntry

/**
 * Reachability of a peer, derived at render time from `last_success_at` + threshold (spec D4:
 * "kein persistierter Zustand, der von der Realität driften kann").
 */
enum class PeerStatus { OK, STALE, UNREACHABLE }

/** The §8.1 state matrix — one derived state per subscribed/forked copy. Never persisted. */
enum class CopyState { CURRENT, UPDATE_AVAILABLE, FORKED, STALE, SOURCE_REMOVED }

/** One peer row of the list screen: the stored record plus its derived reachability. */
data class PeerRow(val record: PeerRecord, val status: PeerStatus)

/** One copy row of the detail screen: the stored copy plus its derived §8.1 state. */
data class CopyRow(val copy: SubscribedCopy, val state: CopyState)

data class PeerExplorerUiState(
    val peers: List<PeerRow> = emptyList(),
    /** The peer the detail pane shows, or null for the bare list. */
    val selectedPeerId: String? = null,
    val copies: List<CopyRow> = emptyList(),
    /** What the selected peer *offers* right now (its live index), for the subscribe-from-peer tab. */
    val availableFromPeer: List<CatalogEntry> = emptyList(),
    /** Discovery results for the "Add peer" flow; empty outside a tailnet (AC11). */
    val candidates: List<PeerCandidate> = emptyList(),
) {
    val selectedPeer: PeerRow? get() = peers.firstOrNull { it.record.peerId == selectedPeerId }
}

/**
 * The Peer Explorer's brain (peer-katalog.md §8) — a plain class with a [StateFlow], no Compose
 * (the [net.devemperor.dictate.companion.ui.history.HistoryViewModel] precedent), so the §8.1 state
 * matrix is testable without rendering anything (AC13).
 *
 * ## How the matrix is derived (§8.1)
 *
 * Per copy, first match wins:
 *
 * 1. `FORKED` — the entity's mode is `LOCAL` while its provenance still names the peer. A fork is a
 *    property of the copy alone; no index or reachability can change it.
 * 2. `STALE` — the peer's `last_success_at` is older than [staleAfterMillis] (or the peer never
 *    answered). Without a fresh word from the peer every per-copy comparison would be against
 *    yesterday's truth, so staleness masks the index-derived states.
 * 3. `SOURCE_REMOVED` — the peer's current index no longer carries the source entity: deleted or
 *    un-shared upstream. The copy stays (§6.4); the state is the warning label.
 * 4. `UPDATE_AVAILABLE` / `CURRENT` — the index entry's `contentHash` against the subscription's
 *    `last_hash` watermark.
 *
 * When the peer is fresh but no index is obtainable *right now* (transient — e.g. the very first
 * look before any sync), the copies fall back to the watermark-only verdict: `CURRENT`, because
 * nothing says otherwise and a scare-label on every cold open would cry wolf.
 *
 * [indexSource] and [syncRunner] are the seams to the credential-touching client wiring (see
 * [PeerIndexSource]); [discovery] feeds the "Add peer" candidates. The [scope] is injected so tests
 * run on `Dispatchers.Unconfined` and assert on the next line.
 *
 * @see docs/decisions/0034-peer-catalog.md
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md §8
 */
class PeerExplorerViewModel(
    private val store: PeerExplorerStore,
    private val indexSource: PeerIndexSource,
    private val discovery: PeerDiscovery,
    private val syncRunner: CatalogSyncRunner?,
    private val subscriber: CatalogSubscriber?,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
    /**
     * Where every blocking, credential/network-touching seam runs: [PeerDiscovery.discover] (execs a
     * subprocess), [PeerIndexSource.entries] (fetches a peer's live index over the wire),
     * [CatalogSyncRunner.syncNow] (one engine run) and [CatalogSubscriber.subscribe] (pull + verify +
     * write). Their contracts forbid the UI thread — while [scope] in production IS the UI scope (the
     * house pattern for the cheap local-DB loads, [store]). Tests override with `Unconfined` to stay
     * inline.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _state = MutableStateFlow(PeerExplorerUiState())
    val state: StateFlow<PeerExplorerUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = scope.launch { load(_state.value.selectedPeerId) }

    fun selectPeer(peerId: String?) = scope.launch { load(peerId) }

    /** One engine run against this peer, then re-derive — the §8.1 sync-now action. */
    fun syncNow(peerId: String) = scope.launch {
        withContext(ioDispatcher) { syncRunner?.syncNow(peerId) }
        load(_state.value.selectedPeerId)
    }

    /** Drop the binding, keep the frozen copy (§8.1). */
    fun unsubscribe(localEntityId: String) = scope.launch {
        store.unsubscribe(localEntityId)
        load(_state.value.selectedPeerId)
    }

    /** Make the copy local and editable; every future sync run is blind to it (§5.3, AC8). */
    fun fork(copy: SubscribedCopy) = scope.launch {
        store.fork(copy.localEntityId, copy.kind)
        load(_state.value.selectedPeerId)
    }

    /** Whether the subscribe-from-peer action is wired (see [CatalogSubscriber]); drives the UI's enabling. */
    val canSubscribe: Boolean get() = subscriber != null

    /** Take over one offered entry from the selected peer ("Von Peer beziehen", §8.3). */
    fun subscribe(entry: CatalogEntry) = scope.launch {
        val peerId = _state.value.selectedPeerId ?: return@launch
        withContext(ioDispatcher) { subscriber?.subscribe(peerId, entry) }
        load(peerId)
    }

    /** Populate [PeerExplorerUiState.candidates] from discovery — empty off-tailnet, never an error (AC11). */
    fun discoverCandidates() = scope.launch {
        val candidates = withContext(ioDispatcher) { discovery.discover() }
        _state.value = _state.value.copy(candidates = candidates)
    }

    // ── derivation ──────────────────────────────────────────────────────────────────────────────

    private suspend fun load(selectedPeerId: String?) {
        val now = clock()
        val peers = store.peers().map { PeerRow(it, status(it, now)) }
        val selected = peers.firstOrNull { it.record.peerId == selectedPeerId }

        // The live index is a wire fetch (credential-touching); hop off the UI scope. The local-DB
        // reads (store.peers/copiesFrom) stay inline per the house pattern.
        val index: List<CatalogEntry>? = selected?.let { peer ->
            withContext(ioDispatcher) { indexSource.entries(peer.record.peerId) }
        }
        val copies = selected?.let { peer ->
            store.copiesFrom(peer.record.peerId).map { copy -> CopyRow(copy, state(copy, peer.status, index)) }
        } ?: emptyList()

        _state.value = _state.value.copy(
            peers = peers,
            selectedPeerId = selected?.record?.peerId,
            copies = copies,
            availableFromPeer = index.orEmpty(),
        )
    }

    private fun status(peer: PeerRecord, now: Long): PeerStatus = when {
        peer.lastSuccessAt == null -> PeerStatus.UNREACHABLE
        now - peer.lastSuccessAt > staleAfterMillis -> PeerStatus.STALE
        else -> PeerStatus.OK
    }

    private fun state(copy: SubscribedCopy, peerStatus: PeerStatus, index: List<CatalogEntry>?): CopyState {
        if (copy.mode == SubscriptionMode.LOCAL) return CopyState.FORKED
        if (peerStatus != PeerStatus.OK) return CopyState.STALE
        if (index == null) return CopyState.CURRENT // fresh peer, no index in hand — see class doc
        val entry = index.firstOrNull { it.id == copy.sourceEntityId } ?: return CopyState.SOURCE_REMOVED
        return if (entry.contentHash == copy.lastHash) CopyState.CURRENT else CopyState.UPDATE_AVAILABLE
    }

    companion object {
        /**
         * Six times the default sync interval's order of magnitude — generous enough that a laptop
         * peer asleep over lunch is not yelled about, tight enough that "since yesterday" shows.
         */
        const val DEFAULT_STALE_AFTER_MILLIS: Long = 6 * 60 * 60 * 1000L
    }
}
