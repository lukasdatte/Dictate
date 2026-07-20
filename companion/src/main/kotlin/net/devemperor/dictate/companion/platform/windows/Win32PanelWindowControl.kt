package net.devemperor.dictate.companion.platform.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import net.devemperor.dictate.companion.domain.port.ForegroundWindows
import net.devemperor.dictate.companion.domain.port.WindowHandle

/**
 * The Win32 half of the focus-free panel (desktop-host.md §6.3): the `WS_EX_NOACTIVATE` spike styler
 * and the foreground-window port behind the `FocusRestorationPolicy` fallback.
 *
 * Compile-verified on Linux, behaviour-verified only on Windows (F1 checklist, TC-W1). The panel
 * window itself is plain cross-platform Compose (`ui/panel/PanelWindow.kt`); only these two leaf
 * objects touch `user32.dll`.
 */
object Win32WindowStyler {

    const val GWL_EXSTYLE = -20
    const val WS_EX_NOACTIVATE = 0x08000000
    /** Keeps the panel out of Alt-Tab — a transient dictation bar is not a task (spec §6.3). */
    const val WS_EX_TOOLWINDOW = 0x00000080

    /**
     * ORs `WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW` into [window]'s extended style (spec §6.3 spike,
     * step 2). Returns true when the style verifiably stuck (read back after write) — that is the
     * *applied* signal, not yet the spike verdict; whether the style actually keeps Compose from
     * taking focus is decided manually on Windows (TC-W1) and gated by
     * `ComposePanelWindowControl.FOCUS_SPIKE_VERIFIED`.
     */
    fun applyFocusFreeStyle(window: java.awt.Window): Boolean = try {
        val hwnd = WinDef.HWND(Native.getWindowPointer(window))
        val current = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE)
        User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE, current or WS_EX_NOACTIVATE or WS_EX_TOOLWINDOW)
        (User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE) and WS_EX_NOACTIVATE) != 0
    } catch (e: Throwable) {
        // A missing native handle (window not displayable yet) or a JNA failure must degrade to the
        // focus-restoration fallback, never crash the panel (D4.3: spike failure is no escalation).
        false
    }
}

/** JNA `GetForegroundWindow`/`SetForegroundWindow` — the raw pair behind the §6.3 fallback. */
object Win32ForegroundWindows : ForegroundWindows {

    override val available: Boolean = true

    override fun foregroundWindow(): WindowHandle? =
        User32.INSTANCE.GetForegroundWindow()?.let { WindowHandle(Pointer.nativeValue(it.pointer)) }

    override fun focusWindow(handle: WindowHandle): Boolean =
        User32.INSTANCE.SetForegroundWindow(WinDef.HWND(Pointer(handle.value)))
}
