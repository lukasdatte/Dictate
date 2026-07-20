package net.devemperor.dictate.ai.secrets

/**
 * The one door to secrets in the project (F11). Callers never touch the
 * Android Keystore, DPAPI or the fallback file directly — they name a
 * [SecretRef] and receive/hand over raw bytes. Encryption at rest is the
 * implementation's job; the port has no notion of crypto.
 *
 * Values are `ByteArray` (not `String`): a credential blob or a raw key may
 * be non-UTF-8, and the store must round-trip bytes losslessly. Callers that
 * hold a String key encode UTF-8 on write and decode on read (the read
 * adapter, e.g. AndroidAiConfig, also applies any legacy normalization such
 * as the non-ASCII strip — the store stays byte-exact).
 *
 * Lives in `:shared-ai`, NOT `:shared`: it has no wire/serialization concern
 * (which would drag it into `:shared`), but it is consumed by the AI core
 * (`AiConfig`) and both platform hosts — the same reason `:shared-ai` exists
 * as a module (spec secretstore.md §4.1, ADR adr-shared-ai-module).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/secretstore.md §4
 */
interface SecretStore {

    /** Decrypted bytes, or null if no secret is stored under [ref]. */
    fun get(ref: SecretRef): ByteArray?

    /** Encrypts and stores [value] under [ref], replacing any prior value. */
    fun put(ref: SecretRef, value: ByteArray)

    /** Removes the secret under [ref]. A no-op if none exists. */
    fun delete(ref: SecretRef)

    /**
     * false → no secure store on this platform/host (e.g. a Linux host where
     * even the file fallback could not initialise). Then [get] returns null and
     * the UI warns, mirroring TextInserter.available. It is NOT false merely
     * because a key is missing.
     */
    val available: Boolean

    /**
     * true → the KEK is bound to hardware/OS-user and never leaves the device
     * (Android Keystore, Windows DPAPI). false → the weaker file fallback
     * (spec §6.3). Surfaced in the UI so the user knows the at-rest strength.
     */
    val hardwareBacked: Boolean
}

/**
 * Stable, namespaced identity of a secret — never the value.
 *
 * [namespace] groups secrets by owning entity kind so a plausible key set
 * stays enumerable and deletable (e.g. all keys of a removed ProviderConfig).
 * [id] is unique within the namespace (a Credential-entity UUID, or a legacy
 * pref slot name during migration).
 */
data class SecretRef(val namespace: String, val id: String) {
    init {
        require(namespace.isNotBlank() && id.isNotBlank()) {
            "SecretRef namespace and id must be non-blank"
        }
        // Used to derive a filesystem-safe storage handle — keep it total.
        require(namespace.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            "SecretRef namespace must be [A-Za-z0-9_-]: '$namespace'"
        }
    }

    /** Stable storage handle, e.g. "credential/9f1c…" or "legacy/rewording_api_key_openai". */
    val handle: String get() = "$namespace/$id"
}

/**
 * Fehler-Semantik-Träger: distinguishes "store missing" from "decrypt failed"
 * from "IO failed" so a lost/rotated KEK can never silently masquerade as an
 * empty (== missing) key (spec §4.3, §11).
 */
sealed class SecretStoreException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The store could not be initialised (no keystore/provider). Paired with available=false. */
    class Unavailable(message: String, cause: Throwable? = null) : SecretStoreException(message, cause)

    /**
     * A blob exists but could not be decrypted — a rotated/foreign KEK, a
     * corrupt blob, or a GCM tag mismatch. MUST surface as this exception, never
     * as null (null means "no such secret") and never as an empty value.
     */
    class DecryptionFailed(ref: SecretRef, cause: Throwable? = null) :
        SecretStoreException("decrypt failed for ${ref.handle}", cause)

    /** Underlying IO failed (file, registry). */
    class StorageIo(message: String, cause: Throwable? = null) : SecretStoreException(message, cause)
}
