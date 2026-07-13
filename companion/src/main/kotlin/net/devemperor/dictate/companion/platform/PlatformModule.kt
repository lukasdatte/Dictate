package net.devemperor.dictate.companion.platform

import com.sun.jna.Platform
import net.devemperor.dictate.companion.domain.port.AutostartManager
import net.devemperor.dictate.companion.domain.port.TextInserter
import net.devemperor.dictate.companion.platform.fallback.NoopAutostart
import net.devemperor.dictate.companion.platform.fallback.NoopTextInserter
import net.devemperor.dictate.companion.platform.windows.AwtClipboard
import net.devemperor.dictate.companion.platform.windows.Win32TextInserter

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
        val autostart: AutostartManager,
    )

    fun detect(): Bindings = if (Platform.isWindows()) windows() else fallback()

    private fun windows() = Bindings(
        inserter = Win32TextInserter(AwtClipboard()),
        // The HKCU Run-key implementation lands with wd-9.
        autostart = NoopAutostart,
    )

    private fun fallback() = Bindings(
        inserter = NoopTextInserter,
        autostart = NoopAutostart,
    )
}
