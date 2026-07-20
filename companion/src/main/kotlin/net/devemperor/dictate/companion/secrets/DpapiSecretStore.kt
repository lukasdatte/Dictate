package net.devemperor.dictate.companion.secrets

import com.sun.jna.platform.win32.Crypt32Util
import net.devemperor.dictate.ai.secrets.SecretRef
import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.ai.secrets.SecretStoreException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Windows [SecretStore]: DPAPI wraps each secret, one file per [SecretRef] under `configDir/secrets/`.
 *
 * `Crypt32Util.cryptProtectData` / `cryptUnprotectData` bind the blob to the **Windows user account**
 * (default `CRYPTPROTECT_USER` scope) — no key-management code, no IV handling on our side (DPAPI does
 * it internally). Same JNA `com.sun.jna.platform.win32` binding family as the existing
 * `Advapi32Util` registry code (spec secretstore.md §6.3, §6.2).
 *
 * DPAPI is real only on Windows, so the round-trip is verified there (see DpapiSecretStoreTest,
 * `pending: block-B-windows-abnahme`); on Linux this class only has to compile.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/secretstore.md §6.3
 */
class DpapiSecretStore(configDir: Path) : SecretStore {

    private val secretsDir: Path = configDir.resolve("secrets")

    /** DPAPI is always present under the Windows user profile. */
    override val available: Boolean = true

    /** User-account-bound, does not leave the profile — honest "hardware/OS-user backed". */
    override val hardwareBacked: Boolean = true

    override fun get(ref: SecretRef): ByteArray? {
        val file = fileFor(ref)
        if (!Files.exists(file)) return null
        val blob = try {
            Files.readAllBytes(file)
        } catch (e: Exception) {
            throw SecretStoreException.StorageIo("read failed for ${ref.handle}", e)
        }
        return try {
            Crypt32Util.cryptUnprotectData(blob)
        } catch (e: Exception) {
            // Foreign profile / corrupt blob — surface as DecryptionFailed, never a silent empty (§4.3).
            throw SecretStoreException.DecryptionFailed(ref, e)
        }
    }

    override fun put(ref: SecretRef, value: ByteArray) {
        val blob = try {
            Crypt32Util.cryptProtectData(value)
        } catch (e: Exception) {
            throw SecretStoreException.StorageIo("DPAPI protect failed for ${ref.handle}", e)
        }
        try {
            Files.createDirectories(secretsDir)
            writeSecretBlobAtomically(fileFor(ref), blob)
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
}
