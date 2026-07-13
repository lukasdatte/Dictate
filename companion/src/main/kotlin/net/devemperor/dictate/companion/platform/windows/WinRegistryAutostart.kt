package net.devemperor.dictate.companion.platform.windows

import net.devemperor.dictate.companion.domain.port.AutostartManager

/**
 * "Start with the computer" — one string value under the HKCU Run key (ADR-0018).
 *
 * ```
 * HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run
 *   DictateCompanion = "C:\Program Files\DictateCompanion\DictateCompanion.exe" --minimized
 * ```
 *
 * **Every operation swallows its exception and reports the truth afterwards.** A registry write can
 * fail — a corporate policy, a locked hive, an antivirus watching that exact key — and Windows says
 * so by throwing. The UI reads [isEnabled] back after every [setEnabled] precisely because of that:
 * a toggle that flips while nothing was written is worse than one that refuses to move, because the
 * user believes their PC will start the companion after the next reboot and it will not.
 *
 * `--minimized` starts the app straight into the tray. Without it, every login would open a window
 * the user did not ask for — an autostart that announces itself is an autostart that gets removed.
 */
class WinRegistryAutostart(
    private val commandLine: String,
    private val registry: WindowsRegistry = JnaWindowsRegistry,
) : AutostartManager {

    override val supported: Boolean = true

    override fun isEnabled(): Boolean = try {
        registry.valueExists(RUN_KEY, VALUE_NAME)
    } catch (e: Exception) {
        false
    }

    override fun setEnabled(enabled: Boolean) {
        try {
            if (enabled) {
                registry.setString(RUN_KEY, VALUE_NAME, commandLine)
            } else if (isEnabled()) {
                // Deleting a value that is not there throws on Windows; the app should not.
                registry.deleteValue(RUN_KEY, VALUE_NAME)
            }
        } catch (e: Exception) {
            // Deliberately silent — isEnabled() is the answer, and the UI asks it next.
        }
    }

    companion object {

        const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val VALUE_NAME = "DictateCompanion"
        const val MINIMIZED_FLAG = "--minimized"

        /**
         * The command line Windows will run at login.
         *
         * The path is **always quoted**: `C:\Program Files\…` contains a space, and an unquoted
         * value makes Windows try to run `C:\Program`. This is the single most common way a Run-key
         * entry silently does nothing.
         *
         * The executable is taken from [ProcessHandle] because inside a jpackage bundle the app runs
         * as `DictateCompanion.exe`, not as `java.exe` — `java.home` would point at the *bundled*
         * runtime and produce an entry that starts a JVM with no application in it.
         */
        fun commandLineFor(executable: String): String = "\"$executable\" $MINIMIZED_FLAG"

        fun currentExecutable(): String? =
            ProcessHandle.current().info().command().orElse(null)
    }
}
