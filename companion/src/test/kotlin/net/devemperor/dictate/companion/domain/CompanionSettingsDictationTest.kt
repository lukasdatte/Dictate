package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.data.memory.InMemorySettings
import net.devemperor.dictate.companion.hotkey.HotkeyCombo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The D2 dictation settings: `hotkey.combo` (§6.1) + `insertion.confirmBeforeInsert` (F21, §8.5). */
class CompanionSettingsDictationTest {

    private val settings = CompanionSettings(InMemorySettings())

    @Test
    fun hotkeyCombo_defaultsToNull_callersFallBackToTheDefaultCombo() {
        assertNull(settings.hotkeyCombo)
        assertEquals(HotkeyCombo.DEFAULT, HotkeyCombo.parse(settings.hotkeyCombo) ?: HotkeyCombo.DEFAULT)
    }

    @Test
    fun hotkeyCombo_roundTripsAFormattedCombo() {
        val combo = HotkeyCombo(ctrl = true, shift = true, vk = 0x44)
        settings.hotkeyCombo = combo.format()
        assertEquals(combo, HotkeyCombo.parse(settings.hotkeyCombo))
    }

    @Test
    fun hotkeyCombo_blankReadsAsNull_clearingRestoresTheDefault() {
        settings.hotkeyCombo = "ctrl+0x42"
        settings.hotkeyCombo = null
        assertNull(settings.hotkeyCombo)
    }

    @Test
    fun confirmBeforeInsert_defaultsToFalse_autoInsertIsThePoint() {
        assertFalse(settings.confirmBeforeInsert)
    }

    @Test
    fun confirmBeforeInsert_roundTrips_andGarbageFallsBackToDefault() {
        settings.confirmBeforeInsert = true
        assertTrue(settings.confirmBeforeInsert)
        settings.confirmBeforeInsert = false
        assertFalse(settings.confirmBeforeInsert)
    }
}
