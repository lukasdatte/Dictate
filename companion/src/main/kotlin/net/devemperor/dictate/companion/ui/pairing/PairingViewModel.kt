package net.devemperor.dictate.companion.ui.pairing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.devemperor.dictate.companion.domain.PairingService
import net.devemperor.dictate.companion.domain.port.DeviceRepository
import net.devemperor.dictate.shared.auth.PairingUri

data class PairingUiState(
    val token: String = "",
    /** The `dictate://pair?…` URI the QR encodes — the phone scans this. */
    val uri: String = "",
    val baseUrl: String = "",
    val remainingSeconds: Long = 0,
    /** Set once a device redeems the token; the dialog closes on it. */
    val pairedDeviceName: String? = null,
) {
    val expired: Boolean get() = remainingSeconds <= 0 && pairedDeviceName == null
}

/**
 * The pairing dialog's brain.
 *
 * The QR **and** the typed code are shown together, and neither is the fallback of the other: a user
 * who will not give the keyboard app camera permission pairs by typing, and that path must be as
 * good as the other one (open question F-4). Both carry the same token.
 *
 * The countdown is deliberately visible. An expiring code is a *feature* — it is what makes a
 * screenshot of the QR harmless five minutes later — and a user who watches it run down understands
 * why, instead of finding an unexplained error at the end.
 *
 * [tick] is called by the UI (or by a test, with the clock moved by hand): no timer lives in here, so
 * the whole thing stays a pure state machine.
 */
class PairingViewModel(
    private val pairing: PairingService,
    private val devices: DeviceRepository,
    private val baseUrl: () -> String,
) {

    private val _state = MutableStateFlow(PairingUiState())
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    private var knownDeviceIds: Set<String> = emptySet()

    /** Issues a fresh token, burning any previous one — also the "new code" button. */
    fun start() {
        knownDeviceIds = devices.all().map { it.deviceId }.toSet()
        val pending = pairing.issue()
        val url = baseUrl()

        _state.value = PairingUiState(
            token = pending.token,
            uri = PairingUri.encode(url, pending.token),
            baseUrl = url,
            remainingSeconds = secondsLeft(),
        )
    }

    /**
     * Refreshes the countdown and notices a device that has just paired.
     *
     * The pairing itself happens on the HTTP thread, in the server; the dialog learns about it by
     * looking for a device it did not know when it opened. That is why there is no callback from the
     * route into the UI — the server layer does not know the UI exists, and it should not.
     */
    fun tick() {
        val newDevice = devices.all().firstOrNull { it.deviceId !in knownDeviceIds }
        _state.value = _state.value.copy(
            remainingSeconds = secondsLeft(),
            pairedDeviceName = newDevice?.name,
        )
    }

    fun cancel() {
        pairing.cancel()
        _state.value = PairingUiState()
    }

    private fun secondsLeft(): Long = (pairing.remainingMillis() + 999) / 1000
}
