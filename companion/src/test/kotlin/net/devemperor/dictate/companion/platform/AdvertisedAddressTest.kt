package net.devemperor.dictate.companion.platform

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The CGNAT-range check behind the advertised pairing address. Guards the "PC nicht erreichbar"
 * pairing bug: the QR used to carry the AD hostname, which the phone cannot resolve — only the
 * Tailscale 100.64.0.0/10 address is reachable from both sides.
 */
class AdvertisedAddressTest {

    private fun addr(s: String): InetAddress = InetAddress.getByName(s)

    @Test
    fun theTailscaleRangeIsRecognised() {
        assertEquals(true, AdvertisedAddress.isCgnat(addr("100.64.0.0")))
        assertEquals(true, AdvertisedAddress.isCgnat(addr("100.66.155.18")))
        assertEquals(true, AdvertisedAddress.isCgnat(addr("100.127.255.255")))
    }

    @Test
    fun neighbouringRangesAreNot() {
        assertEquals(false, AdvertisedAddress.isCgnat(addr("100.63.255.255")))
        assertEquals(false, AdvertisedAddress.isCgnat(addr("100.128.0.0")))
        assertEquals(false, AdvertisedAddress.isCgnat(addr("99.64.0.1")))
        assertEquals(false, AdvertisedAddress.isCgnat(addr("10.0.0.1")))
        assertEquals(false, AdvertisedAddress.isCgnat(addr("192.168.1.1")))
    }

    @Test
    fun detectFallsBackToTheGivenNameWhenNoTailscaleAddressExists() {
        // On machines WITH a Tailscale interface detect() returns that IP instead — both are
        // valid outcomes; what must never happen is returning a name nobody provided.
        val result = AdvertisedAddress.detect { "fallback-host" }
        val viaTailscale = AdvertisedAddress.tailscaleIpv4()
        assertEquals(viaTailscale ?: "fallback-host", result)
    }
}
