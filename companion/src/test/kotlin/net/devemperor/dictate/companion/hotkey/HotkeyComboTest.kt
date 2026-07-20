package net.devemperor.dictate.companion.hotkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The persisted hotkey representation (desktop-host.md §6.1): tolerant parse, stable format. */
class HotkeyComboTest {

    @Test
    fun default_isCtrlAltSpace() {
        assertEquals(HotkeyCombo(ctrl = true, alt = true, vk = 0x20), HotkeyCombo.DEFAULT)
        assertTrue(HotkeyCombo.DEFAULT.hasModifier)
    }

    @Test
    fun formatParse_roundTripsEveryModifierCombination() {
        val combos = listOf(
            HotkeyCombo(ctrl = true, alt = true, vk = 0x20),
            HotkeyCombo(shift = true, win = true, vk = 0x44),
            HotkeyCombo(ctrl = true, alt = true, shift = true, win = true, vk = 0x7B), // F12
        )
        combos.forEach { combo ->
            assertEquals("round-trip of ${combo.format()}", combo, HotkeyCombo.parse(combo.format()))
        }
    }

    @Test
    fun parse_isCaseInsensitiveAndTrimsWhitespace() {
        assertEquals(HotkeyCombo.DEFAULT, HotkeyCombo.parse(" Ctrl + ALT + 0x20 "))
    }

    @Test
    fun parse_acceptsDecimalKeyCodes() {
        assertEquals(HotkeyCombo(ctrl = true, vk = 32), HotkeyCombo.parse("ctrl+32"))
    }

    @Test
    fun parse_rejectsGarbage() {
        // Callers fall back to DEFAULT on null — a mangled settings row must never lose the hotkey.
        assertNull("blank", HotkeyCombo.parse(""))
        assertNull("null", HotkeyCombo.parse(null))
        assertNull("unknown token", HotkeyCombo.parse("ctrl+banana"))
        assertNull("two keys", HotkeyCombo.parse("ctrl+0x20+0x21"))
        assertNull("no key", HotkeyCombo.parse("ctrl+alt"))
        assertNull("vk out of range", HotkeyCombo.parse("ctrl+0xFFF"))
        assertNull("bare key without modifier would swallow typing", HotkeyCombo.parse("0x20"))
    }
}
