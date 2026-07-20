package net.devemperor.dictate.companion.secrets

import com.sun.jna.Platform
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the platform-selection contract of [SecretStoreModule] — the companion's parallel to
 * `PlatformModule.detect()` (ADR-0018).
 *
 * The non-Windows branch is asserted for real on this Linux CI host: `detect` must hand back the
 * file-based AES-GCM fallback, which runs everywhere without a keyring daemon (spec secretstore.md
 * §6.1). The Windows/DPAPI branch is verified under the block-B Windows acceptance, not here.
 */
class SecretStoreModuleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun detect_onNonWindowsHost_returnsFileAesGcmFallback() {
        assumeFalse("DPAPI branch is covered by the Windows acceptance", Platform.isWindows())

        val store = SecretStoreModule.detect(tmp.root.toPath())

        assertTrue(
            "Non-Windows host must get the FileAesGcmSecretStore fallback, got ${store::class}",
            store is FileAesGcmSecretStore,
        )
    }
}
