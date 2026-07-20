package net.devemperor.dictate.companion.hotkey

/**
 * A system-wide hotkey the dictation host listens on even while another application has focus
 * (desktop-host.md §6.1) — a **port** in the ADR-0018 style.
 *
 * Implementations: `Win32GlobalHotkey` (`RegisterHotKey` + message loop) on Windows,
 * `NoopGlobalHotkey` everywhere else (`available = false`; on Linux the user triggers dictation via
 * the tray menu or the panel button instead, F6). Tests use `FakeGlobalHotkey` with a manual
 * `trigger()`.
 */
interface GlobalHotkey {

    /** false → no OS-level hotkey on this platform; the UI offers tray/button triggers instead. */
    val available: Boolean

    /**
     * Registers [combo] system-wide; [onTrigger] fires on every press (possibly on a background
     * thread — callers hand it something thread-safe, e.g. `DesktopDictationController.dispatch`).
     * Re-registering replaces the previous combo. Returns false when the OS refused the combo
     * (typically: already taken by another application).
     */
    fun register(combo: HotkeyCombo, onTrigger: () -> Unit): Boolean

    /** Releases the registration (idempotent). */
    fun unregister()
}
