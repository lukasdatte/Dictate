package net.devemperor.dictate.companion.data

import net.devemperor.dictate.companion.db.DictateCompanionDb
import net.devemperor.dictate.companion.db.Devices
import net.devemperor.dictate.companion.domain.model.Device
import net.devemperor.dictate.companion.domain.port.DeviceRepository

/** [DeviceRepository] on SQLite. Un-pairing cascades: the device's texts go with it. */
class SqlDelightDeviceRepository(database: DictateCompanionDb) : DeviceRepository {

    private val queries = database.companionQueries

    override fun findById(deviceId: String): Device? =
        queries.deviceById(deviceId).executeAsOneOrNull()?.toDomain()

    override fun save(device: Device) {
        queries.saveDevice(
            device_id = device.deviceId,
            name = device.name,
            secret_hash = device.secretHash,
            paired_at = device.pairedAt,
            last_seen_at = device.lastSeenAt,
        )
    }

    override fun touchLastSeen(deviceId: String, at: Long) {
        queries.touchLastSeen(at = at, deviceId = deviceId)
    }

    override fun all(): List<Device> = queries.allDevices().executeAsList().map { it.toDomain() }

    override fun revoke(deviceId: String) {
        queries.deleteDevice(deviceId)
    }

    private fun Devices.toDomain() = Device(
        deviceId = device_id,
        name = name,
        secretHash = secret_hash,
        pairedAt = paired_at,
        lastSeenAt = last_seen_at,
    )
}
