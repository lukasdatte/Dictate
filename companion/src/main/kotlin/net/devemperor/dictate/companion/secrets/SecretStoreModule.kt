package net.devemperor.dictate.companion.secrets

import com.sun.jna.Platform
import net.devemperor.dictate.ai.secrets.SecretStore
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

/**
 * The one place the companion asks what OS it is on to pick a [SecretStore] backend — the exact
 * parallel of [net.devemperor.dictate.companion.platform.PlatformModule] (ADR-0018): an `object`
 * with a single `detect(...)`, so a reader navigating from `PlatformModule.detect()` finds the same
 * shape here.
 *
 * Windows gets DPAPI ([DpapiSecretStore], user-account-bound, `hardwareBacked = true`); every other
 * host — Linux dogfooding and the headless Hub peer (F8) — gets the file-based AES-GCM fallback
 * ([FileAesGcmSecretStore], `hardwareBacked = false`), which runs everywhere without a keyring
 * daemon (spec secretstore.md §6.1).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/secretstore.md §6
 * @see docs/decisions/0029-secret-store.md
 */
object SecretStoreModule {

    fun detect(configDir: Path): SecretStore =
        if (Platform.isWindows()) DpapiSecretStore(configDir) else FileAesGcmSecretStore(configDir)
}

/**
 * Maps a [net.devemperor.dictate.ai.secrets.SecretRef.handle] to a filesystem-safe file name so
 * `delete` is a plain file delete and no handle character can escape the secrets directory. Shared
 * by both file-backed stores (DRY). URL-safe Base64 of SHA-256(handle) — unpadded, no `/` or `+`.
 */
internal fun secretFileName(handle: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(handle.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

/**
 * Writes [bytes] to [target] atomically: stage into a sibling `.tmp` then `ATOMIC_MOVE` it over the
 * target. A crash or power loss *while replacing an existing secret* can therefore never leave a
 * truncated blob — which the next [SecretStore.get] would surface as a spurious `DecryptionFailed`
 * for a previously-valid secret (an unintended re-entry). Shared by both file-backed stores (DRY),
 * mirroring the master-key atomic-create hardening. Falls back to a plain (non-atomic) replace only
 * where the filesystem cannot do an atomic move; the temp file is cleaned up on any failure.
 */
internal fun writeSecretBlobAtomically(target: Path, bytes: ByteArray) {
    val tmp = target.resolveSibling("${target.fileName}.tmp")
    try {
        Files.write(tmp, bytes)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: Exception) {
        runCatching { Files.deleteIfExists(tmp) }
        throw e
    }
}
