package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.domain.net.BindSelection
import net.devemperor.dictate.companion.domain.net.Ipv4
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

    /**
     * The persisted bind choice, or `null` when the user has never configured one.
     *
     * The nullability is the whole migration contract: `null` is the *only* signal that lets the
     * caller run first-setup (the Tailscale default) exactly once. Reading order — an explicit
     * `server.bind.mode` wins; otherwise the legacy `server.bind` key is migrated (a manual address
     * becomes [BindSelection.Explicit] and is thus preserved, a bare `0.0.0.0` becomes
     * [BindSelection.AllInterfaces] rather than being narrowed silently); anything corrupt or absent
     * reads as `null`, so a hand-mangled table self-heals into first-setup instead of crashing.
     */
    val storedBindSelection: BindSelection?
        get() = when (settings.get(KEY_BIND_MODE)) {
            MODE_ALL -> BindSelection.AllInterfaces
            MODE_EXPLICIT -> readExplicitAddresses()?.let { BindSelection.Explicit(it) }
            else -> migrateLegacyBind()
        }

    /** The effective selection: [storedBindSelection], or all-interfaces when never configured. */
    var bindSelection: BindSelection
        get() = storedBindSelection ?: BindSelection.AllInterfaces
        set(value) = when (value) {
            is BindSelection.AllInterfaces -> {
                settings.put(KEY_BIND_MODE, MODE_ALL)
                settings.put(KEY_BIND_ADDRESSES, "")
            }
            is BindSelection.Explicit -> {
                settings.put(KEY_BIND_MODE, MODE_EXPLICIT)
                settings.put(KEY_BIND_ADDRESSES, value.addresses.joinToString(","))
            }
        }

    /** Valid IPv4 literals from the explicit-addresses row, or `null` when none survive. */
    private fun readExplicitAddresses(): Set<String>? =
        settings.get(KEY_BIND_ADDRESSES)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { Ipv4.isValid(it) }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }

    private fun migrateLegacyBind(): BindSelection? {
        val legacy = settings.get(KEY_BIND)?.takeIf { it.isNotBlank() } ?: return null
        return if (legacy == BIND_ALL) BindSelection.AllInterfaces else BindSelection.Explicit(setOf(legacy))
    }

    var clipboardRestoreDelayMillis: Long
        get() = settings.get(KEY_RESTORE_DELAY)?.toLongOrNull()?.takeIf { it in 0..MAX_RESTORE_DELAY_MILLIS }
            ?: DEFAULT_RESTORE_DELAY_MILLIS
        set(value) = settings.put(KEY_RESTORE_DELAY, value.toString())

    /** The autostart entry passes `--minimized`; the app then starts straight into the tray. */
    var startMinimized: Boolean
        get() = settings.get(KEY_START_MINIMIZED)?.toBooleanStrictOrNull() ?: false
        set(value) = settings.put(KEY_START_MINIMIZED, value.toString())

    /**
     * The chosen microphone mixer name (desktop-host.md §4.2), or `null` for the system default. A
     * name rather than an index because indices reshuffle when a device is (un)plugged. A blank stored
     * value reads as `null` — clearing the setting means "back to default".
     */
    var audioInputDevice: String?
        get() = settings.get(KEY_AUDIO_DEVICE)?.takeIf { it.isNotBlank() }
        set(value) = settings.put(KEY_AUDIO_DEVICE, value.orEmpty())

    /**
     * How many seconds of audio one rolling WAV segment holds before the capture loop rolls to the
     * next (desktop-host.md §4.3). Bounded so a hand-edited settings row cannot make segments
     * absurdly short (thrashing) or effectively infinite; garbage falls back to the default.
     */
    var rollingSegmentSeconds: Int
        get() = settings.get(KEY_ROLLING_SEGMENT)?.toIntOrNull()?.takeIf { it in MIN_ROLLING_SEGMENT_SEC..MAX_ROLLING_SEGMENT_SEC }
            ?: DEFAULT_ROLLING_SEGMENT_SEC
        set(value) = settings.put(KEY_ROLLING_SEGMENT, value.toString())

    /**
     * The dictation hotkey as its `HotkeyCombo.format()` string (desktop-host.md §6.1), or `null`
     * when never configured / cleared. Stored as a plain string on purpose: the domain stays free of
     * the `hotkey/` vocabulary, and the caller's `HotkeyCombo.parse(...) ?: DEFAULT` gives garbage
     * values the same self-healing fallback every other setting here has.
     */
    var hotkeyCombo: String?
        get() = settings.get(KEY_HOTKEY_COMBO)?.takeIf { it.isNotBlank() }
        set(value) = settings.put(KEY_HOTKEY_COMBO, value.orEmpty())

    /**
     * F21 (desktop-host.md §8.5): when true, an INSERT-verdict dictation waits in the panel for an
     * explicit confirm instead of auto-inserting. Default false — auto-insert is the point of the
     * hotkey flow.
     */
    var confirmBeforeInsert: Boolean
        get() = settings.get(KEY_CONFIRM_BEFORE_INSERT)?.toBooleanStrictOrNull() ?: false
        set(value) = settings.put(KEY_CONFIRM_BEFORE_INSERT, value.toString())

    companion object {

        /** 0.0.0.0 — the `AllInterfaces` host; the legacy `server.bind` default before selection. */
        const val BIND_ALL = "0.0.0.0"
        const val DEFAULT_PORT = 8756

        /**
         * Long enough for the pasted-into application to have read the clipboard, short enough that
         * the user does not notice their own clipboard was borrowed. There is no event to wait for
         * instead — a paste is not acknowledged to the source.
         */
        const val DEFAULT_RESTORE_DELAY_MILLIS = 800L
        const val MAX_RESTORE_DELAY_MILLIS = 10_000L

        /** 30 s matches the phone's ADR-0007 rolling cadence; the bounds keep a hand-edit sane. */
        const val DEFAULT_ROLLING_SEGMENT_SEC = 30
        const val MIN_ROLLING_SEGMENT_SEC = 5
        const val MAX_ROLLING_SEGMENT_SEC = 600

        private const val KEY_PORT = "server.port"

        /** Legacy single-address key — read for migration, never written again. */
        private const val KEY_BIND = "server.bind"
        private const val KEY_BIND_MODE = "server.bind.mode"
        private const val KEY_BIND_ADDRESSES = "server.bind.addresses"
        private const val MODE_ALL = "all"
        private const val MODE_EXPLICIT = "explicit"

        private const val KEY_RESTORE_DELAY = "insertion.clipboardRestoreDelayMillis"
        private const val KEY_START_MINIMIZED = "ui.startMinimized"
        private const val KEY_AUDIO_DEVICE = "audio.inputDevice"
        private const val KEY_ROLLING_SEGMENT = "audio.rollingSegmentSec"
        private const val KEY_HOTKEY_COMBO = "hotkey.combo"
        private const val KEY_CONFIRM_BEFORE_INSERT = "insertion.confirmBeforeInsert"
    }
}
