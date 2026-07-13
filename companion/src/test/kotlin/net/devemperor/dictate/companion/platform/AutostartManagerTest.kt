package net.devemperor.dictate.companion.platform

import net.devemperor.dictate.companion.domain.port.AutostartManager
import net.devemperor.dictate.companion.platform.fallback.NoopAutostart
import net.devemperor.dictate.companion.platform.windows.WinRegistryAutostart
import net.devemperor.dictate.companion.platform.windows.WindowsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The [AutostartManager] contract — for both implementations, on Linux.
 *
 * The registry calls sit behind [WindowsRegistry], so what is actually tested here is the part that
 * can be wrong: the quoting of the executable path, the `--minimized` flag, the delete-only-if-present
 * rule, and — the important one — that a *failing* registry write is reported honestly instead of
 * flipping a toggle the user then trusts.
 *
 * What is left for the Windows checklist (item 6) is only whether Windows honours the key on reboot.
 */
class AutostartManagerTest {

    @Test
    fun theNoopHonoursTheContract_byRefusingToLie() {
        val autostart: AutostartManager = NoopAutostart

        autostart.setEnabled(true)

        // It accepted the call and still reports false — because there is no Run key on Linux and
        // pretending there is would show the user a promise nothing can keep.
        assertFalse(autostart.isEnabled())
        assertFalse(autostart.supported)
    }

    @Test
    fun enabling_writesAQuotedCommandLineWithTheMinimizedFlag() {
        val registry = FakeRegistry()
        val autostart = WinRegistryAutostart(
            WinRegistryAutostart.commandLineFor("""C:\Program Files\DictateCompanion\DictateCompanion.exe"""),
            registry,
        )

        autostart.setEnabled(true)

        assertTrue(autostart.isEnabled())
        // The quotes are the whole point: "C:\Program Files\…" unquoted makes Windows try to run
        // `C:\Program` — the single most common way a Run-key entry silently does nothing.
        assertEquals(
            """"C:\Program Files\DictateCompanion\DictateCompanion.exe" --minimized""",
            registry.values[WinRegistryAutostart.RUN_KEY to WinRegistryAutostart.VALUE_NAME],
        )
    }

    @Test
    fun disabling_removesTheValue_andIsHarmlessWhenItIsNotThere() {
        val registry = FakeRegistry()
        val autostart = WinRegistryAutostart("cmd", registry)
        autostart.setEnabled(true)

        autostart.setEnabled(false)
        assertFalse(autostart.isEnabled())

        // Deleting a value that is not there throws on Windows; the app must not.
        autostart.setEnabled(false)
        assertFalse(autostart.isEnabled())
        assertEquals(0, registry.deleteCallsOnMissingValue)
    }

    @Test
    fun aRegistryThatRefusesTheWrite_reportsDisabled_neverASilentPromise() {
        val registry = FakeRegistry(writable = false)
        val autostart = WinRegistryAutostart("cmd", registry)

        autostart.setEnabled(true)

        // A corporate policy or a locked hive makes the write throw. The user must see the toggle
        // snap back, not a happy checkbox over a PC that will never start the companion.
        assertFalse(autostart.isEnabled())
    }

    private class FakeRegistry(private val writable: Boolean = true) : WindowsRegistry {

        val values = mutableMapOf<Pair<String, String>, String>()
        var deleteCallsOnMissingValue = 0

        override fun valueExists(key: String, name: String): Boolean = (key to name) in values

        override fun setString(key: String, name: String, value: String) {
            if (!writable) throw IllegalStateException("access denied")
            values[key to name] = value
        }

        override fun deleteValue(key: String, name: String) {
            if (values.remove(key to name) == null) {
                deleteCallsOnMissingValue++
                throw IllegalArgumentException("no such value") // what Windows does
            }
        }
    }
}
