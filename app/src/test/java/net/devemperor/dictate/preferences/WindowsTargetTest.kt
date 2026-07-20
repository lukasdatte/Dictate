package net.devemperor.dictate.preferences

import net.devemperor.dictate.ai.secrets.SecretRef
import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.ai.secrets.SecretStoreException
import net.devemperor.dictate.secrets.PairingSecrets
import net.devemperor.dictate.testutil.FakeSecretStore
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Unit tests for [WindowsTarget] (ADR-0017/0019).
 *
 * Since the SecretStore migration (spec secretstore.md §7.2) the "is a PC paired?" gate is
 * [WindowsTarget.isPaired] — a non-secret predicate over [Pref.WindowsTargetUrl] +
 * [Pref.WindowsDeviceId]; the device secret is no longer a pref. [WindowsTarget.resolve] is the
 * only path that needs the secret value, reading it from the [SecretStore].
 */
class WindowsTargetTest {

    /** A paired device: url + deviceId + serverName in prefs, but NO secret pref (it lives in the store). */
    private fun pairedPrefs() = FakeSharedPreferences().apply {
        edit()
            .put(Pref.WindowsTargetUrl, "http://vm-win:8756")
            .put(Pref.WindowsDeviceId, "device-1")
            .put(Pref.WindowsServerName, "Office PC")
            .apply()
    }

    private fun storeWith(secret: String = "s3cr3t") = FakeSecretStore().apply {
        put(PairingSecrets.DEVICE_SECRET_REF, secret.toByteArray(StandardCharsets.UTF_8))
    }

    // ── isPaired: the non-secret predicate ──────────────────────────────────────────────────

    @Test
    fun `isPaired is false on fresh defaults`() {
        assertFalse(WindowsTarget.isPaired(FakeSharedPreferences()))
    }

    @Test
    fun `isPaired is false with only a url`() {
        val sp = FakeSharedPreferences().apply { edit().put(Pref.WindowsTargetUrl, "http://vm-win:8756").apply() }
        assertFalse(WindowsTarget.isPaired(sp))
    }

    @Test
    fun `isPaired is false with only a deviceId`() {
        val sp = FakeSharedPreferences().apply { edit().put(Pref.WindowsDeviceId, "device-1").apply() }
        assertFalse(WindowsTarget.isPaired(sp))
    }

    @Test
    fun `isPaired is true with url and deviceId even when no secret pref exists`() {
        // Models a migrated user: SecretsMigration deleted the plaintext secret pref, url+deviceId
        // remain — the user IS paired and must read as paired (the C-TEST-2 regression this fixes).
        assertTrue(WindowsTarget.isPaired(pairedPrefs()))
    }

    // ── resolve: the full send target, secret from the store ────────────────────────────────

    @Test
    fun `resolve returns the full target reading the secret from the store`() {
        val target = WindowsTarget.resolve(pairedPrefs(), storeWith())
        assertNotNull(target)
        target!!
        assertEquals("http://vm-win:8756", target.baseUrl)
        assertEquals("device-1", target.deviceId)
        assertEquals("s3cr3t", target.deviceSecret)
        assertEquals("Office PC", target.serverName)
    }

    @Test
    fun `resolve returns null when not paired`() {
        assertNull(WindowsTarget.resolve(FakeSharedPreferences(), storeWith()))
    }

    @Test
    fun `resolve returns null when the secret is absent from the store`() {
        // Paired prefs but the store has no pairing secret — treated as "pair again", not a crash.
        assertNull(WindowsTarget.resolve(pairedPrefs(), FakeSecretStore()))
    }

    @Test
    fun `resolve returns null when the secret is undecryptable`() {
        // A rotated/foreign KEK makes get() throw DecryptionFailed — resolve swallows it to null
        // (ADR-0017 "pair again"), never letting the exception escape to the caller.
        val throwing = object : SecretStore {
            override val available = true
            override val hardwareBacked = false
            override fun get(ref: SecretRef): ByteArray? =
                throw SecretStoreException.DecryptionFailed(ref)
            override fun put(ref: SecretRef, value: ByteArray) = Unit
            override fun delete(ref: SecretRef) = Unit
        }
        assertNull(WindowsTarget.resolve(pairedPrefs(), throwing))
    }

    @Test
    fun `credentials mirror the device id and secret`() {
        val credentials = WindowsTarget.resolve(pairedPrefs(), storeWith())!!.credentials()
        assertEquals("device-1", credentials.deviceId)
        assertEquals("s3cr3t", credentials.deviceSecret)
    }

    @Test
    fun `pref defaults are the not-paired empty strings and toggle-off`() {
        val sp = FakeSharedPreferences()
        assertEquals("", sp.get(Pref.WindowsTargetUrl))
        assertEquals("", sp.get(Pref.WindowsDeviceId))
        assertEquals("", sp.get(Pref.WindowsServerName))
        assertEquals(false, sp.get(Pref.WindowsAutoSendEnabled))
    }
}
