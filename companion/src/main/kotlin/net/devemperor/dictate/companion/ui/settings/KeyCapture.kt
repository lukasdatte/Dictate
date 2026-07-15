package net.devemperor.dictate.companion.ui.settings

/**
 * Turns a captured AWT virtual-key code into the Win32 VK code the companion injects.
 *
 * AWT's `KeyEvent.VK_*` codes coincide with Win32 VK codes for the keys a user is likely to rebind —
 * letters (A–Z), digits, the arrows, Backspace, Space — but a handful diverge; those are corrected
 * here. Pure so the mapping is unit-tested without an AWT event (§5.4 "pur testbar").
 *
 * Known limitation (documented): an exotic key whose AWT code has no Win32 twin is passed through
 * unchanged and may mis-inject — the manual checklist (2b) is the real acceptance for rebinding.
 */
object KeyCapture {

    private val AWT_TO_WIN32: Map<Int, Int> = mapOf(
        0x0A to 0x0D, // AWT VK_ENTER (10) → Win32 VK_RETURN (13)
    )

    fun win32VkFor(awtKeyCode: Int): Int = AWT_TO_WIN32[awtKeyCode] ?: awtKeyCode
}
