package net.devemperor.dictate.companion.ui

import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.domain.PairingService
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.companion.ui.pairing.PairingViewModel
import net.devemperor.dictate.shared.auth.PairingUri
import net.devemperor.dictate.shared.protocol.Endpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingViewModelTest {

    private val devices = SqlDelightDeviceRepository(CompanionDatabase.inMemory())
    private val clock = MutableClock()
    private val pairing = PairingService(devices, clock, serverName = "test-pc")
    private val viewModel = PairingViewModel(pairing, devices) { BASE_URL }

    @Test
    fun start_showsAQrAndTheSameTokenAsText() {
        viewModel.start()
        val state = viewModel.state.value

        // The QR and the typed code are the same credential — one of them being a "fallback" would
        // mean two code paths and one of them rotting (open question F-4).
        val parsed = PairingUri.parse(state.uri)!!
        assertEquals(state.token, parsed.token)
        assertEquals(BASE_URL, parsed.baseUrl)
        assertEquals(BASE_URL, state.baseUrl)
        assertEquals(Endpoints.PAIRING_TOKEN_LENGTH, state.token.length)
        assertFalse(state.expired)
    }

    @Test
    fun theCountdownRunsDown_andThenTheCodeIsExpired() {
        viewModel.start()
        assertEquals(Endpoints.PAIRING_TOKEN_TTL_MILLIS / 1000, viewModel.state.value.remainingSeconds)

        clock.advance(60_000)
        viewModel.tick()
        assertEquals(60, viewModel.state.value.remainingSeconds)
        assertFalse(viewModel.state.value.expired)

        clock.advance(60_000)
        viewModel.tick()
        assertEquals(0, viewModel.state.value.remainingSeconds)
        assertTrue("an expiring code is a feature, and the user must see it happen", viewModel.state.value.expired)
    }

    @Test
    fun aNewCodeBurnsTheOldOne() {
        viewModel.start()
        val first = viewModel.state.value.token

        viewModel.start()

        assertNotEquals(first, viewModel.state.value.token)
        assertNull(devices.findById(DEVICE_ID))
    }

    @Test
    fun onceAPhonePairs_theDialogNoticesIt() {
        viewModel.start()
        assertNull(viewModel.state.value.pairedDeviceName)

        // The pairing happens on the HTTP thread, inside the server. The dialog finds out by seeing a
        // device it did not know when it opened — no callback from a route into the UI.
        pairing.redeem(viewModel.state.value.token, DEVICE_ID, "Pixel 8")
        viewModel.tick()

        assertEquals("Pixel 8", viewModel.state.value.pairedDeviceName)
        assertFalse("a paired dialog is not an expired one", viewModel.state.value.expired)
    }

    private companion object {
        const val BASE_URL = "http://vm-win.tailnet.ts.net:8756"
        const val DEVICE_ID = "test-device-0001"
    }
}
