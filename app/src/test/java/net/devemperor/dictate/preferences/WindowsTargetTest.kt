package net.devemperor.dictate.preferences

import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit tests for [WindowsTarget] — the single "is a PC paired?" gate (ADR-0017/0019).
 *
 * The device secret is the pairing proof: an empty secret (the default) means "not paired",
 * so [WindowsTarget.from] must return null in every not-fully-paired shape.
 */
class WindowsTargetTest {

    private fun paired() = FakeSharedPreferences().apply {
        edit()
            .put(Pref.WindowsTargetUrl, "http://vm-win:8756")
            .put(Pref.WindowsDeviceId, "device-1")
            .put(Pref.WindowsDeviceSecret, "s3cr3t")
            .put(Pref.WindowsServerName, "Office PC")
            .apply()
    }

    @Test
    fun `from returns null on fresh defaults`() {
        assertNull(WindowsTarget.from(FakeSharedPreferences()))
    }

    @Test
    fun `from returns null when secret is empty even with a url`() {
        val sp = FakeSharedPreferences().apply {
            edit().put(Pref.WindowsTargetUrl, "http://vm-win:8756")
                .put(Pref.WindowsDeviceId, "device-1")
                .apply()
        }
        assertNull(WindowsTarget.from(sp))
    }

    @Test
    fun `from returns null when url is empty even with a secret`() {
        val sp = FakeSharedPreferences().apply {
            edit().put(Pref.WindowsDeviceSecret, "s3cr3t")
                .put(Pref.WindowsDeviceId, "device-1")
                .apply()
        }
        assertNull(WindowsTarget.from(sp))
    }

    @Test
    fun `from resolves the full target when all fields are set`() {
        val target = WindowsTarget.from(paired())
        assertNotNull(target)
        target!!
        assertEquals("http://vm-win:8756", target.baseUrl)
        assertEquals("device-1", target.deviceId)
        assertEquals("s3cr3t", target.deviceSecret)
        assertEquals("Office PC", target.serverName)
    }

    @Test
    fun `credentials mirror the device id and secret`() {
        val credentials = WindowsTarget.from(paired())!!.credentials()
        assertEquals("device-1", credentials.deviceId)
        assertEquals("s3cr3t", credentials.deviceSecret)
    }

    @Test
    fun `pref defaults are the not-paired empty strings and toggle-off`() {
        val sp = FakeSharedPreferences()
        assertEquals("", sp.get(Pref.WindowsTargetUrl))
        assertEquals("", sp.get(Pref.WindowsDeviceSecret))
        assertEquals("", sp.get(Pref.WindowsDeviceId))
        assertEquals("", sp.get(Pref.WindowsServerName))
        assertEquals(false, sp.get(Pref.WindowsAutoSendEnabled))
    }
}
