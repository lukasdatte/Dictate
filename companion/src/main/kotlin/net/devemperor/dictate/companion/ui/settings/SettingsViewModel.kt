package net.devemperor.dictate.companion.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.devemperor.dictate.companion.domain.CompanionSettings
import net.devemperor.dictate.companion.domain.net.AddressCatalog
import net.devemperor.dictate.companion.domain.net.AddressKind
import net.devemperor.dictate.companion.domain.net.BindCandidate
import net.devemperor.dictate.companion.domain.net.BindSelection
import net.devemperor.dictate.companion.domain.net.BindWarning
import net.devemperor.dictate.companion.domain.net.Ipv4
import net.devemperor.dictate.companion.domain.port.AutostartManager

data class SettingsUiState(
    val port: Int = CompanionSettings.DEFAULT_PORT,
    val bindSelection: BindSelection = BindSelection.AllInterfaces,
    val candidates: List<BindCandidate> = emptyList(),
    /** Warnings from resolving the *current* selection — rendered as banners. */
    val warnings: List<BindWarning> = emptyList(),
    /** Non-null → the free-text advanced field held something that is not a valid IPv4 literal. */
    val bindError: String? = null,
    /** Non-null → on `AllInterfaces` while a tailnet address exists: the one-click narrowing target. */
    val tailscaleSuggestion: String? = null,
    val clipboardRestoreDelayMillis: Long = CompanionSettings.DEFAULT_RESTORE_DELAY_MILLIS,
    val autostartEnabled: Boolean = false,
    /** false on Linux/macOS → the toggle is disabled rather than lying. */
    val autostartSupported: Boolean = false,
    /** true once port or bind selection were changed: the running socket still has the old ones. */
    val restartRequired: Boolean = false,
    val portError: String? = null,
) {
    /** The addresses ticked in "Selected" mode; empty in "All interfaces" mode. */
    val selectedAddresses: Set<String>
        get() = (bindSelection as? BindSelection.Explicit)?.addresses ?: emptySet()
}

/**
 * The settings screen's brain.
 *
 * The bind selection is the substance here. Every mutation goes through the domain
 * [CompanionSettings] (persisted) and is re-resolved against the live [AddressCatalog] so the banners
 * (listening-on-all, address-unavailable, …) always describe the *current* choice. An empty explicit
 * selection is rejected rather than saved — "listen on nothing" is not a state the user can want, and
 * it would strand the app on loopback with no way back but the checkbox they just cleared.
 *
 * The autostart state is **read back** from the [AutostartManager] after every write rather than
 * mirrored optimistically: on Windows the registry write can fail (a locked hive, a policy), and a
 * toggle that flips in the UI while nothing happened in the registry is worse than one that refuses
 * to move.
 */
class SettingsViewModel(
    private val settings: CompanionSettings,
    private val autostart: AutostartManager,
    private val catalog: AddressCatalog,
) {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        _state.value = SettingsUiState(
            port = settings.port,
            clipboardRestoreDelayMillis = settings.clipboardRestoreDelayMillis,
            autostartEnabled = autostart.isEnabled(),
            autostartSupported = autostart.supported,
        ).withBind(settings.bindSelection, restartChanged = false)
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

    /** Re-read the interface list without touching the selection (E1: Tailscale appeared/vanished). */
    fun refreshInterfaces() {
        _state.value = _state.value.withBind(_state.value.bindSelection, restartChanged = false)
    }

    fun listenOnAllInterfaces() = persist(BindSelection.AllInterfaces)

    /** Switch to "Selected", seeding it with the highest-priority address so it is never empty. */
    fun listenOnSelected() {
        if (_state.value.bindSelection is BindSelection.Explicit) return
        val seed = catalog.enumerate().firstOrNull()?.address
        if (seed == null) {
            _state.value = _state.value.copy(bindError = "No addresses are available to select.")
            return
        }
        persist(BindSelection.Explicit(setOf(seed)))
    }

    fun toggleAddress(address: String, checked: Boolean) {
        val current = _state.value.selectedAddresses
        val next = if (checked) current + address else current - address
        if (next.isEmpty()) {
            _state.value = _state.value.copy(bindError = "Keep at least one address, or choose “All interfaces”.")
            return
        }
        persist(BindSelection.Explicit(next))
    }

    /** The validated advanced field: add a literal address the catalogue may not list yet. */
    fun addManualAddress(raw: String) {
        val address = raw.trim()
        if (!Ipv4.isValid(address)) {
            _state.value = _state.value.copy(bindError = "“$address” is not a valid IPv4 address.")
            return
        }
        persist(BindSelection.Explicit(_state.value.selectedAddresses + address))
    }

    /** F2 one-click: narrow an all-interfaces install onto the discovered tailnet address. */
    fun applyTailscaleSuggestion() {
        val tailscale = _state.value.tailscaleSuggestion ?: return
        persist(BindSelection.Explicit(setOf(tailscale)))
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

    private fun persist(selection: BindSelection) {
        settings.bindSelection = selection
        _state.value = _state.value.withBind(selection, restartChanged = true)
    }

    /** Recompute the derived bind view (catalogue, warnings, suggestion) for a selection. */
    private fun SettingsUiState.withBind(selection: BindSelection, restartChanged: Boolean): SettingsUiState {
        val candidates = catalog.enumerate()
        val resolved = catalog.resolve(selection)
        val tailscale = candidates.firstOrNull { it.kind == AddressKind.TAILSCALE }?.address
        return copy(
            bindSelection = selection,
            candidates = candidates,
            warnings = resolved.warnings,
            bindError = null,
            tailscaleSuggestion = tailscale?.takeIf { selection is BindSelection.AllInterfaces },
            restartRequired = restartRequired || restartChanged,
        )
    }

    private companion object {
        const val MIN_PORT = 1024
        const val MAX_PORT = 65_535
    }
}
