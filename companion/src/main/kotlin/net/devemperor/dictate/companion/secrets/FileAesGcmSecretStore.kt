package net.devemperor.dictate.companion.secrets

import net.devemperor.dictate.ai.secrets.SecretRef
import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.ai.secrets.SecretStoreException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Non-Windows [SecretStore] fallback: a machine-local AES-256-GCM master key wraps each secret.
 *
 * For Linux dogfooding **and** the headless Hub peer (F8), where no keyring daemon can be assumed.
 * The master key lives in `configDir/secrets/master.key` with POSIX `0600` (owner-only), generated
 * from `SecureRandom` on first use; blobs are `IV(12) ‖ AES-256-GCM(payload)`, one file per secret.
 *
 * `hardwareBacked = false` — the master key is on disk (permission-restricted), not in hardware.
 * That is honestly surfaced via the flag: whoever has the user's filesystem access can decrypt. A
 * stronger libsecret backend is a documented later hardening (spec secretstore.md §6.4, §12 Gap 2).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/secretstore.md §6.4
 * @see docs/decisions/0029-secret-store.md
 */
class FileAesGcmSecretStore(configDir: Path) : SecretStore {

    private val secretsDir: Path = configDir.resolve("secrets")
    private val masterKeyFile: Path = secretsDir.resolve(MASTER_KEY_FILE)

    /**
     * true once the secrets dir is writable; false only when even the file store cannot init (§6.4).
     *
     * Evaluated **per access**, not memoized: in the long-lived companion/headless-Hub process a
     * first probe during a transient FS hiccup (slow/late mount) must not latch a false for the rest
     * of the session. `createDirectories` is idempotent and `isWritable` is a cheap stat, so recovery
     * is automatic once the directory becomes writable — matching the Android and DPAPI stores, which
     * also evaluate availability per access.
     */
    override val available: Boolean
        get() = try {
            Files.createDirectories(secretsDir)
            Files.isWritable(secretsDir)
        } catch (e: Exception) {
            false
        }

    override val hardwareBacked: Boolean = false

    private val masterKey: SecretKeySpec by lazy { loadOrCreateMasterKey() }

    override fun get(ref: SecretRef): ByteArray? {
        val file = fileFor(ref)
        if (!Files.exists(file)) return null
        val blob = try {
            Files.readAllBytes(file)
        } catch (e: Exception) {
            throw SecretStoreException.StorageIo("read failed for ${ref.handle}", e)
        }
        if (blob.size <= IV_LENGTH) throw SecretStoreException.DecryptionFailed(ref)

        val iv = blob.copyOfRange(0, IV_LENGTH)
        val ciphertext = blob.copyOfRange(IV_LENGTH, blob.size)
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(ciphertext)
            }
        } catch (e: GeneralSecurityException) {
            // GCM tag mismatch (swapped master key, tampered blob) — §4.3, never a silent empty.
            throw SecretStoreException.DecryptionFailed(ref, e)
        }
    }

    override fun put(ref: SecretRef, value: ByteArray) {
        if (!available) throw SecretStoreException.Unavailable("secrets dir not writable: $secretsDir")

        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val ciphertext = try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.ENCRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(value)
            }
        } catch (e: GeneralSecurityException) {
            throw SecretStoreException.StorageIo("encrypt failed for ${ref.handle}", e)
        }
        try {
            writeSecretBlobAtomically(fileFor(ref), iv + ciphertext)
        } catch (e: Exception) {
            throw SecretStoreException.StorageIo("write failed for ${ref.handle}", e)
        }
    }

    override fun delete(ref: SecretRef) {
        try {
            Files.deleteIfExists(fileFor(ref))
        } catch (e: Exception) {
            throw SecretStoreException.StorageIo("delete failed for ${ref.handle}", e)
        }
    }

    private fun fileFor(ref: SecretRef): Path = secretsDir.resolve(secretFileName(ref.handle))

    private fun loadOrCreateMasterKey(): SecretKeySpec {
        Files.createDirectories(secretsDir)
        if (Files.exists(masterKeyFile)) {
            return SecretKeySpec(Files.readAllBytes(masterKeyFile), KEY_ALGORITHM)
        }
        val raw = ByteArray(KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        writeOwnerOnly(masterKeyFile, raw)
        return SecretKeySpec(raw, KEY_ALGORITHM)
    }

    /**
     * Creates [file] with owner read/write only (POSIX `0600`) **from the start** and writes [bytes].
     *
     * The permission is an atomic create attribute rather than a write-then-chmod: a plain
     * `Files.write` creates the file with the umask default (typically world/group-readable) and only
     * a subsequent `setPosixFilePermissions` narrows it — a window during which the master key is
     * readable, and, on a crash between the two calls, a *permanent* world-readable master key. Both
     * are closed by handing the perms to the open call.
     *
     * `internal` (not `private`) so the T3 regression test can assert the CREATE_NEW collision
     * behaviour directly — a revert to the old write-then-chmod would silently clobber an existing
     * file instead of throwing, which the final-perms test alone cannot catch.
     */
    internal fun writeOwnerOnly(file: Path, bytes: ByteArray) {
        val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        try {
            Files.newByteChannel(
                file,
                setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(ownerOnly),
            ).use { it.write(ByteBuffer.wrap(bytes)) }
        } catch (e: UnsupportedOperationException) {
            // Non-POSIX filesystem (only reachable if this fallback ever ran on Windows, which
            // SecretStoreModule.detect prevents) — no POSIX perms to set, and DPAPI is the Windows path.
            Files.write(file, bytes)
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"
        private const val KEY_SIZE_BYTES = 32
        private const val GCM_TAG_BITS = 128
        private const val IV_LENGTH = 12
        private const val MASTER_KEY_FILE = "master.key"
    }
}
