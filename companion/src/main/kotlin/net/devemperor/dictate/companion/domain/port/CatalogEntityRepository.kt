package net.devemperor.dictate.companion.domain.port

import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import net.devemperor.dictate.shared.protocol.CatalogEntry

/**
 * The read side of the shared config-entity mirror, seen as a catalog (peer-katalog.md §4.2).
 *
 * A port, not the SqlDelight repository directly, so [net.devemperor.dictate.companion.domain.CatalogService]
 * stays a pure domain unit a test can drive against an in-memory list. Only `visibility = SHARED`
 * entities are ever exposed — a PRIVATE entity is invisible to a peer, and the same 404 covers
 * "unknown" and "not shared" so the offer never leaks which private entities exist.
 *
 * Credentials are split off the entity path on purpose: their SECRET is never a payload, so a
 * credential is only ever named here as [SharedCredential] metadata; the plaintext is delivered
 * exclusively by [CatalogService.credential] out of the SecretStore (F12, §4.3).
 */
interface CatalogEntityRepository {

    /** Index rows for every SHARED non-credential entity (stored `contentHash`, no payload). */
    fun sharedEntries(): List<CatalogEntry>

    /** Canonical v3 payload of ONE shared non-credential entity; null if unknown, private, or a credential. */
    fun sharedEntity(id: String): SharedEntityPayload?

    /** Metadata of every SHARED credential (no secret, no hash — the index hash is the service's job). */
    fun sharedCredentials(): List<SharedCredential>

    /** Metadata of ONE shared credential id; null if unknown or not shared. */
    fun sharedCredential(id: String): SharedCredential?
}

/**
 * The canonical serialization of one non-credential entity, ready to hand to the wire verbatim.
 * [payload] is exactly the string the sender's `contentHash` was computed over (`CanonicalJson`), so
 * the receiver reproduces the hash byte-for-byte (peer-katalog.md §6.3).
 */
data class SharedEntityPayload(
    val kind: CatalogEntityKindWire,
    val contentHash: String,
    val payload: String,
)

/**
 * A shareable credential named by the SecretStore id it resolves under (`credentialRef`). Carries only
 * metadata — the secret bytes live in the SecretStore and are read only at delivery time.
 */
data class SharedCredential(
    val id: String,
    val provider: String,
    val label: String,
    val updatedAt: Long,
)
