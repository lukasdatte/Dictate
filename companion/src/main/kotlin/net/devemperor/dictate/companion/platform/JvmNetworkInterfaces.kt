package net.devemperor.dictate.companion.platform

import net.devemperor.dictate.companion.domain.port.NetworkAdapter
import net.devemperor.dictate.companion.domain.port.NetworkInterfaces
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The one place that touches `java.net.NetworkInterface` — the production side of the
 * [NetworkInterfaces] port.
 *
 * **IPv4-only** (`Inet4Address`): the pairing target is a Tailscale IPv4, and a `http://[fe80::…%eth0]`
 * URL in a QR is a separate problem the classifier is left open for but this revision does not take on.
 *
 * **Only up, non-virtual interfaces**, and enumeration errors are swallowed into an empty list: a
 * machine whose interfaces cannot be listed must still start and fall back to loopback, not crash on
 * launch. The domain [net.devemperor.dictate.companion.domain.net.AddressCatalog] turns "no
 * interfaces" into the honest downstream behaviour.
 */
object JvmNetworkInterfaces : NetworkInterfaces {

    override fun list(): List<NetworkAdapter> = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp }
            .map { iface ->
                NetworkAdapter(
                    name = iface.name,
                    addresses = iface.inetAddresses.asSequence()
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress }
                        .toList(),
                    isLoopback = iface.isLoopback,
                )
            }
            .filter { it.addresses.isNotEmpty() }
            .toList()
    } catch (e: Exception) {
        emptyList()
    }
}
