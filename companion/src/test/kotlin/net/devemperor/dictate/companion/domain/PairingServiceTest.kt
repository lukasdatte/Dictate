package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.domain.model.Device
import net.devemperor.dictate.companion.domain.port.DeviceRepository
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.shared.auth.Secrets
import net.devemperor.dictate.shared.protocol.Endpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

class PairingServiceTest {

    private val devices = SqlDelightDeviceRepository(CompanionDatabase.inMemory())
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

    /**
     * Regression (review — L2-F1): the one-token-one-device invariant (ADR-0017) must survive
     * concurrent redemption. Ktor CIO serves calls in parallel, so two `POST /v1/pair` with the same
     * valid token can race. A non-atomic get→check-consumed→save→burn lets BOTH pass the consumed
     * check and BOTH persist a device. The `save` sleeps to widen the TOCTOU window so a regression
     * fails reliably rather than flakily.
     */
    @Test
    fun redeem_concurrentlyWithTheSameToken_savesExactlyOneDevice() {
        val saves = AtomicInteger(0)
        val countingDevices = object : DeviceRepository {
            override fun findById(deviceId: String): Device? = null
            override fun save(device: Device) {
                saves.incrementAndGet()
                Thread.sleep(25) // widen the race window: a non-atomic redeem double-saves here
            }
            override fun touchLastSeen(deviceId: String, at: Long) {}
            override fun all(): List<Device> = emptyList()
            override fun revoke(deviceId: String) {}
        }
        val service = PairingService(countingDevices, clock, serverName = "test-pc")
        val token = service.issue().token

        val barrier = CyclicBarrier(2)
        val successes = AtomicInteger(0)
        val threads = (0 until 2).map { i ->
            Thread {
                barrier.await()
                runCatching { service.redeem(token, "device-$i", "Pixel $i") }
                    .onSuccess { successes.incrementAndGet() }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("one token, one device — a concurrent redeem must not double-save", 1, saves.get())
        assertEquals("exactly one of the two racing redemptions may win", 1, successes.get())
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
