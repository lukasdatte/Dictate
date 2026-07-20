package net.devemperor.dictate.secrets

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import net.devemperor.dictate.ai.secrets.SecretRef
import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.ai.secrets.SecretStoreException
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Android [SecretStore]: a Keystore-bound AES-256-GCM KEK wraps each secret blob.
 *
 * Layout (spec secretstore.md §5.2): one record per secret, `IV(12) ‖ GCM ciphertext+tag`,
 * Base64 under [SecretRef.handle] in a **dedicated** SharedPreferences file
 * (`net.devemperor.dictate.secretstore`) kept separate from the app's main prefs — that separation
 * is what lets B2's absence test assert "no plaintext key left in the main prefs XML" directly.
 *
 * The crypto sits behind [KekProvider] so the store is unit-testable under Robolectric with an
 * in-memory key (§5.4); the real Keystore path is verified in the Android acceptance run.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/secretstore.md §5
 * @see docs/decisions/0029-secret-store.md
 */
class AndroidKeystoreSecretStore(
    private val blobPrefs: SharedPreferences,
    private val kekProvider: KekProvider,
    override val hardwareBacked: Boolean,
) : SecretStore {

    /**
     * True once the KEK can be obtained. Obtaining it lazily creates the Keystore key if absent —
     * benign and idempotent. False only when the platform keystore is unreachable, mirroring
     * `TextInserter.available` (spec §4.3).
     */
    override val available: Boolean
        get() = try {
            kekProvider.encryptionKey()
            true
        } catch (e: Exception) {
            false
        }

    override fun get(ref: SecretRef): ByteArray? {
        val stored = blobPrefs.getString(ref.handle, null) ?: return null

        val key = try {
            kekProvider.encryptionKey()
        } catch (e: Exception) {
            // A blob exists but the KEK is gone (e.g. device-bound key lost after a restore, §5.3):
            // that is a decryption failure, never a silent "no key" — §4.3 / §11.
            throw SecretStoreException.DecryptionFailed(ref, e)
        }

        val blob = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw SecretStoreException.DecryptionFailed(ref, e)
        }
        if (blob.size <= IV_LENGTH) throw SecretStoreException.DecryptionFailed(ref)

        val iv = blob.copyOfRange(0, IV_LENGTH)
        val ciphertext = blob.copyOfRange(IV_LENGTH, blob.size)
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(ciphertext)
            }
        } catch (e: GeneralSecurityException) {
            // GCM tag mismatch (foreign/rotated KEK, tampered blob) lands here — §4.3.
            throw SecretStoreException.DecryptionFailed(ref, e)
        }
    }

    override fun put(ref: SecretRef, value: ByteArray) {
        val key = try {
            kekProvider.encryptionKey()
        } catch (e: Exception) {
            throw SecretStoreException.Unavailable("secret store unavailable for ${ref.handle}", e)
        }

        val blob = try {
            Cipher.getInstance(TRANSFORMATION).run {
                // No caller-supplied IV: AndroidKeyStore GCM keys forbid it and generate a fresh
                // random IV per encryption themselves (readable via cipher.iv). Same behaviour for the
                // in-memory test key. Invariant preserved: a fresh 12-byte IV per put, prepended to
                // the blob (§5.2, §11).
                init(Cipher.ENCRYPT_MODE, key)
                iv + doFinal(value)
            }
        } catch (e: GeneralSecurityException) {
            // A raw GeneralSecurityException here (e.g. KeyPermanentlyInvalidatedException when the
            // Keystore key was invalidated) must surface as the store's StorageIo contract, never
            // escape as an unwrapped crypto exception — mirrors FileAesGcmSecretStore.put and this
            // class's own get. The sole prod caller (SecretsMigration, run on every app start) catches
            // SecretStoreException and aborts cleanly (flag unset, plaintext intact, retry next
            // start); an unwrapped exception would instead crash app start into a boot loop (§4.3).
            throw SecretStoreException.StorageIo("encrypt failed for ${ref.handle}", e)
        }

        blobPrefs.edit()
            .putString(ref.handle, Base64.encodeToString(blob, Base64.NO_WRAP))
            .apply()
    }

    override fun delete(ref: SecretRef) {
        blobPrefs.edit().remove(ref.handle).apply()
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_LENGTH = 12

        /** Dedicated prefs file, kept apart from the app's main prefs (spec §5.2). */
        const val BLOB_PREFS_NAME = "net.devemperor.dictate.secretstore"

        /**
         * Prod wiring: the Keystore-backed KEK plus the dedicated blob-prefs file. `hardwareBacked`
         * is true because the KEK is TEE-bound.
         */
        @JvmStatic
        fun create(context: Context): AndroidKeystoreSecretStore =
            AndroidKeystoreSecretStore(
                blobPrefs = context.getSharedPreferences(BLOB_PREFS_NAME, Context.MODE_PRIVATE),
                kekProvider = KeystoreKekProvider(),
                hardwareBacked = true,
            )
    }
}
