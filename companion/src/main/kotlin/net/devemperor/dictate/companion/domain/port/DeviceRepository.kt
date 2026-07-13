package net.devemperor.dictate.companion.domain.port

import net.devemperor.dictate.companion.domain.model.Device

/** The paired phones. */
interface DeviceRepository {

    fun findById(deviceId: String): Device?

    /** Insert or replace. Re-pairing an existing [Device.deviceId] rotates its secret hash. */
    fun save(device: Device)

    fun touchLastSeen(deviceId: String, at: Long)

    fun all(): List<Device>

    /** Un-pairs. The phone gets a 401 from its next call and offers "pair again". */
    fun revoke(deviceId: String)
}
