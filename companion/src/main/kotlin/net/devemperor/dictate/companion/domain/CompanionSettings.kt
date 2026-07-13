package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.domain.port.SettingsRepository

/**
 * The user-changeable settings, typed and defaulted — and the home of the defaults themselves.
 *
 * The defaults live *here*, in the domain, rather than in the server and the platform layers that
 * consume them: those layers depend on the domain, never the other way round, and a default that
 * sat in `CompanionServer` could not be read by a settings screen without inverting that.
 *
 * Every read tolerates a garbage value by falling back to the default. The settings table is plain
 * SQLite the user could in principle edit by hand; a companion that refuses to start because someone
 * typed "eight thousand" into the port row would be a bad trade.
 *
 * The port and the bind address take effect **on the next start** — rebinding a live socket under a
 * paired phone would drop an in-flight dispatch, and the UI says so instead of pretending otherwise.
 */
class CompanionSettings(private val settings: SettingsRepository) {

    var port: Int
        get() = settings.get(KEY_PORT)?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: DEFAULT_PORT
        set(value) = settings.put(KEY_PORT, value.toString())

    var bindAddress: String
        get() = settings.get(KEY_BIND)?.takeIf { it.isNotBlank() } ?: DEFAULT_BIND_ADDRESS
        set(value) = settings.put(KEY_BIND, value)

    var clipboardRestoreDelayMillis: Long
        get() = settings.get(KEY_RESTORE_DELAY)?.toLongOrNull()?.takeIf { it in 0..MAX_RESTORE_DELAY_MILLIS }
            ?: DEFAULT_RESTORE_DELAY_MILLIS
        set(value) = settings.put(KEY_RESTORE_DELAY, value.toString())

    /** The autostart entry passes `--minimized`; the app then starts straight into the tray. */
    var startMinimized: Boolean
        get() = settings.get(KEY_START_MINIMIZED)?.toBooleanStrictOrNull() ?: false
        set(value) = settings.put(KEY_START_MINIMIZED, value.toString())

    companion object {

        /** 0.0.0.0 — the phone reaches the PC over the tailnet interface, not over loopback. */
        const val DEFAULT_BIND_ADDRESS = "0.0.0.0"
        const val DEFAULT_PORT = 8756

        /**
         * Long enough for the pasted-into application to have read the clipboard, short enough that
         * the user does not notice their own clipboard was borrowed. There is no event to wait for
         * instead — a paste is not acknowledged to the source.
         */
        const val DEFAULT_RESTORE_DELAY_MILLIS = 800L
        const val MAX_RESTORE_DELAY_MILLIS = 10_000L

        private const val KEY_PORT = "server.port"
        private const val KEY_BIND = "server.bind"
        private const val KEY_RESTORE_DELAY = "insertion.clipboardRestoreDelayMillis"
        private const val KEY_START_MINIMIZED = "ui.startMinimized"
    }
}
