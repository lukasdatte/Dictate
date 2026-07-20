package net.devemperor.dictate.testutil

import net.devemperor.dictate.ai.secrets.SecretRef
import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.ai.secrets.SecretStoreException

/**
 * Minimal in-memory [SecretStore] fake for pure-JVM tests: records puts, can report unavailable,
 * and can throw on a chosen [SecretRef] to drive the abort paths deterministically.
 *
 * Hoisted from `SecretsMigrationTest` so `WindowsTargetTest` (pure JVM) and the Robolectric
 * composition tests can reuse the same fake instead of the real Keystore-backed store — the
 * `AndroidKeyStore` provider is absent under Robolectric (spec secretstore.md §5.4).
 */
class FakeSecretStore(
    override val available: Boolean = true,
    override val hardwareBacked: Boolean = false,
    var failOn: SecretRef? = null,
) : SecretStore {
    private val stored = linkedMapOf<SecretRef, ByteArray>()

    override fun get(ref: SecretRef): ByteArray? = stored[ref]

    override fun put(ref: SecretRef, value: ByteArray) {
        if (ref == failOn) throw SecretStoreException.StorageIo("forced failure for ${ref.handle}")
        stored[ref] = value
    }

    override fun delete(ref: SecretRef) {
        stored.remove(ref)
    }
}
