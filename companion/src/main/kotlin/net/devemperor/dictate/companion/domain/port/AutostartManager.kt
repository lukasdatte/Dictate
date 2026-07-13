package net.devemperor.dictate.companion.domain.port

/**
 * Whether the companion starts with the user's session.
 *
 * Windows: an `HKCU\…\Run` value (`WinRegistryAutostart`). Everywhere else: a no-op that honestly
 * reports `false` rather than pretending — the UI reads [isEnabled] back after every [setEnabled],
 * so a lying implementation would show a toggle that silently snaps back.
 */
interface AutostartManager {

    fun isEnabled(): Boolean

    fun setEnabled(enabled: Boolean)

    /** false → the settings screen disables the toggle instead of offering a lie. */
    val supported: Boolean
}
