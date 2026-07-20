package net.devemperor.dictate.companion.catalog.discovery

/**
 * Enumerates *candidate* peers on the local tailnet — machines that might run a companion — for the
 * "Add peer" flow of the Peer Explorer (peer-katalog.md §9.2, F26).
 *
 * A port, so the UI depends on "give me candidates" and not on how they are found: the production
 * binding is [TailscalePeerDiscovery] (the `tailscale` CLI), the fallback and the tests use
 * [NoopPeerDiscovery]. Discovery is strictly *suggestive*: a candidate becomes a real peer only
 * after a health probe (`supportsCatalog == true`) and the normal pairing hand-shake (§9.1) — this
 * port performs neither, so nothing that comes out of it is trusted with anything.
 *
 * Blocking (the Tailscale impl runs a subprocess); call it from a background coroutine, never the
 * UI thread.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md §9.2
 */
fun interface PeerDiscovery {

    /** Candidate machines, best-effort. Empty when the environment has no discovery source (AC11). */
    fun discover(): List<PeerCandidate>
}

/**
 * One machine the discovery saw: its stable MagicDNS name (the address a `peers.address` should use,
 * §5.1 — it survives IP churn) and its current tailnet IP as a fallback/diagnostic.
 */
data class PeerCandidate(
    val magicDnsName: String,
    val address: String,
)

/**
 * The non-Tailscale environment (and test) binding: no candidates, ever. The Explorer then shows
 * only the manual address+code path (§9.1) — discovery degrades to nothing, it never blocks adding
 * a peer by hand.
 */
object NoopPeerDiscovery : PeerDiscovery {
    override fun discover(): List<PeerCandidate> = emptyList()
}
