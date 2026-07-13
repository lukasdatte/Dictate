package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.data.memory.InMemoryDeviceRepository
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.shared.auth.Secrets
import net.devemperor.dictate.shared.protocol.Endpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingServiceTest {

    private val devices = InMemoryDeviceRepository()
    private val clock = MutableClock()
    private val pairing = PairingService(devices, clock, serverName = "test-pc")

    @Test
    fun redeem_storesOnlyTheHash_neverTheSecret() {
        val token = pairing.issue().token

        val response = pairing.redeem(token, DEVICE_ID, "Pixel 8")

        val stored = devices.findById(DEVICE_ID)!!
        assertEquals(Secrets.sha256(response.deviceSecret), stored.secretHash)
        assertNotEquals(response.deviceSecret, stored.secretHash)
        assertNotEquals(token, response.deviceSecret)
        assertEquals("test-pc", response.serverName)
    }

    @Test(expected = CompanionException.InvalidTokenException::class)
    fun redeem_withoutAnIssuedToken_isInvalid() {
        pairing.redeem("ABCDEFGH", DEVICE_ID, "Pixel 8")
    }

    @Test(expected = CompanionException.InvalidTokenException::class)
    fun redeem_withTheWrongToken_isInvalid() {
        pairing.issue()
        pairing.redeem("ZZZZZZZZ", DEVICE_ID, "Pixel 8")
    }

    @Test(expected = CompanionException.TokenConsumedException::class)
    fun redeem_twice_isConsumed() {
        val token = pairing.issue().token
        pairing.redeem(token, DEVICE_ID, "Pixel 8")

        pairing.redeem(token, "another-device-id", "Pixel 9")
    }

    @Test(expected = CompanionException.TokenExpiredException::class)
    fun redeem_afterTheTtl_isExpired() {
        val token = pairing.issue().token
        clock.advance(Endpoints.PAIRING_TOKEN_TTL_MILLIS)

        pairing.redeem(token, DEVICE_ID, "Pixel 8")
    }

    @Test
    fun issue_burnsThePreviousToken() {
        val first = pairing.issue().token
        val second = pairing.issue().token
        assertNotEquals(first, second)

        val failure = runCatching { pairing.redeem(first, DEVICE_ID, "Pixel 8") }.exceptionOrNull()

        assertTrue("$failure", failure is CompanionException.InvalidTokenException)
        assertNull(devices.findById(DEVICE_ID))
    }

    @Test
    fun remainingMillis_countsDown_andIsZeroOnceRedeemed() {
        val token = pairing.issue().token
        clock.advance(30_000)
        assertEquals(Endpoints.PAIRING_TOKEN_TTL_MILLIS - 30_000, pairing.remainingMillis())

        pairing.redeem(token, DEVICE_ID, "Pixel 8")

        assertEquals(0L, pairing.remainingMillis())
    }

    private companion object {
        const val DEVICE_ID = "test-device-0001"
    }
}
