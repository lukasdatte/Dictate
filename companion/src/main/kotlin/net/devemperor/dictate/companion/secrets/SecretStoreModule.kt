package net.devemperor.dictate.companion.secrets

import com.sun.jna.Platform
import net.devemperor.dictate.ai.secrets.SecretStore
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

/**
 * The one place the companion asks what OS it is on to pick a [SecretStore] backend — mirrors
 * `PlatformModule.detect()` (ADR-0018).
 *
 * Windows gets DPAPI ([DpapiSecretStore], user-account-bound, `hardwareBacked = true`); every other
 * host — Linux dogfooding and the headless Hub peer (F8) — gets the file-based AES-GCM fallback
 * ([FileAesGcmSecretStore], `hardwareBacked = false`), which runs everywhere without a keyring
 * daemon (spec secretstore.md §6.1).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/secretstore.md §6
 */
fun detectSecretStore(configDir: Path): SecretStore =
    if (Platform.isWindows()) DpapiSecretStore(configDir) else FileAesGcmSecretStore(configDir)

/**
 * Maps a [net.devemperor.dictate.ai.secrets.SecretRef.handle] to a filesystem-safe file name so
 * `delete` is a plain file delete and no handle character can escape the secrets directory. Shared
 * by both file-backed stores (DRY). URL-safe Base64 of SHA-256(handle) — unpadded, no `/` or `+`.
 */
internal fun secretFileName(handle: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(handle.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}
