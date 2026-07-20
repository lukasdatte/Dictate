package net.devemperor.dictate.companion.catalog

import net.devemperor.dictate.companion.catalog.discovery.NoopPeerDiscovery
import net.devemperor.dictate.companion.catalog.discovery.PeerCandidate
import net.devemperor.dictate.companion.catalog.discovery.TailscalePeerDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC11 (peer-katalog.md §2): fixture `tailscale status --json` parses to candidates; a missing CLI
 * (null output), garbage output, or a Noop environment yields the empty list — never a crash.
 */
class TailscalePeerDiscoveryTest {

    @Test
    fun discover_parsesOnlinePeersFromStatusJson() {
        val discovery = TailscalePeerDiscovery { FIXTURE }

        val candidates = discovery.discover()

        assertEquals(
            listOf(
                PeerCandidate(magicDnsName = "buero-pc.tail1234.ts.net", address = "100.101.102.103"),
                PeerCandidate(magicDnsName = "heim-pc.tail1234.ts.net", address = "100.64.0.7"),
            ),
            candidates,
        )
    }

    @Test
    fun discover_skipsOfflinePeers_andPeersWithoutDnsName() {
        val discovery = TailscalePeerDiscovery { FIXTURE }

        val names = discovery.discover().map { it.magicDnsName }

        assertTrue("offline peer must be skipped", names.none { it.startsWith("laptop") })
        assertTrue("nameless peer must be skipped", "" !in names)
    }

    @Test
    fun discover_cliAbsent_returnsEmptyList() {
        val discovery = TailscalePeerDiscovery { null }

        assertEquals(emptyList<PeerCandidate>(), discovery.discover())
    }

    @Test
    fun discover_unparsableOutput_returnsEmptyList() {
        val discovery = TailscalePeerDiscovery { "tailscale is stopped." }

        assertEquals(emptyList<PeerCandidate>(), discovery.discover())
    }

    @Test
    fun discover_jsonWithoutPeerMap_returnsEmptyList() {
        val discovery = TailscalePeerDiscovery { """{"Version":"1.60.0","BackendState":"NeedsLogin"}""" }

        assertEquals(emptyList<PeerCandidate>(), discovery.discover())
    }

    // 15s ceiling: the production exec self-bounds to TIMEOUT_SECONDS (5s) + destroyForcibly, so a
    // hung `tailscale` on the CI box surfaces as a test failure here, never an unbounded CI stall.
    @Test(timeout = 15_000)
    fun realCliBinding_neverThrows_evenWhereTailscaleIsMissing() {
        // The production default execs the actual binary. Whatever this machine has installed, the
        // contract is "candidates or empty" — an exception here would crash the Add-peer flow (AC11).
        TailscalePeerDiscovery().discover()
    }

    @Test
    fun noop_returnsEmptyList() {
        assertEquals(emptyList<PeerCandidate>(), NoopPeerDiscovery.discover())
    }

    private companion object {
        /** Trimmed real-shape `tailscale status --json`: PascalCase keys, FQDN dot, extra fields. */
        val FIXTURE = """
            {
              "Version": "1.60.0",
              "BackendState": "Running",
              "Self": {"DNSName": "this-pc.tail1234.ts.net.", "TailscaleIPs": ["100.64.0.1"], "Online": true},
              "Peer": {
                "nodekey:aaaa": {
                  "DNSName": "heim-pc.tail1234.ts.net.",
                  "TailscaleIPs": ["100.64.0.7", "fd7a:115c:a1e0::7"],
                  "Online": true,
                  "OS": "windows"
                },
                "nodekey:bbbb": {
                  "DNSName": "laptop.tail1234.ts.net.",
                  "TailscaleIPs": ["100.64.0.9"],
                  "Online": false
                },
                "nodekey:cccc": {
                  "DNSName": "buero-pc.tail1234.ts.net.",
                  "TailscaleIPs": ["100.101.102.103"],
                  "Online": true
                },
                "nodekey:dddd": {
                  "TailscaleIPs": ["100.64.0.11"],
                  "Online": true
                }
              }
            }
        """.trimIndent()
    }
}
