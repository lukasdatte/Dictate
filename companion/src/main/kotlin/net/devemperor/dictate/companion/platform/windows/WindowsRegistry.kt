package net.devemperor.dictate.companion.platform.windows

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * The three registry calls autostart needs — the same seam pattern as [Win32Keyboard].
 *
 * The *policy* (what value to write, how to quote the path, what to do when a write silently fails)
 * is ordinary Kotlin and is tested on Linux; only these three JNA calls are Windows-only, and there
 * is no branching left inside them to get wrong.
 */
interface WindowsRegistry {

    fun valueExists(key: String, name: String): Boolean

    fun setString(key: String, name: String, value: String)

    fun deleteValue(key: String, name: String)
}

/**
 * `HKEY_CURRENT_USER`, never `HKEY_LOCAL_MACHINE`.
 *
 * HKCU needs no administrator rights, so the autostart toggle works for the user who installed the
 * app — which is the only user whose session this app belongs in. Writing HKLM would demand an
 * elevation prompt for a checkbox, and would start the companion for *every* account on the machine,
 * including ones that never paired a phone.
 *
 * Compile-verified on Linux, behaviour-verified on Windows (checklist item 6).
 */
object JnaWindowsRegistry : WindowsRegistry {

    override fun valueExists(key: String, name: String): Boolean =
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, name)

    override fun setString(key: String, name: String, value: String) =
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, name, value)

    override fun deleteValue(key: String, name: String) =
        Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, key, name)
}
