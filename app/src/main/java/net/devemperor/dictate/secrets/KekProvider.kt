package net.devemperor.dictate.secrets

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The crypto seam that makes [AndroidKeystoreSecretStore] testable under Robolectric.
 *
 * The `AndroidKeyStore` provider is NOT real on the JVM — Robolectric does not shadow it with
 * working crypto, so a store that reached for the keystore directly would crash every unit test
 * with `NoSuchProviderException`. Behind this one-method seam, prod ([KeystoreKekProvider]) fetches
 * the hardware-bound KEK while a test supplies an in-memory AES key, and the exact same
 * `AES/GCM/NoPadding` round-trip runs in both (spec secretstore.md §5.4, §11).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/secretstore.md §5
 */
fun interface KekProvider {
    /** The AES key that wraps secret blobs. Never leaves the device in the prod impl. */
    fun encryptionKey(): SecretKey
}

/**
 * Prod [KekProvider]: a non-exportable AES-256 key in the Android Keystore (TEE-bound).
 *
 * The key is created lazily on first use and reused thereafter. `setUserAuthenticationRequired`
 * is deliberately `false`: the IME must read keys without an unlock prompt while dictating — the
 * protection goal is "not plaintext on disk", not "only after a fingerprint" (spec §5.1). StrongBox
 * is not requested (API 28+ / Secure-Element only; an unconditional request throws on many devices).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/secretstore.md §5.1, §5.3
 */
class KeystoreKekProvider(private val alias: String = DEFAULT_ALIAS) : KekProvider {

    override fun encryptionKey(): SecretKey = getOrCreateKey()

    /** Lazy, `@Synchronized` against parallel IME threads racing the first-put key creation (§5.3). */
    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** Versioned alias — a `.v2` would be a deliberate key rotation, never an in-place change. */
        const val DEFAULT_ALIAS = "net.devemperor.dictate.secretstore.kek.v1"
    }
}
