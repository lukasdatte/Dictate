package net.devemperor.dictate.companion.ui

import net.devemperor.dictate.companion.data.memory.InMemorySettings
import net.devemperor.dictate.companion.domain.CompanionSettings
import net.devemperor.dictate.companion.domain.net.AddressCatalog
import net.devemperor.dictate.companion.domain.net.BindSelection
import net.devemperor.dictate.companion.domain.port.AutostartManager
import net.devemperor.dictate.companion.domain.port.NetworkAdapter
import net.devemperor.dictate.companion.platform.fallback.NoopAutostart
import net.devemperor.dictate.companion.ui.settings.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsViewModelTest {

    private val store = InMemorySettings()
    private val settings = CompanionSettings(store)

    private val emptyCatalog = AddressCatalog { emptyList() }
    private val richCatalog = AddressCatalog {
        listOf(
            NetworkAdapter("tailscale0", listOf("100.66.155.18"), isLoopback = false),
            NetworkAdapter("enp3s0", listOf("192.168.1.42"), isLoopback = false),
        )
    }

    private fun viewModel(
        autostart: AutostartManager = NoopAutostart,
        catalog: AddressCatalog = emptyCatalog,
    ) = SettingsViewModel(settings, autostart, catalog)

    @Test
    fun defaultsHold_whenNothingWasEverSaved() {
        val state = viewModel().state.value

        assertEquals(CompanionSettings.DEFAULT_PORT, state.port)
        assertEquals(BindSelection.AllInterfaces, state.bindSelection)
        assertFalse(state.restartRequired)
    }

    @Test
    fun switchingToSelected_seedsTheHighestPriorityAddress_andPersists() {
        val vm = viewModel(catalog = richCatalog)

        vm.listenOnSelected()

        assertEquals(BindSelection.Explicit(setOf("100.66.155.18")), vm.state.value.bindSelection)
        assertEquals(BindSelection.Explicit(setOf("100.66.155.18")), CompanionSettings(store).bindSelection)
        assertTrue(vm.state.value.restartRequired)
    }

    @Test
    fun clearingTheLastAddress_isRejected_withAnError() {
        val vm = viewModel(catalog = richCatalog)
        vm.listenOnSelected() // now Explicit({100.66.155.18})

        vm.toggleAddress("100.66.155.18", checked = false)

        assertNotNull(vm.state.value.bindError)
        assertEquals(BindSelection.Explicit(setOf("100.66.155.18")), vm.state.value.bindSelection)
    }

    @Test
    fun aGarbageManualAddress_isRejected_withoutPersisting() {
        val vm = viewModel(catalog = richCatalog)
        vm.listenOnSelected()

        vm.addManualAddress("192.168.1.999")

        assertNotNull(vm.state.value.bindError)
        assertFalse(vm.state.value.selectedAddresses.contains("192.168.1.999"))
    }

    @Test
    fun aValidManualAddress_isAdded() {
        val vm = viewModel(catalog = richCatalog)
        vm.listenOnSelected()

        vm.addManualAddress(" 10.0.0.9 ")

        assertTrue(vm.state.value.selectedAddresses.contains("10.0.0.9"))
    }

    @Test
    fun onAllInterfacesWithTailscalePresent_aSuggestionIsOffered_andAppliesInOneClick() {
        val vm = viewModel(catalog = richCatalog) // starts AllInterfaces (nothing stored)

        assertEquals("100.66.155.18", vm.state.value.tailscaleSuggestion)

        vm.applyTailscaleSuggestion()

        assertEquals(BindSelection.Explicit(setOf("100.66.155.18")), vm.state.value.bindSelection)
        assertNull(vm.state.value.tailscaleSuggestion) // no longer on AllInterfaces
    }

    @Test
    fun aGarbageValueInTheTable_fallsBackToTheDefault() {
        // The settings table is plain SQLite the user could edit by hand. Refusing to start over it
        // would be a bad trade.
        store.put("server.port", "eight thousand")

        assertEquals(CompanionSettings.DEFAULT_PORT, settings.port)
    }

    @Test
    fun changingThePort_isPersisted_andSaysARestartIsNeeded() {
        val viewModel = viewModel()

        viewModel.setPort("9000")

        assertEquals(9000, viewModel.state.value.port)
        assertEquals(9000, CompanionSettings(store).port)
        assertNull(viewModel.state.value.portError)
        // The socket in use right now still has the old port — pretending otherwise would be a lie
        // the user only discovers when their phone cannot reach the PC.
        assertTrue(viewModel.state.value.restartRequired)
    }

    @Test
    fun anImpossiblePort_isRejectedWithoutTouchingTheSetting() {
        val viewModel = viewModel()

        viewModel.setPort("70000")

        assertNotNull(viewModel.state.value.portError)
        assertEquals(CompanionSettings.DEFAULT_PORT, settings.port)
        assertFalse(viewModel.state.value.restartRequired)
    }

    @Test
    fun theRestoreDelayIsClamped() {
        val viewModel = viewModel()

        viewModel.setClipboardRestoreDelay(999_999)

        assertEquals(CompanionSettings.MAX_RESTORE_DELAY_MILLIS, viewModel.state.value.clipboardRestoreDelayMillis)
    }

    @Test
    fun anAutostartWriteThatDoesNotStick_doesNotFlipTheToggle() {
        // NoopAutostart accepts setEnabled(true) and still reports false — precisely the shape of a
        // failed registry write. The toggle must not lie about it.
        val viewModel = viewModel()

        viewModel.setAutostart(true)

        assertFalse(viewModel.state.value.autostartEnabled)
        assertFalse(viewModel.state.value.autostartSupported)
    }

    @Test
    fun aWorkingAutostart_readsBackAsEnabled() {
        val working = object : AutostartManager {
            override val supported = true
            private var enabled = false
            override fun isEnabled() = enabled
            override fun setEnabled(enabled: Boolean) { this.enabled = enabled }
        }

        val viewModel = viewModel(working)
        viewModel.setAutostart(true)

        assertTrue(viewModel.state.value.autostartEnabled)
        assertTrue(viewModel.state.value.autostartSupported)
    }
}
