package net.devemperor.dictate.companion.platform.windows

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import net.devemperor.dictate.companion.hotkey.GlobalHotkey
import net.devemperor.dictate.companion.hotkey.HotkeyCombo
import java.util.concurrent.CompletableFuture

/**
 * `RegisterHotKey` against `user32.dll` (desktop-host.md §6.1). Compile-verified on Linux,
 * behaviour-verified only on Windows (F1 checklist, TC-W1).
 *
 * The one non-obvious constraint: **`RegisterHotKey` binds the hotkey to the calling thread**, and
 * `WM_HOTKEY` is delivered to that thread's message queue — so registration, the
 * `GetMessage` loop and `UnregisterHotKey` all have to live on one dedicated thread. [register]
 * spins that thread up and blocks only until the OS accepted (or refused) the combo; [unregister]
 * posts `WM_QUIT` to the loop thread's queue (the documented way to end a `GetMessage` loop from
 * outside) and joins it.
 *
 * [onTrigger] fires on the message-loop thread. That is fine for its one caller — the
 * dictation trigger ends in `DesktopDictationController.dispatch`, which is thread-safe by design.
 */
class Win32GlobalHotkey : GlobalHotkey {

    override val available: Boolean = true

    private var loopThread: Thread? = null

    @Volatile
    private var nativeThreadId: Int = 0

    @Synchronized
    override fun register(combo: HotkeyCombo, onTrigger: () -> Unit): Boolean {
        unregister()
        val accepted = CompletableFuture<Boolean>()
        val thread = Thread({ messageLoop(combo, onTrigger, accepted) }, "dictate-hotkey")
        thread.isDaemon = true
        thread.start()
        val ok = accepted.join()
        if (ok) {
            loopThread = thread
        } else {
            loopThread = null
            nativeThreadId = 0
        }
        return ok
    }

    @Synchronized
    override fun unregister() {
        val thread = loopThread ?: return
        // WM_QUIT makes GetMessage return 0 → the loop unregisters the hotkey and ends.
        User32.INSTANCE.PostThreadMessage(nativeThreadId, WinUser.WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
        thread.join(JOIN_TIMEOUT_MILLIS)
        loopThread = null
        nativeThreadId = 0
    }

    private fun messageLoop(combo: HotkeyCombo, onTrigger: () -> Unit, accepted: CompletableFuture<Boolean>) {
        nativeThreadId = Kernel32.INSTANCE.GetCurrentThreadId()
        // hWnd = null → thread-bound hotkey, WM_HOTKEY lands in this thread's queue.
        val registered = try {
            User32.INSTANCE.RegisterHotKey(null, HOTKEY_ID, win32Modifiers(combo), combo.vk)
        } catch (e: Throwable) {
            accepted.complete(false)
            return
        }
        accepted.complete(registered)
        if (!registered) return

        try {
            val msg = WinUser.MSG()
            while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.message == WM_HOTKEY) onTrigger()
            }
        } finally {
            User32.INSTANCE.UnregisterHotKey(null, HOTKEY_ID)
        }
    }

    companion object {

        // Win32 constants, spelled out rather than pulled from WinUser: referencing them must stay
        // possible in pure-JVM unit tests on Linux without tripping any native class initialisation.
        const val WM_HOTKEY = 0x0312
        const val MOD_ALT = 0x0001
        const val MOD_CONTROL = 0x0002
        const val MOD_SHIFT = 0x0004
        const val MOD_WIN = 0x0008

        /** One fixed id — the companion registers exactly one dictation hotkey. */
        const val HOTKEY_ID = 0xD1C7 // "DICT"

        private const val JOIN_TIMEOUT_MILLIS = 2_000L

        /** Pure [HotkeyCombo] → `RegisterHotKey` modifier-flags translation (unit-tested on Linux). */
        fun win32Modifiers(combo: HotkeyCombo): Int {
            var mods = 0
            if (combo.ctrl) mods = mods or MOD_CONTROL
            if (combo.alt) mods = mods or MOD_ALT
            if (combo.shift) mods = mods or MOD_SHIFT
            if (combo.win) mods = mods or MOD_WIN
            return mods
        }
    }
}
