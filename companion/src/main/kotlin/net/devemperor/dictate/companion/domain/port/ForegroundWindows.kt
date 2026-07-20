package net.devemperor.dictate.companion.domain.port

/**
 * An opaque OS window identity (a Win32 `HWND` on Windows). The domain never dereferences it — it
 * only remembers and hands it back, so the type is a plain value the Linux test suite can mint.
 */
data class WindowHandle(val value: Long)

/**
 * Read/steer which OS window is in the foreground — the raw API pair behind the
 * `FocusRestorationPolicy` fallback (desktop-host.md §6.3): remember the foreground window when the
 * dictation hotkey fires, put it back in front before the text insert.
 *
 * A **port** (ADR-0018): `Win32ForegroundWindows` (JNA `GetForegroundWindow`/`SetForegroundWindow`)
 * on Windows, `NoopForegroundWindows` elsewhere, `FakeForegroundWindows` in tests — which is what
 * makes the policy's remember/restore order unit-testable on this Linux VM (acceptance §2
 * criterion 8).
 */
interface ForegroundWindows {

    /** false → this platform cannot steer window focus; the policy degrades to a no-op. */
    val available: Boolean

    /** The window currently in the foreground, or `null` when nothing has focus (screen lock). */
    fun foregroundWindow(): WindowHandle?

    /** Brings [handle] back to the foreground. false when the OS refused (window gone, UIPI). */
    fun focusWindow(handle: WindowHandle): Boolean
}
