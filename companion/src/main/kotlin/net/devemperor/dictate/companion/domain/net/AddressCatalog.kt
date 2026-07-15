package net.devemperor.dictate.companion.domain.net

import net.devemperor.dictate.companion.domain.port.NetworkInterfaces

/**
 * Turns the machine's interfaces into a classified, prioritised catalogue and resolves a
 * [BindSelection] against it.
 *
 * This is the whole bind-address brain, kept framework-free and NIC-free (it talks only to the
 * [NetworkInterfaces] port) so every rule below is a plain unit test:
 *
 * - **Classification** — CGNAT `100.64.0.0/10` is Tailscale, a loopback interface or `127.x` is
 *   loopback, any other valid IPv4 is LAN.
 * - **Priority** — Tailscale before LAN before loopback: it decides what a multi-address bind lists
 *   first and, above all, what the pairing QR advertises.
 * - **Resolution** — [resolve] is the single seam where the bound hosts and the advertised address
 *   are chosen together, which is what makes their consistency an invariant rather than a convention.
 *
 * `enumerate` and `resolve` live on one object because `resolve` consumes `enumerate` and both share
 * the priority ordering; splitting them would duplicate that ordering. If `resolve` grows past a
 * screenful, `BindResolver(catalog)` is the clean later extraction (see ADR-0023).
 */
class AddressCatalog(private val interfaces: NetworkInterfaces) {

    /** Every current IPv4 address, classified, highest-priority first, de-duplicated by address. */
    fun enumerate(): List<BindCandidate> =
        interfaces.list()
            .flatMap { adapter ->
                adapter.addresses.map { address ->
                    BindCandidate(address, adapter.name, classify(address, adapter.isLoopback))
                }
            }
            .sortedBy { priority(it.kind) }
            .distinctBy { it.address }

    /**
     * The selection to persist when the user has never configured one: Tailscale-only if a tailnet
     * address exists (the security default — nothing then listens on the LAN), otherwise every
     * interface. Materialised into [BindSelection.Explicit] so the stored setting states the address,
     * not a mode that re-resolves.
     */
    fun firstSetupSelection(): BindSelection {
        val tailscale = enumerate().firstOrNull { it.kind == AddressKind.TAILSCALE }
        return if (tailscale != null) BindSelection.Explicit(setOf(tailscale.address)) else BindSelection.AllInterfaces
    }

    /** Match [selection] against the live catalogue; see [ResolvedBinding] for the guarantees. */
    fun resolve(selection: BindSelection): ResolvedBinding {
        val catalogue = enumerate()
        return when (selection) {
            is BindSelection.AllInterfaces -> resolveAll(catalogue)
            is BindSelection.Explicit -> resolveExplicit(selection, catalogue)
        }
    }

    private fun resolveAll(catalogue: List<BindCandidate>): ResolvedBinding {
        val warnings = buildList {
            add(BindWarning.ListeningOnAllInterfaces)
            if (catalogue.none { it.kind == AddressKind.TAILSCALE }) add(BindWarning.NoTailscaleFound)
        }
        return ResolvedBinding(
            hosts = listOf(BIND_ALL),
            advertised = advertisedFrom(catalogue.map { it.address }, catalogue),
            warnings = warnings,
        )
    }

    private fun resolveExplicit(selection: BindSelection.Explicit, catalogue: List<BindCandidate>): ResolvedBinding {
        val available = catalogue.mapTo(HashSet()) { it.address }
        val present = selection.addresses.filter { it in available }
        val missing = selection.addresses.filter { it !in available }

        if (present.isNotEmpty()) {
            return ResolvedBinding(
                hosts = present.sortedByPriority(catalogue),
                advertised = advertisedFrom(present, catalogue),
                warnings = missing.map { BindWarning.AddressUnavailable(it) },
            )
        }

        // Nothing the user chose exists any more. Try to heal onto a single same-kind replacement
        // (the tailnet-address-changed case) before giving up to loopback.
        healOnto(selection, catalogue)?.let { return it }

        return ResolvedBinding(
            hosts = listOf(BIND_LOOPBACK),
            advertised = BIND_LOOPBACK,
            warnings = selection.addresses.map { BindWarning.AddressUnavailable(it) } + BindWarning.FellBackToLoopback,
        )
    }

    /**
     * Auto-heal, and *only* when it is unambiguous: the whole selection must be one kind and the
     * catalogue must hold exactly one candidate of that kind. Two candidates would make any pick a
     * guess, and guessing which network to bind is worse than falling back and asking.
     */
    private fun healOnto(selection: BindSelection.Explicit, catalogue: List<BindCandidate>): ResolvedBinding? {
        val selectedKinds = selection.addresses.map { classify(it) }.toSet()
        if (selectedKinds.size != 1) return null
        val sameKind = catalogue.filter { it.kind == selectedKinds.first() }
        if (sameKind.size != 1) return null

        val healed = sameKind.first().address
        return ResolvedBinding(
            hosts = listOf(healed),
            advertised = healed,
            warnings = listOf(BindWarning.AddressMigrated(selection.addresses, healed)),
            healedSelection = BindSelection.Explicit(setOf(healed)),
        )
    }

    /** The highest-priority address to put in the QR, or `null` when there is nothing to advertise. */
    private fun advertisedFrom(addresses: Collection<String>, catalogue: List<BindCandidate>): String? =
        addresses.minByOrNull { priority(kindOf(it, catalogue)) }

    private fun List<String>.sortedByPriority(catalogue: List<BindCandidate>): List<String> =
        sortedBy { priority(kindOf(it, catalogue)) }

    private fun kindOf(address: String, catalogue: List<BindCandidate>): AddressKind =
        catalogue.firstOrNull { it.address == address }?.kind ?: classify(address)

    /** Classification for a catalogue address, where the interface already told us if it is loopback. */
    private fun classify(address: String, isLoopbackInterface: Boolean): AddressKind = when {
        isLoopbackInterface || Ipv4.isLoopback(address) -> AddressKind.LOOPBACK
        Ipv4.isCgnat(address) -> AddressKind.TAILSCALE
        else -> AddressKind.LAN
    }

    /** Classification for a bare address (a selection or free-text entry) with no interface context. */
    private fun classify(address: String): AddressKind = when {
        Ipv4.isLoopback(address) -> AddressKind.LOOPBACK
        Ipv4.isCgnat(address) -> AddressKind.TAILSCALE
        Ipv4.isValid(address) -> AddressKind.LAN
        else -> AddressKind.OTHER
    }

    private fun priority(kind: AddressKind): Int = when (kind) {
        AddressKind.TAILSCALE -> 0
        AddressKind.LAN -> 1
        AddressKind.OTHER -> 2
        AddressKind.LOOPBACK -> 3
    }

    companion object {
        const val BIND_ALL = "0.0.0.0"
        const val BIND_LOOPBACK = "127.0.0.1"
    }
}
