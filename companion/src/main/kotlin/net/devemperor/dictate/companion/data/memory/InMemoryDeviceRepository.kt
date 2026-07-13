package net.devemperor.dictate.companion.data.memory

import net.devemperor.dictate.companion.domain.model.Device
import net.devemperor.dictate.companion.domain.port.DeviceRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * The device store until SQLDelight lands (`wd-5`).
 *
 * Concurrent because the Ktor server serves calls from several threads; the map is the whole state,
 * so no further locking is needed.
 */
class InMemoryDeviceRepository : DeviceRepository {

    private val devices = ConcurrentHashMap<String, Device>()

    override fun findById(deviceId: String): Device? = devices[deviceId]

    override fun save(device: Device) {
        devices[device.deviceId] = device
    }

    override fun touchLastSeen(deviceId: String, at: Long) {
        devices.computeIfPresent(deviceId) { _, device -> device.copy(lastSeenAt = at) }
    }

    override fun all(): List<Device> = devices.values.sortedBy { it.pairedAt }

    override fun revoke(deviceId: String) {
        devices.remove(deviceId)
    }
}
