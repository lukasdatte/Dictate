package net.devemperor.dictate.companion.platform

import net.devemperor.dictate.companion.hotkey.HotkeyCombo
import net.devemperor.dictate.companion.platform.windows.Win32GlobalHotkey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure half of the Win32 hotkey (§6.1): [HotkeyCombo] → `RegisterHotKey` modifier flags. The
 * message loop itself is Windows-only and covered by the F1 manual checklist (TC-W1) — pattern
 * `Win32KeyboardSequenceTest`: test the branch-free translation here, leave `user32.dll` to Windows.
 */
class Win32GlobalHotkeyTest {

    @Test
    fun modifierFlags_matchTheWin32Vocabulary() {
        assertEquals(Win32GlobalHotkey.MOD_CONTROL or Win32GlobalHotkey.MOD_ALT,
            Win32GlobalHotkey.win32Modifiers(HotkeyCombo(ctrl = true, alt = true, vk = 0x20)))
        assertEquals(Win32GlobalHotkey.MOD_SHIFT,
            Win32GlobalHotkey.win32Modifiers(HotkeyCombo(shift = true, vk = 0x41)))
        assertEquals(Win32GlobalHotkey.MOD_WIN,
            Win32GlobalHotkey.win32Modifiers(HotkeyCombo(win = true, vk = 0x41)))
        assertEquals(0, Win32GlobalHotkey.win32Modifiers(HotkeyCombo(vk = 0x41)))
    }

    @Test
    fun win32Constants_areTheDocumentedValues() {
        // RegisterHotKey's contract, not ours — a typo here would register the wrong combo.
        assertEquals(0x0001, Win32GlobalHotkey.MOD_ALT)
        assertEquals(0x0002, Win32GlobalHotkey.MOD_CONTROL)
        assertEquals(0x0004, Win32GlobalHotkey.MOD_SHIFT)
        assertEquals(0x0008, Win32GlobalHotkey.MOD_WIN)
        assertEquals(0x0312, Win32GlobalHotkey.WM_HOTKEY)
    }
}
