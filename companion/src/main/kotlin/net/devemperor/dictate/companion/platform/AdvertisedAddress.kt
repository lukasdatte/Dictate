package net.devemperor.dictate.companion.platform

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The host the phone is told to reach this machine at (the QR base URL).
 *
 * The machine's own hostname is useless for that: the phone is not in the office AD DNS and cannot
 * resolve `Laptop-XY`, so a QR carrying the hostname pairs a target the phone can never reach
 * ("PC nicht erreichbar" right after a successful-looking scan). The one address both devices share
 * is the Tailscale one — every Tailscale IPv4 lives in the CGNAT range 100.64.0.0/10, so the first
 * live interface address in that range is what gets advertised. The hostname stays as the fallback
 * for setups without Tailscale, where same-LAN mDNS may still resolve it.
 */
object AdvertisedAddress {

    /** The advertised host: the Tailscale IPv4 when present, otherwise [fallback]. */
    fun detect(fallback: () -> String): String = tailscaleIpv4() ?: fallback()

    fun tailscaleIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { isCgnat(it) }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    /** 100.64.0.0/10 — the range Tailscale assigns from. */
    fun isCgnat(address: InetAddress): Boolean {
        val bytes = address.address
        return bytes.size == 4 &&
            (bytes[0].toInt() and 0xFF) == 100 &&
            (bytes[1].toInt() and 0xC0) == 64
    }
}
