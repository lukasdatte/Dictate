package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.domain.model.Device
import net.devemperor.dictate.shared.auth.Secrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthServiceTest {

    private val devices = SqlDelightDeviceRepository(CompanionDatabase.inMemory())
    private val auth = AuthService(devices)

    @Before
    fun setUp() {
        devices.save(
            Device(
                deviceId = DEVICE_ID,
                name = "Pixel 8",
                secretHash = Secrets.sha256(SECRET),
                pairedAt = 1L,
            ),
        )
    }

    @Test
    fun authenticate_withTheRightSecret_resolvesTheDevice() {
        assertEquals(DEVICE_ID, auth.authenticate(DEVICE_ID, SECRET)?.deviceId)
    }

    @Test
    fun authenticate_rejectsEveryOtherShapeOfFailure_identically() {
        assertNull("wrong secret", auth.authenticate(DEVICE_ID, "wrong-secret"))
        assertNull("unknown device", auth.authenticate("unknown-device-id", SECRET))
        assertNull("no device id", auth.authenticate(null, SECRET))
        assertNull("no secret", auth.authenticate(DEVICE_ID, null))
        assertNull("empty secret", auth.authenticate(DEVICE_ID, ""))
    }

    private companion object {
        const val DEVICE_ID = "test-device-0001"
        const val SECRET = "a-very-long-device-secret-value-000"
    }
}
