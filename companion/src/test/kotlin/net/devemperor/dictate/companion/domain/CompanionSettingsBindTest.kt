package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.data.memory.InMemorySettings
import net.devemperor.dictate.companion.domain.net.BindSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bind-address migration rules — the "never overwrite a deliberate choice" contract.
 *
 * `storedBindSelection` is deliberately nullable: `null` means "the user never configured this",
 * which is the *only* signal that lets Main run first-setup (Tailscale default) exactly once. A
 * garbage row is treated as unconfigured rather than crashing, matching the table's
 * hand-editability (`CompanionSettings` reads tolerate garbage everywhere).
 */
class CompanionSettingsBindTest {

    private val store = InMemorySettings()
    private val settings = CompanionSettings(store)

    @Test
    fun emptyStore_isUnconfigured() {
        assertNull(settings.storedBindSelection)
    }

    @Test
    fun explicitMode_isReadBackVerbatim() {
        settings.bindSelection = BindSelection.Explicit(setOf("100.66.155.18", "192.168.1.5"))

        // A second instance reads the same table — persistence, not just in-memory state.
        val reread = CompanionSettings(store).storedBindSelection
        assertEquals(BindSelection.Explicit(setOf("100.66.155.18", "192.168.1.5")), reread)
    }

    @Test
    fun allInterfacesMode_isReadBack() {
        settings.bindSelection = BindSelection.AllInterfaces
        assertEquals(BindSelection.AllInterfaces, CompanionSettings(store).storedBindSelection)
    }

    @Test
    fun legacyManualAddress_migratesToExplicit_andIsNotOverwritten() {
        // A bestand install with a hand-set address. The Tailscale default must NOT touch it.
        store.put("server.bind", "192.168.1.5")

        assertEquals(BindSelection.Explicit(setOf("192.168.1.5")), settings.storedBindSelection)
    }

    @Test
    fun legacyZeros_migratesToAllInterfaces_notTailscale() {
        // Indistinguishable from the old default; treated as a deliberate AllInterfaces so an update
        // never silently strips LAN reachability. The one-click Tailscale suggestion lives in the UI.
        store.put("server.bind", "0.0.0.0")

        assertEquals(BindSelection.AllInterfaces, settings.storedBindSelection)
    }

    @Test
    fun explicitMode_winsOverLegacyKey() {
        store.put("server.bind", "192.168.1.5")
        settings.bindSelection = BindSelection.Explicit(setOf("100.66.155.18"))

        assertEquals(BindSelection.Explicit(setOf("100.66.155.18")), CompanionSettings(store).storedBindSelection)
    }

    @Test
    fun garbageMode_andGarbageAddresses_areTreatedAsUnconfigured_notACrash() {
        store.put("server.bind.mode", "yes")
        store.put("server.bind.addresses", ",, ,")

        assertNull(settings.storedBindSelection)
    }

    @Test
    fun explicitMode_withNoValidAddresses_isUnconfigured() {
        store.put("server.bind.mode", "explicit")
        store.put("server.bind.addresses", "not.an.ip")

        assertNull(settings.storedBindSelection)
    }

    @Test
    fun explicitMode_dropsGarbageButKeepsValidAddresses() {
        store.put("server.bind.mode", "explicit")
        store.put("server.bind.addresses", "100.66.155.18, not.an.ip ,192.168.1.5")

        assertEquals(
            BindSelection.Explicit(setOf("100.66.155.18", "192.168.1.5")),
            settings.storedBindSelection,
        )
    }
}
