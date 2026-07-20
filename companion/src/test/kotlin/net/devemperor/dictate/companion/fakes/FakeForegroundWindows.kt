package net.devemperor.dictate.companion.fakes

import net.devemperor.dictate.companion.domain.port.ForegroundWindows
import net.devemperor.dictate.companion.domain.port.WindowHandle

/**
 * A steerable fake of the §6.3 window-focus port. Tests set [foreground] to whatever "the user's
 * editor" is; [focused] records every restore the policy asked for, [onFocus] lets an order-checking
 * test interleave the restore with the insert (acceptance §2 criterion 8).
 */
class FakeForegroundWindows(
    override var available: Boolean = true,
    var foreground: WindowHandle? = null,
    var focusSucceeds: Boolean = true,
) : ForegroundWindows {

    val focused = mutableListOf<WindowHandle>()
    var onFocus: ((WindowHandle) -> Unit)? = null

    override fun foregroundWindow(): WindowHandle? = foreground

    override fun focusWindow(handle: WindowHandle): Boolean {
        focused += handle
        onFocus?.invoke(handle)
        return focusSucceeds
    }
}
