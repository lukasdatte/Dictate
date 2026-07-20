package net.devemperor.dictate.companion.platform.fallback

import net.devemperor.dictate.companion.domain.port.ForegroundWindows
import net.devemperor.dictate.companion.domain.port.WindowHandle

/**
 * Linux/macOS: no focus steering — insertion there is clipboard-only anyway (ADR-0018), so there is
 * no foreground window worth restoring. `available = false` short-circuits the
 * `FocusRestorationPolicy` into a no-op.
 */
object NoopForegroundWindows : ForegroundWindows {
    override val available: Boolean = false
    override fun foregroundWindow(): WindowHandle? = null
    override fun focusWindow(handle: WindowHandle): Boolean = false
}
