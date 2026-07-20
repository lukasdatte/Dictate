package net.devemperor.dictate.companion.platform

import com.sun.jna.Platform
import net.devemperor.dictate.companion.domain.port.AutostartManager
import net.devemperor.dictate.companion.domain.port.ChordMappingRepository
import net.devemperor.dictate.companion.domain.port.ClipboardPort
import net.devemperor.dictate.companion.domain.port.ForegroundWindows
import net.devemperor.dictate.companion.domain.port.InputCommandPerformer
import net.devemperor.dictate.companion.domain.port.TextInserter
import net.devemperor.dictate.companion.hotkey.GlobalHotkey
import net.devemperor.dictate.companion.platform.fallback.NoopAutostart
import net.devemperor.dictate.companion.platform.fallback.NoopForegroundWindows
import net.devemperor.dictate.companion.platform.fallback.NoopGlobalHotkey
import net.devemperor.dictate.companion.platform.fallback.NoopInputCommandPerformer
import net.devemperor.dictate.companion.platform.fallback.NoopTextInserter
import net.devemperor.dictate.companion.platform.windows.AwtClipboard
import net.devemperor.dictate.companion.platform.windows.JnaWin32Keyboard
import net.devemperor.dictate.companion.platform.windows.Win32ForegroundWindows
import net.devemperor.dictate.companion.platform.windows.Win32GlobalHotkey
import net.devemperor.dictate.companion.platform.windows.Win32InputPerformer
import net.devemperor.dictate.companion.platform.windows.Win32TextInserter
import net.devemperor.dictate.companion.platform.windows.Win32WindowStyler
import net.devemperor.dictate.companion.platform.windows.WinRegistryAutostart

/**
 * The one place the app asks what operating system it is on (ADR-0018).
 *
 * Everywhere else the OS is invisible, because everything OS-specific sits behind a port. That is
 * what lets the companion **build, run and be tested on this Linux VM** while shipping Win32
 * insertion: on Linux it wires the no-ops, the app starts, serves, stores and displays its history,
 * and honestly reports `canInsert = false` instead of pretending.
 *
 * Adding macOS insertion later means one more branch here and one more implementation — not a
 * change to a single service, route or screen.
 */
object PlatformModule {

    data class Bindings(
        val inserter: TextInserter,
        val clipboard: ClipboardPort,
        val autostart: AutostartManager,
        /**
         * A **factory**, not an instance: the input performer resolves chords through a
         * [ChordMappingRepository], and that repository is owned by the container (DB-backed, §B2) —
         * not knowable at OS-detection time. The container hands its repository in here.
         */
        val inputPerformer: (ChordMappingRepository) -> InputCommandPerformer,
        /** The system-wide dictation hotkey (desktop-host.md §6.1); Noop off-Windows (tray/F6). */
        val globalHotkey: GlobalHotkey = NoopGlobalHotkey,
        /** Foreground-window read/steer for the §6.3 focus-restoration fallback. */
        val foregroundWindows: ForegroundWindows = NoopForegroundWindows,
        /** Applies the `WS_EX_NOACTIVATE` spike style to the panel window; `false` = not applied. */
        val panelStyler: (java.awt.Window) -> Boolean = { false },
    )

    fun detect(): Bindings = if (Platform.isWindows()) windows() else fallback()

    private fun windows(): Bindings {
        val clipboard = AwtClipboard()
        val executable = WinRegistryAutostart.currentExecutable()
        val inserter = Win32TextInserter(clipboard)

        return Bindings(
            inserter = inserter,
            clipboard = clipboard,
            // Reuses the same Ctrl+V inserter for TYPE_TEXT — one insertion path, one set of gotchas.
            inputPerformer = { chords -> Win32InputPerformer(JnaWin32Keyboard, inserter, chords) },
            // No resolvable executable path (an exotic launcher, a JVM started by hand) means there
            // is nothing honest to put in the Run key — so the toggle reports "not supported" rather
            // than writing a command line that would fail silently at the next login.
            autostart = executable
                ?.let { WinRegistryAutostart(WinRegistryAutostart.commandLineFor(it)) }
                ?: NoopAutostart,
            globalHotkey = Win32GlobalHotkey(),
            foregroundWindows = Win32ForegroundWindows,
            panelStyler = Win32WindowStyler::applyFocusFreeStyle,
        )
    }

    /**
     * Linux/macOS. The clipboard is still the real AWT one — copying a received text to the
     * clipboard works perfectly well here; it is only the *keystroke injection* that is Windows-only.
     * Handing out a no-op clipboard would take away a feature that costs nothing to keep.
     */
    private fun fallback() = Bindings(
        inserter = NoopTextInserter,
        clipboard = AwtClipboard(),
        autostart = NoopAutostart,
        inputPerformer = { NoopInputCommandPerformer },
    )
}
