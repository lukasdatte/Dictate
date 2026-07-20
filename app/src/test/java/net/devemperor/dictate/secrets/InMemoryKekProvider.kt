package net.devemperor.dictate.secrets

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Test [KekProvider]: a plain in-memory AES-256 key, so the store's `AES/GCM/NoPadding` round-trip
 * runs under Robolectric without the (unavailable) real `AndroidKeyStore` provider (spec §5.4).
 *
 * Two instances hold different keys unless the same [key] is passed in — that is exactly what the
 * "foreign KEK ⇒ DecryptionFailed" test needs. Reused by the B2 migration test.
 */
class InMemoryKekProvider(private val key: SecretKey = randomAes256()) : KekProvider {

    override fun encryptionKey(): SecretKey = key

    companion object {
        fun randomAes256(): SecretKey =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
