package net.devemperor.dictate.shared.auth

import net.devemperor.dictate.shared.protocol.Endpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone builds these headers and the companion parses them — both sides are pinned here. */
class AuthHeadersTest {

    private val credentials = Credentials(deviceId = "device-1", deviceSecret = "s3cr3t")

    @Test
    fun forDevice_carriesBearerDeviceAndProtocol() {
        val headers = AuthHeaders.forDevice(credentials)

        assertEquals("Bearer s3cr3t", headers[Endpoints.HEADER_AUTHORIZATION])
        assertEquals("device-1", headers[Endpoints.HEADER_DEVICE_ID])
        assertEquals("1", headers[Endpoints.HEADER_PROTOCOL])
    }

    @Test
    fun forPairing_carriesTheProtocolButNoAuthorization() {
        val headers = AuthHeaders.forPairing()

        assertFalse(headers.toString(), headers.containsKey(Endpoints.HEADER_AUTHORIZATION))
        assertEquals("1", headers[Endpoints.HEADER_PROTOCOL])
    }

    @Test
    fun parseBearer_roundTripsWhatForDeviceBuilt() {
        val header = AuthHeaders.forDevice(credentials)[Endpoints.HEADER_AUTHORIZATION]

        assertEquals("s3cr3t", AuthHeaders.parseBearer(header))
    }

    @Test
    fun parseBearer_rejectsAnythingThatIsNotABearerHeader() {
        assertNull(AuthHeaders.parseBearer(null))
        assertNull(AuthHeaders.parseBearer(""))
        assertNull(AuthHeaders.parseBearer("s3cr3t"))
        assertNull(AuthHeaders.parseBearer("Basic dXNlcjpwYXNz"))
        assertNull(AuthHeaders.parseBearer("Bearer "))
        assertNull(AuthHeaders.parseBearer("bearer s3cr3t"))
    }
}
