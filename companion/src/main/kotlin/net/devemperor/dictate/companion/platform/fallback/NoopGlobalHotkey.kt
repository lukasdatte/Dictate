package net.devemperor.dictate.companion.platform.fallback

import net.devemperor.dictate.companion.hotkey.GlobalHotkey
import net.devemperor.dictate.companion.hotkey.HotkeyCombo

/**
 * Linux/macOS: no portable system-wide hotkey API worth its dependencies (spec §6.1, R4 — prefer the
 * JNA hand-roll over new libraries; X11/Wayland grabs are a different, larger story). The app still
 * runs — dictation is triggered from the tray menu or the panel button (F6), and `available = false`
 * lets the UI say so instead of pretending.
 */
object NoopGlobalHotkey : GlobalHotkey {
    override val available: Boolean = false
    override fun register(combo: HotkeyCombo, onTrigger: () -> Unit): Boolean = false
    override fun unregister() = Unit
}
