package net.devemperor.dictate.companion.fakes

import net.devemperor.dictate.ai.secrets.SecretRef
import net.devemperor.dictate.ai.secrets.SecretStore

/**
 * A minimal in-memory [SecretStore] — no crypto, just a byte map keyed by [SecretRef.handle].
 *
 * The catalog tests need a store they can seed with a known sentinel secret and then assert never
 * leaks into an index/entity payload (F12, AC5). It is honest about what it is: `hardwareBacked =
 * false`, `available = true`.
 */
class FakeSecretStore : SecretStore {

    private val store = mutableMapOf<String, ByteArray>()

    override fun get(ref: SecretRef): ByteArray? = store[ref.handle]

    override fun put(ref: SecretRef, value: ByteArray) {
        store[ref.handle] = value
    }

    override fun delete(ref: SecretRef) {
        store.remove(ref.handle)
    }

    override val available: Boolean = true
    override val hardwareBacked: Boolean = false
}
