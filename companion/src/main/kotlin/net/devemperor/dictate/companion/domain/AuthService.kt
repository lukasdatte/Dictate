package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.domain.model.Device
import net.devemperor.dictate.companion.domain.port.DeviceRepository
import net.devemperor.dictate.shared.auth.Secrets

/**
 * Bearer secret → [Device], or null. One answer for every kind of failure (ADR-0017).
 *
 * The work is deliberately **unconditional**: an unknown device id is hashed and compared against a
 * dummy hash instead of returning early. An early return would make "unknown device" measurably
 * faster than "wrong secret", and that difference is a device-enumeration oracle for anyone who can
 * reach the port. The cost is one SHA-256 on a request that was going to fail anyway.
 */
class AuthService(private val devices: DeviceRepository) {

    private val dummyHash = Secrets.sha256("dictate-companion::no-such-device")

    fun authenticate(deviceId: String?, presentedSecret: String?): Device? {
        val device = deviceId?.let(devices::findById)
        val expectedHash = device?.secretHash ?: dummyHash
        val presentedHash = Secrets.sha256(presentedSecret.orEmpty())

        val matches = Secrets.constantTimeEquals(expectedHash, presentedHash)
        return device.takeIf { matches }
    }
}
