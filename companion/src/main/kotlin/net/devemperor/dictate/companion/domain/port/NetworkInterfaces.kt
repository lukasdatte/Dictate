package net.devemperor.dictate.companion.domain.port

/**
 * The machine's network interfaces, as far as the bind-address logic cares about them.
 *
 * A port rather than a direct `java.net.NetworkInterface` call for one reason: the classification,
 * priority, fallback and auto-heal rules in [net.devemperor.dictate.companion.domain.net.AddressCatalog]
 * are the substance worth testing, and they cannot be tested against the CI machine's real NICs. The
 * single production implementation is `platform/JvmNetworkInterfaces`; every test passes a fake list.
 */
fun interface NetworkInterfaces {

    /**
     * The currently-up interfaces and their IPv4 addresses. The implementation swallows enumeration
     * errors and returns an empty list rather than throwing — a machine whose interfaces cannot be
     * listed still has to start and fall back (loopback), not crash on launch.
     */
    fun list(): List<NetworkAdapter>
}

/** One interface: its name, its IPv4 addresses as dotted-quad strings, and whether it is loopback. */
data class NetworkAdapter(
    val name: String,
    val addresses: List<String>,
    val isLoopback: Boolean,
)
