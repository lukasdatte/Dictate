package net.devemperor.dictate.companion.hotkey

/**
 * A global dictation hotkey: modifier flags plus a Win32 virtual-key code (desktop-host.md §6.1).
 *
 * Pure data, no JNA — the Win32 `MOD_*`/`RegisterHotKey` translation lives in
 * `platform/windows/Win32GlobalHotkey.kt`, so this type is usable (and testable) on any OS. The
 * combo is persisted in `CompanionSettings` (`hotkey.combo`) via [format]/[parse].
 */
data class HotkeyCombo(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val win: Boolean = false,
    /** Win32 virtual-key code of the non-modifier key (e.g. `0x20` = VK_SPACE). */
    val vk: Int,
) {

    /** True when at least one modifier is held — a bare-key global hotkey would swallow typing. */
    val hasModifier: Boolean get() = ctrl || alt || shift || win

    /** Stable settings representation, e.g. `ctrl+alt+0x20`. Inverse of [parse]. */
    fun format(): String = buildString {
        if (ctrl) append("$TOKEN_CTRL+")
        if (alt) append("$TOKEN_ALT+")
        if (shift) append("$TOKEN_SHIFT+")
        if (win) append("$TOKEN_WIN+")
        append("0x%02X".format(vk))
    }

    companion object {

        /** Ctrl+Alt+Space (spec §6.1's suggested default). VK_SPACE = 0x20. */
        val DEFAULT = HotkeyCombo(ctrl = true, alt = true, vk = 0x20)

        private const val TOKEN_CTRL = "ctrl"
        private const val TOKEN_ALT = "alt"
        private const val TOKEN_SHIFT = "shift"
        private const val TOKEN_WIN = "win"

        /**
         * Parses a [format]-style string, tolerantly (case-insensitive, whitespace-trimmed). Returns
         * `null` for anything unusable — a blank value, an unknown token, a key code outside the VK
         * range, or a combo without any modifier. Callers fall back to [DEFAULT]; a hand-mangled
         * settings row must never leave the app without a hotkey.
         */
        fun parse(value: String?): HotkeyCombo? {
            val tokens = value?.split('+')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
            if (tokens.isNullOrEmpty()) return null

            var ctrl = false
            var alt = false
            var shift = false
            var win = false
            var vk: Int? = null
            tokens.forEach { token ->
                when (token) {
                    TOKEN_CTRL -> ctrl = true
                    TOKEN_ALT -> alt = true
                    TOKEN_SHIFT -> shift = true
                    TOKEN_WIN -> win = true
                    else -> {
                        if (vk != null) return null // two non-modifier keys is not a combo
                        vk = parseVk(token) ?: return null
                    }
                }
            }
            val key = vk ?: return null
            val combo = HotkeyCombo(ctrl, alt, shift, win, key)
            return combo.takeIf { it.hasModifier }
        }

        private fun parseVk(token: String): Int? {
            val parsed = if (token.startsWith("0x")) token.removePrefix("0x").toIntOrNull(16) else token.toIntOrNull()
            return parsed?.takeIf { it in 1..0xFE } // valid Win32 VK range
        }
    }
}
