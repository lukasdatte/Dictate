package net.devemperor.dictate.companion.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.devemperor.dictate.companion.domain.CompanionSettings
import net.devemperor.dictate.companion.domain.port.AutostartManager

data class SettingsUiState(
    val port: Int = CompanionSettings.DEFAULT_PORT,
    val bindAddress: String = CompanionSettings.DEFAULT_BIND_ADDRESS,
    val clipboardRestoreDelayMillis: Long = CompanionSettings.DEFAULT_RESTORE_DELAY_MILLIS,
    val autostartEnabled: Boolean = false,
    /** false on Linux/macOS → the toggle is disabled rather than lying. */
    val autostartSupported: Boolean = false,
    /** true once port or bind address were changed: the running socket still has the old ones. */
    val restartRequired: Boolean = false,
    val portError: String? = null,
)

/**
 * The settings screen's brain.
 *
 * The autostart state is **read back** from the [AutostartManager] after every write rather than
 * mirrored optimistically: on Windows the registry write can fail (a locked hive, a policy), and a
 * toggle that flips in the UI while nothing happened in the registry is worse than one that refuses
 * to move.
 */
class SettingsViewModel(
    private val settings: CompanionSettings,
    private val autostart: AutostartManager,
) {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        _state.value = SettingsUiState(
            port = settings.port,
            bindAddress = settings.bindAddress,
            clipboardRestoreDelayMillis = settings.clipboardRestoreDelayMillis,
            autostartEnabled = autostart.isEnabled(),
            autostartSupported = autostart.supported,
        )
    }

    fun setPort(raw: String) {
        val port = raw.toIntOrNull()
        if (port == null || port !in MIN_PORT..MAX_PORT) {
            _state.value = _state.value.copy(portError = "Enter a port between $MIN_PORT and $MAX_PORT")
            return
        }
        settings.port = port
        _state.value = _state.value.copy(port = port, portError = null, restartRequired = true)
    }

    fun setBindAddress(address: String) {
        settings.bindAddress = address
        _state.value = _state.value.copy(bindAddress = address, restartRequired = true)
    }

    fun setClipboardRestoreDelay(millis: Long) {
        val clamped = millis.coerceIn(0, CompanionSettings.MAX_RESTORE_DELAY_MILLIS)
        settings.clipboardRestoreDelayMillis = clamped
        _state.value = _state.value.copy(clipboardRestoreDelayMillis = clamped)
    }

    fun setAutostart(enabled: Boolean) {
        autostart.setEnabled(enabled)
        // Read back, never assume: a failed registry write must not show as a happy toggle.
        _state.value = _state.value.copy(autostartEnabled = autostart.isEnabled())
    }

    private companion object {
        const val MIN_PORT = 1024
        const val MAX_PORT = 65_535
    }
}
