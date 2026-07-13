package net.devemperor.dictate.companion.ui.devices

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.devemperor.dictate.companion.domain.model.Device
import net.devemperor.dictate.companion.domain.port.DeviceRepository

data class DevicesUiState(val devices: List<Device> = emptyList())

/**
 * The paired phones, and the ability to un-pair one.
 *
 * Un-pairing is not cosmetic: it deletes the device row, so the phone's next call gets a 401 and
 * offers "pair again" — and the `ON DELETE CASCADE` takes that phone's received texts with it. A
 * user who un-pairs a phone they lost expects exactly that.
 */
class DevicesViewModel(private val devices: DeviceRepository) {

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = DevicesUiState(devices.all())
    }

    fun revoke(deviceId: String) {
        devices.revoke(deviceId)
        refresh()
    }
}
