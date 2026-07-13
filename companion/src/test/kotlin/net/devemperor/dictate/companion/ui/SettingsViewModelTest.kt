package net.devemperor.dictate.companion.ui

import net.devemperor.dictate.companion.data.memory.InMemorySettings
import net.devemperor.dictate.companion.domain.CompanionSettings
import net.devemperor.dictate.companion.domain.port.AutostartManager
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

    @Test
    fun defaultsHold_whenNothingWasEverSaved() {
        val state = SettingsViewModel(settings, NoopAutostart).state.value

        assertEquals(CompanionSettings.DEFAULT_PORT, state.port)
        assertEquals(CompanionSettings.DEFAULT_BIND_ADDRESS, state.bindAddress)
        assertFalse(state.restartRequired)
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
        val viewModel = SettingsViewModel(settings, NoopAutostart)

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
        val viewModel = SettingsViewModel(settings, NoopAutostart)

        viewModel.setPort("70000")

        assertNotNull(viewModel.state.value.portError)
        assertEquals(CompanionSettings.DEFAULT_PORT, settings.port)
        assertFalse(viewModel.state.value.restartRequired)
    }

    @Test
    fun theRestoreDelayIsClamped() {
        val viewModel = SettingsViewModel(settings, NoopAutostart)

        viewModel.setClipboardRestoreDelay(999_999)

        assertEquals(CompanionSettings.MAX_RESTORE_DELAY_MILLIS, viewModel.state.value.clipboardRestoreDelayMillis)
    }

    @Test
    fun anAutostartWriteThatDoesNotStick_doesNotFlipTheToggle() {
        // NoopAutostart accepts setEnabled(true) and still reports false — precisely the shape of a
        // failed registry write. The toggle must not lie about it.
        val viewModel = SettingsViewModel(settings, NoopAutostart)

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

        val viewModel = SettingsViewModel(settings, working)
        viewModel.setAutostart(true)

        assertTrue(viewModel.state.value.autostartEnabled)
        assertTrue(viewModel.state.value.autostartSupported)
    }
}
