package net.devemperor.dictate.companion.domain.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dotted-quad arithmetic behind classification and free-text validation. The CGNAT cases are the
 * ones that used to sit in `AdvertisedAddressTest`; they move here because this is where the range
 * check now lives (one place, no `java.net`).
 */
class Ipv4Test {

    @Test
    fun cgnatRangeIsRecognised() {
        assertTrue(Ipv4.isCgnat("100.64.0.0"))
        assertTrue(Ipv4.isCgnat("100.66.155.18"))
        assertTrue(Ipv4.isCgnat("100.127.255.255"))
    }

    @Test
    fun neighbouringRangesAreNotCgnat() {
        assertFalse(Ipv4.isCgnat("100.63.255.255"))
        assertFalse(Ipv4.isCgnat("100.128.0.0"))
        assertFalse(Ipv4.isCgnat("99.64.0.1"))
        assertFalse(Ipv4.isCgnat("192.168.1.1"))
    }

    @Test
    fun loopbackIsTheWhole127Block() {
        assertTrue(Ipv4.isLoopback("127.0.0.1"))
        assertTrue(Ipv4.isLoopback("127.255.255.254"))
        assertFalse(Ipv4.isLoopback("128.0.0.1"))
    }

    @Test
    fun validityRejectsGarbageAndOutOfRangeOctets() {
        assertTrue(Ipv4.isValid("0.0.0.0"))
        assertTrue(Ipv4.isValid("255.255.255.255"))
        assertFalse(Ipv4.isValid("192.168.1.999"))
        assertFalse(Ipv4.isValid("192.168.1"))
        assertFalse(Ipv4.isValid("192.168.1.1.1"))
        assertFalse(Ipv4.isValid("not.an.ip.addr"))
        assertFalse(Ipv4.isValid(""))
        assertNull(Ipv4.octets("192.168.1.999"))
    }
}
