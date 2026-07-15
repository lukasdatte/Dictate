package net.devemperor.dictate.companion.platform

import net.devemperor.dictate.companion.platform.windows.JnaWin32Keyboard
import net.devemperor.dictate.companion.platform.windows.KeyEventSpec
import net.devemperor.dictate.companion.platform.windows.Win32Keyboard
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Ctrl+V key-event sequence across the refactor onto the generic [Win32Keyboard.sendKeySequence].
 *
 * `SendInput` itself cannot run on this Linux VM, but the *event list* it is handed is plain data
 * and IS the behaviour that must be preserved: VK_CONTROL down, 'V' down, 'V' up, VK_CONTROL up.
 * Written red-before-green — before the refactor there is no shared sequence to assert (rot-vor-grün).
 */
class Win32KeyboardSequenceTest {

    private val VK_CONTROL = 0x11
    private val VK_V = 0x56

    @Test
    fun ctrlVSequence_isControlDown_vDown_vUp_controlUp() {
        assertEquals(Win32Keyboard.CTRL_V_EVENT_COUNT, JnaWin32Keyboard.CTRL_V_SEQUENCE.size)
        assertEquals(
            listOf(
                KeyEventSpec(VK_CONTROL, keyUp = false),
                KeyEventSpec(VK_V, keyUp = false),
                KeyEventSpec(VK_V, keyUp = true),
                KeyEventSpec(VK_CONTROL, keyUp = true),
            ),
            JnaWin32Keyboard.CTRL_V_SEQUENCE,
        )
    }
}
