package net.devemperor.dictate.companion.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.devemperor.dictate.companion.catalog.CatalogSubscriber
import net.devemperor.dictate.companion.catalog.CatalogSyncRunner
import net.devemperor.dictate.companion.catalog.PeerIndexSource
import net.devemperor.dictate.companion.catalog.discovery.NoopPeerDiscovery
import net.devemperor.dictate.companion.catalog.discovery.PeerCandidate
import net.devemperor.dictate.companion.catalog.discovery.PeerDiscovery
import net.devemperor.dictate.companion.domain.port.PeerExplorerStore
import net.devemperor.dictate.companion.domain.port.PeerRecord
import net.devemperor.dictate.companion.domain.port.SubscribedCopy
import net.devemperor.dictate.companion.ui.peers.CopyState
import net.devemperor.dictate.companion.ui.peers.PeerExplorerViewModel
import net.devemperor.dictate.companion.ui.peers.PeerStatus
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import net.devemperor.dictate.shared.protocol.CatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC13 (peer-katalog.md §2): the §8.1 state matrix — CURRENT / UPDATE_AVAILABLE / FORKED / STALE /
 * SOURCE_REMOVED — plus the peer-status derivation (D4: derived, never stored) and the Explorer's
 * actions, all without Compose (the `HistoryViewModelTest` pattern; `Dispatchers.Unconfined` runs
 * every `launch` inline so state is readable on the next line).
 */
class PeerExplorerViewModelTest {

    private val store = FakePeerExplorerStore()
    private var index: List<CatalogEntry>? = null
    private var now = HOUR // > 0 so "one minute ago" timestamps stay positive
    private val syncedPeers = mutableListOf<String>()
    private val subscribed = mutableListOf<Pair<String, CatalogEntry>>()

    private fun viewModel(
        discovery: PeerDiscovery = NoopPeerDiscovery,
        subscriber: CatalogSubscriber? = CatalogSubscriber { peerId, entry -> subscribed += peerId to entry },
    ) = PeerExplorerViewModel(
        store = store,
        indexSource = PeerIndexSource { index },
        discovery = discovery,
        syncRunner = CatalogSyncRunner { syncedPeers += it },
        subscriber = subscriber,
        scope = CoroutineScope(Dispatchers.Unconfined),
        clock = { now },
        staleAfterMillis = HOUR,
    )

    // ── the §8.1 matrix (AC13) ──────────────────────────────────────────────────────────────────

    @Test
    fun current_whenIndexHashMatchesWatermark() {
        store.addPeer(peer("p1", lastSuccessAt = now))
        store.subscriptions += copy("local-a", "src-a", lastHash = "h1")
        index = listOf(entry("src-a", "h1"))

        val vm = viewModel().also { it.selectPeer("p1") }

        assertEquals(CopyState.CURRENT, vm.state.value.copies.single().state)
    }

    @Test
    fun updateAvailable_whenIndexHashMoved() {
        store.addPeer(peer("p1", lastSuccessAt = now))
        store.subscriptions += copy("local-a", "src-a", lastHash = "h1")
        index = listOf(entry("src-a", "h2"))

        val vm = viewModel().also { it.selectPeer("p1") }

        assertEquals(CopyState.UPDATE_AVAILABLE, vm.state.value.copies.single().state)
    }

    @Test
    fun forked_whenModeIsLocal_regardlessOfIndex() {
        store.addPeer(peer("p1", lastSuccessAt = now))
        store.subscriptions += copy("local-a", "src-a", lastHash = "h1", mode = SubscriptionMode.LOCAL)
        // Even a moved upstream hash must not re-label a fork — the copy left the sync world (§5.3).
        index = listOf(entry("src-a", "h2"))

        val vm = viewModel().also { it.selectPeer("p1") }

        assertEquals(CopyState.FORKED, vm.state.value.copies.single().state)
    }

    @Test
    fun stale_whenPeerLastSuccessOlderThanThreshold() {
        store.addPeer(peer("p1", lastSuccessAt = now))
        store.subscriptions += copy("local-a", "src-a", lastHash = "h1")
        index = listOf(entry("src-a", "h2"))
        now += HOUR + 1 // threshold passed → the index-derived states are masked by staleness

        val vm = viewModel().also { it.selectPeer("p1") }

        assertEquals(CopyState.STALE, vm.state.value.copies.single().state)
        assertEquals(PeerStatus.STALE, vm.state.value.selectedPeer!!.status)
    }

    @Test
    fun sourceRemoved_whenEntryAbsentFromCurrentIndex() {
        store.addPeer(peer("p1", lastSuccessAt = now))
        store.subscriptions += copy("local-a", "src-a", lastHash = "h1")
        index = listOf(entry("src-other", "hx")) // present index, our entry gone (deleted/un-shared)

        val vm = viewModel().also { it.selectPeer("p1") }

        assertEquals(CopyState.SOURCE_REMOVED, vm.state.value.copies.single().state)
    }

    @Test
    fun current_whenPeerFreshButNoIndexObtainable() {
        store.addPeer(peer("p1", lastSuccessAt = now))
        store.subscriptions += copy("local-a", "src-a", lastHash = "h1")
        index = null // transient: nothing fetched yet — no scare label (class doc)

        val vm = viewModel().also { it.selectPeer("p1") }

        assertEquals(CopyState.CURRENT, vm.state.value.copies.single().state)
    }

    // ── peer status (§8.1 list, D4) ─────────────────────────────────────────────────────────────

    @Test
    fun peerStatus_okStaleUnreachable() {
        store.addPeer(peer("ok", lastSuccessAt = now))
        store.addPeer(peer("stale", lastSuccessAt = now - HOUR - 1))
        store.addPeer(peer("never", lastSuccessAt = null))

        val statuses = viewModel().state.value.peers.associate { it.record.peerId to it.status }

        assertEquals(PeerStatus.OK, statuses["ok"])
        assertEquals(PeerStatus.STALE, statuses["stale"])
        assertEquals(PeerStatus.UNREACHABLE, statuses["never"])
    }

    // ── actions ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun syncNow_runsTheRunner_andRefreshes() {
        store.addPeer(peer("p1", lastSuccessAt = now))

        viewModel().syncNow("p1")

        assertEquals(listOf("p1"), syncedPeers)
    }

    @Test
    fun unsubscribe_deletesTheSubscription_copyRowStays() {
        store.addPeer(peer("p1", lastSuccessAt = now))
        store.subscriptions += copy("local-a", "src-a", lastHash = "h1")
        val vm = viewModel().also { it.selectPeer("p1") }

        vm.unsubscribe("local-a")

        assertEquals(listOf("local-a"), store.unsubscribedIds)
    }

    @Test
    fun fork_delegatesToTheStore_withTheCopysKind() {
        store.addPeer(peer("p1", lastSuccessAt = now))
        val theCopy = copy("local-a", "src-a", lastHash = "h1", kind = CatalogEntityKindWire.PROFILE)
        store.subscriptions += theCopy
        val vm = viewModel().also { it.selectPeer("p1") }

        vm.fork(theCopy)

        assertEquals(listOf("local-a" to CatalogEntityKindWire.PROFILE), store.forked)
    }

    @Test
    fun subscribe_targetsTheSelectedPeer() {
        store.addPeer(peer("p1", lastSuccessAt = now))
        index = listOf(entry("src-new", "h9"))
        val vm = viewModel().also { it.selectPeer("p1") }

        vm.subscribe(entry("src-new", "h9"))

        assertEquals(listOf("p1" to entry("src-new", "h9")), subscribed)
    }

    @Test
    fun subscribe_withoutWiredSeam_isDisabledAndInert() {
        store.addPeer(peer("p1", lastSuccessAt = now))
        val vm = viewModel(subscriber = null).also { it.selectPeer("p1") }

        assertFalse(vm.canSubscribe)
        vm.subscribe(entry("src-new", "h9")) // must not throw
        assertTrue(subscribed.isEmpty())
    }

    // ── discovery into the Add-peer flow (AC11's UI half) ───────────────────────────────────────

    @Test
    fun discoverCandidates_populatesState_andEmptyMeansEmpty() {
        val candidate = PeerCandidate("heim-pc.tail1.ts.net", "100.64.0.7")
        val vm = viewModel(discovery = { listOf(candidate) })
        vm.discoverCandidates()
        assertEquals(listOf(candidate), vm.state.value.candidates)

        val noopVm = viewModel(discovery = NoopPeerDiscovery)
        noopVm.discoverCandidates()
        assertTrue(noopVm.state.value.candidates.isEmpty())
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun peer(id: String, lastSuccessAt: Long?) = PeerRecord(
        peerId = id,
        displayName = id,
        address = "$id.tail1.ts.net:8756",
        deviceId = "dev-$id",
        secretRef = "peer/$id",
        addedAt = 0,
        lastContactAt = lastSuccessAt,
        lastSuccessAt = lastSuccessAt,
        lastRootHash = null,
    )

    private fun copy(
        localId: String,
        sourceId: String,
        lastHash: String,
        mode: SubscriptionMode = SubscriptionMode.SUBSCRIBE,
        kind: CatalogEntityKindWire = CatalogEntityKindWire.PROMPT,
    ) = SubscribedCopy(
        localEntityId = localId,
        sourceEntityId = sourceId,
        kind = kind,
        mode = mode,
        label = localId,
        lastHash = lastHash,
        lastCheckedAt = null,
    )

    private fun entry(id: String, hash: String) = CatalogEntry(
        id = id,
        kind = CatalogEntityKindWire.PROMPT,
        contentHash = hash,
        updatedAt = 0,
        label = id,
    )

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}

/** In-memory [PeerExplorerStore]: lists are the state, mutations are recorded for assertion. */
private class FakePeerExplorerStore : PeerExplorerStore {
    private val peerList = mutableListOf<PeerRecord>()
    val subscriptions = mutableListOf<SubscribedCopy>()
    val unsubscribedIds = mutableListOf<String>()
    val forked = mutableListOf<Pair<String, CatalogEntityKindWire>>()

    override fun peers(): List<PeerRecord> = peerList.toList()
    override fun addPeer(peer: PeerRecord) { peerList += peer }
    override fun copiesFrom(peerId: String): List<SubscribedCopy> = subscriptions.toList()
    override fun unsubscribe(localEntityId: String) {
        unsubscribedIds += localEntityId
        subscriptions.removeAll { it.localEntityId == localEntityId }
    }
    override fun fork(localEntityId: String, kind: CatalogEntityKindWire) {
        forked += localEntityId to kind
        subscriptions.replaceAll {
            if (it.localEntityId == localEntityId) it.copy(mode = SubscriptionMode.LOCAL) else it
        }
    }
}
