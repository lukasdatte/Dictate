package net.devemperor.dictate.secrets

import net.devemperor.dictate.ai.secrets.SecretRef
import net.devemperor.dictate.ai.secrets.SecretStoreException
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.spec.SecretKeySpec

/**
 * Round-trip and error-semantics of [AndroidKeystoreSecretStore] on the JVM.
 *
 * # Why Robolectric
 * The store uses `android.util.Base64` and `javax.crypto.Cipher`; Robolectric provides a working
 * `Base64` shadow, and the crypto runs on the JVM's real provider via the [InMemoryKekProvider]
 * seam ([spec] §5.4) — the real `AndroidKeyStore` is verified in the Android acceptance run.
 *
 * Covers spec §2 criteria 2 (round-trip / delete→null) and 3 (DecryptionFailed, never a silent
 * empty value) plus the IV-uniqueness footgun (§11).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidKeystoreSecretStoreTest {

    private val ref = SecretRef("legacy", "transcription_api_key_openai")

    private fun store(
        prefs: FakeSharedPreferences = FakeSharedPreferences(),
        kek: KekProvider = InMemoryKekProvider(),
    ) = AndroidKeystoreSecretStore(prefs, kek, hardwareBacked = false)

    @Test
    fun putThenGet_returnsByteIdenticalValue() {
        val store = store()
        val secret = "sk-Öß-非ASCII-key".toByteArray(Charsets.UTF_8)

        store.put(ref, secret)

        assertArrayEquals(secret, store.get(ref))
    }

    @Test
    fun putThenGet_roundTripsRawNonUtf8Bytes() {
        val store = store()
        val raw = byteArrayOf(0, -1, 13, -128, 127, 42)

        store.put(ref, raw)

        assertArrayEquals(raw, store.get(ref))
    }

    @Test
    fun get_onAbsentRef_isNull() {
        assertNull(store().get(ref))
    }

    @Test
    fun deleteThenGet_isNull() {
        val store = store()
        store.put(ref, "value".toByteArray())

        store.delete(ref)

        assertNull(store.get(ref))
    }

    @Test
    fun put_replacesPriorValue() {
        val store = store()
        store.put(ref, "first".toByteArray())
        store.put(ref, "second".toByteArray())

        assertArrayEquals("second".toByteArray(), store.get(ref))
    }

    @Test
    fun twoPuts_ofSameValue_useDistinctIVs() {
        // Nonce reuse would break GCM confidentiality; a fresh IV per put means the two stored blobs
        // must differ even for identical plaintext (spec §5.2 / §11).
        val prefs = FakeSharedPreferences()
        val store = store(prefs)

        store.put(ref, "same".toByteArray())
        val first = prefs.getString(ref.handle, null)
        store.put(ref, "same".toByteArray())
        val second = prefs.getString(ref.handle, null)

        assertTrue("stored blobs must differ across puts", first != second)
    }

    @Test
    fun get_withForeignKek_throwsDecryptionFailed_notEmpty() {
        val prefs = FakeSharedPreferences()
        store(prefs, InMemoryKekProvider()).put(ref, "value".toByteArray())

        val foreignStore = store(prefs, InMemoryKekProvider()) // different in-memory key

        assertThrows(SecretStoreException.DecryptionFailed::class.java) { foreignStore.get(ref) }
    }

    @Test
    fun get_whenKekUnavailableForStoredBlob_throwsDecryptionFailed() {
        // §5.3 device-bound-key-lost-after-restore: a blob is present but the KEK cannot be obtained
        // at read time. That must surface as DecryptionFailed, never a silent null — this covers the
        // "KEK gone" get() branch specifically (get_withForeignKek exercises the GCM-tag path instead).
        val prefs = FakeSharedPreferences()
        store(prefs, InMemoryKekProvider()).put(ref, "value".toByteArray())

        val failingKek = KekProvider { throw IllegalStateException("keystore key gone after restore") }

        assertThrows(SecretStoreException.DecryptionFailed::class.java) { store(prefs, failingKek).get(ref) }
    }

    @Test
    fun get_onCorruptBlob_throwsDecryptionFailed() {
        val prefs = FakeSharedPreferences()
        val store = store(prefs)
        store.put(ref, "value".toByteArray())

        // Corrupt the stored blob into something that is not even valid Base64 → the decode branch
        // must surface as DecryptionFailed, not a silent null (the GCM-tag-mismatch path is covered
        // by get_withForeignKek_throwsDecryptionFailed_notEmpty).
        prefs.edit().putString(ref.handle, "not-a-valid-base64-blob!!!").apply()

        assertThrows(SecretStoreException.DecryptionFailed::class.java) { store.get(ref) }
    }

    @Test
    fun get_onTooShortBlob_throwsDecryptionFailed() {
        val prefs = FakeSharedPreferences()
        // A blob shorter than the IV cannot be decrypted — must be flagged, not returned as null.
        prefs.edit().putString(ref.handle, android.util.Base64.encodeToString(byteArrayOf(1, 2, 3), android.util.Base64.NO_WRAP)).apply()

        assertThrows(SecretStoreException.DecryptionFailed::class.java) { store(prefs).get(ref) }
    }

    @Test
    fun put_whenKekUnavailable_throwsUnavailable() {
        val failing = KekProvider { throw IllegalStateException("no keystore") }
        val store = store(kek = failing)

        assertThrows(SecretStoreException.Unavailable::class.java) { store.put(ref, "x".toByteArray()) }
        assertFalse(store.available)
    }

    @Test
    fun put_whenCipherFails_throwsStorageIo_notRawCryptoException() {
        // Regression: a GeneralSecurityException from cipher.init/doFinal (e.g. an invalidated
        // Keystore key) must be wrapped as the store's StorageIo contract, never escape raw — the sole
        // prod caller (SecretsMigration, on every app start) catches only SecretStoreException, so an
        // unwrapped crypto exception would crash app start into a boot loop. An invalid 5-byte AES key
        // makes cipher.init throw InvalidKeyException here.
        val badKey = KekProvider { SecretKeySpec(ByteArray(5), "AES") }
        val store = store(kek = badKey)

        assertThrows(SecretStoreException.StorageIo::class.java) { store.put(ref, "x".toByteArray()) }
    }

    @Test
    fun available_isTrue_whenKekResolvable() {
        assertTrue(store().available)
    }
}
