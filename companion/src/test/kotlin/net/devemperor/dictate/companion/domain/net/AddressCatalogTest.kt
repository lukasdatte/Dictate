package net.devemperor.dictate.companion.domain.net

import net.devemperor.dictate.companion.domain.port.NetworkAdapter
import net.devemperor.dictate.companion.domain.port.NetworkInterfaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bind-address brain, driven entirely by a fake [NetworkInterfaces] — no real NIC in sight.
 *
 * The `fun interface` is why every case is a one-liner: hand it the interfaces the scenario needs
 * and assert what the catalogue and [AddressCatalog.resolve] make of them. Covers classification,
 * priority, the `advertised in hosts` invariant, the auto-heal rule (and its ambiguity guard), and
 * the loopback fallback that replaces ADR-0017 §3's `0.0.0.0` widening.
 */
class AddressCatalogTest {

    private fun catalogOf(vararg adapters: NetworkAdapter) = AddressCatalog { adapters.toList() }

    private fun tailscale(addr: String = "100.66.155.18") = NetworkAdapter("tailscale0", listOf(addr), isLoopback = false)
    private fun lan(addr: String = "192.168.1.42") = NetworkAdapter("enp3s0", listOf(addr), isLoopback = false)
    private fun loopback() = NetworkAdapter("lo", listOf("127.0.0.1"), isLoopback = true)

    // ── Classification ──────────────────────────────────────────────────────────────────

    @Test
    fun cgnatIsTailscale_loopbackFlagIsLoopback_restIsLan() {
        val catalogue = catalogOf(tailscale(), lan(), loopback()).enumerate()

        assertEquals(AddressKind.TAILSCALE, catalogue.first { it.address == "100.66.155.18" }.kind)
        assertEquals(AddressKind.LAN, catalogue.first { it.address == "192.168.1.42" }.kind)
        assertEquals(AddressKind.LOOPBACK, catalogue.first { it.address == "127.0.0.1" }.kind)
    }

    @Test
    fun cgnatBoundaries_matchTheTailscaleRange() {
        // The grenzfälle that used to live in AdvertisedAddressTest, now on the classifier.
        fun kindOf(addr: String) = catalogOf(NetworkAdapter("x", listOf(addr), false)).enumerate().first().kind

        assertEquals(AddressKind.TAILSCALE, kindOf("100.64.0.0"))
        assertEquals(AddressKind.TAILSCALE, kindOf("100.127.255.255"))
        assertEquals(AddressKind.LAN, kindOf("100.63.255.255"))
        assertEquals(AddressKind.LAN, kindOf("100.128.0.0"))
        assertEquals(AddressKind.LAN, kindOf("10.0.0.1"))
    }

    @Test
    fun enumerate_sortsTailscaleFirstLoopbackLast_andDedupesByAddress() {
        val catalogue = catalogOf(loopback(), lan(), tailscale()).enumerate()
        assertEquals(listOf(AddressKind.TAILSCALE, AddressKind.LAN, AddressKind.LOOPBACK), catalogue.map { it.kind })
    }

    @Test
    fun enumerate_dedupesTheSameAddressOnTwoInterfaces() {
        val catalogue = catalogOf(
            NetworkAdapter("eth0", listOf("192.168.1.5"), false),
            NetworkAdapter("eth1", listOf("192.168.1.5"), false),
        ).enumerate()
        assertEquals(1, catalogue.size)
    }

    @Test
    fun enumerate_isEmpty_whenThePortThrows() {
        val throwing = NetworkInterfaces { error("no interfaces here") }
        // The catalogue does not catch — the production port does. But a throwing port must not be
        // dressed up as data: resolve() over an empty catalogue is the honest downstream behaviour.
        val empty = AddressCatalog { emptyList() }
        assertTrue(empty.enumerate().isEmpty())
        assertNull(throwing.let { runCatching { AddressCatalog(it).enumerate() }.getOrNull() })
    }

    // ── resolve(AllInterfaces) ─────────────────────────────────────────────────────────

    @Test
    fun allInterfaces_bindsZeros_advertisesTailscale_andWarns() {
        val resolved = catalogOf(tailscale(), lan()).resolve(BindSelection.AllInterfaces)

        assertEquals(listOf("0.0.0.0"), resolved.hosts)
        assertEquals("100.66.155.18", resolved.advertised)
        assertTrue(resolved.warnings.contains(BindWarning.ListeningOnAllInterfaces))
    }

    @Test
    fun allInterfaces_withoutTailscale_addsNoTailscaleWarning_advertisesLan() {
        val resolved = catalogOf(lan()).resolve(BindSelection.AllInterfaces)

        assertEquals("192.168.1.42", resolved.advertised)
        assertTrue(resolved.warnings.contains(BindWarning.NoTailscaleFound))
    }

    @Test
    fun allInterfaces_withNoAddressesAtAll_advertisesNull() {
        val resolved = AddressCatalog { emptyList() }.resolve(BindSelection.AllInterfaces)
        assertEquals(listOf("0.0.0.0"), resolved.hosts)
        assertNull(resolved.advertised)
    }

    // ── resolve(Explicit) ──────────────────────────────────────────────────────────────

    @Test
    fun explicit_tailscaleOnly_bindsAndAdvertisesIt_noWarnings() {
        val resolved = catalogOf(tailscale(), lan())
            .resolve(BindSelection.Explicit(setOf("100.66.155.18")))

        assertEquals(listOf("100.66.155.18"), resolved.hosts)
        assertEquals("100.66.155.18", resolved.advertised)
        assertTrue(resolved.warnings.isEmpty())
    }

    @Test
    fun explicit_multipleAddresses_bindsAll_advertisesHighestPriority() {
        val resolved = catalogOf(tailscale(), lan())
            .resolve(BindSelection.Explicit(setOf("192.168.1.42", "100.66.155.18")))

        assertEquals(setOf("100.66.155.18", "192.168.1.42"), resolved.hosts.toSet())
        assertEquals("100.66.155.18", resolved.advertised)
        assertEquals("100.66.155.18", resolved.hosts.first()) // Tailscale first for deterministic connectors
    }

    @Test
    fun explicit_oneAddressGone_bindsTheRest_warnsAboutTheMissing() {
        val resolved = catalogOf(lan())
            .resolve(BindSelection.Explicit(setOf("100.66.155.18", "192.168.1.42")))

        assertEquals(listOf("192.168.1.42"), resolved.hosts)
        assertTrue(resolved.warnings.contains(BindWarning.AddressUnavailable("100.66.155.18")))
    }

    // ── Auto-heal (F3) ─────────────────────────────────────────────────────────────────

    @Test
    fun explicit_tailscaleAddressChanged_healsOntoTheSingleNewOne() {
        // Chosen 100.66.155.18 is gone; the one tailnet address now is 100.66.200.9 → adopt it.
        val resolved = catalogOf(tailscale("100.66.200.9"))
            .resolve(BindSelection.Explicit(setOf("100.66.155.18")))

        assertEquals(listOf("100.66.200.9"), resolved.hosts)
        assertEquals("100.66.200.9", resolved.advertised)
        assertEquals(BindSelection.Explicit(setOf("100.66.200.9")), resolved.healedSelection)
        assertTrue(resolved.warnings.any { it is BindWarning.AddressMigrated })
    }

    @Test
    fun explicit_twoCandidatesOfSameKind_doesNotHeal_fallsBackToLoopback() {
        val resolved = catalogOf(
            NetworkAdapter("ts0", listOf("100.66.1.1"), false),
            NetworkAdapter("ts1", listOf("100.66.2.2"), false),
        ).resolve(BindSelection.Explicit(setOf("100.66.155.18")))

        assertEquals(listOf("127.0.0.1"), resolved.hosts)
        assertNull(resolved.healedSelection)
        assertTrue(resolved.warnings.contains(BindWarning.FellBackToLoopback))
    }

    // ── Dead selection → loopback, NOT 0.0.0.0 (F4) ────────────────────────────────────

    @Test
    fun explicit_everythingGone_fallsBackToLoopbackWithError_neverToZeros() {
        val resolved = catalogOf(lan()) // only a LAN address; the chosen tailnet one is gone
            .resolve(BindSelection.Explicit(setOf("100.66.155.18")))

        // Two LAN-vs-Tailscale kinds differ, so no unambiguous heal → loopback.
        assertEquals(listOf("127.0.0.1"), resolved.hosts)
        assertEquals("127.0.0.1", resolved.advertised)
        assertTrue(resolved.warnings.contains(BindWarning.FellBackToLoopback))
        assertTrue("must never silently widen to 0.0.0.0", resolved.hosts.none { it == "0.0.0.0" })
    }

    @Test
    fun explicit_nothingChosenAndNoInterfaces_isLoopback() {
        val resolved = AddressCatalog { emptyList() }
            .resolve(BindSelection.Explicit(setOf("192.168.1.42")))
        assertEquals(listOf("127.0.0.1"), resolved.hosts)
    }

    // ── First-setup default (§4.3) ─────────────────────────────────────────────────────

    @Test
    fun firstSetup_prefersTailscaleExclusively_whenPresent() {
        val selection = catalogOf(tailscale(), lan()).firstSetupSelection()
        assertEquals(BindSelection.Explicit(setOf("100.66.155.18")), selection)
    }

    @Test
    fun firstSetup_fallsBackToAllInterfaces_withoutTailscale() {
        assertEquals(BindSelection.AllInterfaces, catalogOf(lan()).firstSetupSelection())
    }

    // ── The advertised-in-hosts invariant (§6) ─────────────────────────────────────────

    @Test
    fun advertisedIsAlwaysReachable_acrossEverySelectionAndCatalogueCombination() {
        val catalogues = listOf(
            catalogOf(tailscale(), lan(), loopback()),
            catalogOf(lan()),
            catalogOf(loopback()),
            AddressCatalog { emptyList() },
        )
        val selections = listOf(
            BindSelection.AllInterfaces,
            BindSelection.Explicit(setOf("100.66.155.18")),
            BindSelection.Explicit(setOf("192.168.1.42")),
            BindSelection.Explicit(setOf("192.168.1.42", "100.66.155.18")),
            BindSelection.Explicit(setOf("203.0.113.7")), // exists nowhere
        )
        for (catalogue in catalogues) {
            for (selection in selections) {
                val resolved = catalogue.resolve(selection)
                val ad = resolved.advertised
                val ok = ad == null || ad in resolved.hosts || resolved.hosts == listOf("0.0.0.0")
                assertTrue("advertised=$ad hosts=${resolved.hosts} selection=$selection", ok)
                assertTrue("hosts is never empty", resolved.hosts.isNotEmpty())
            }
        }
    }
}
