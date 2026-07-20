package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.ai.secrets.SecretStore
import net.devemperor.dictate.companion.ai.CredentialSecrets
import net.devemperor.dictate.companion.domain.port.CatalogAuditLog
import net.devemperor.dictate.companion.domain.port.CatalogEntityRepository
import net.devemperor.dictate.companion.domain.port.ClockPort
import net.devemperor.dictate.companion.domain.port.SharedCredential
import net.devemperor.dictate.shared.auth.Secrets
import net.devemperor.dictate.shared.protocol.CatalogCredentialResponse
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import net.devemperor.dictate.shared.protocol.CatalogEntityResponse
import net.devemperor.dictate.shared.protocol.CatalogEntry
import net.devemperor.dictate.shared.protocol.CatalogIndexResponse

/**
 * The offer side of the peer catalog (peer-katalog.md §4.2/§4.3): build the index, serve one entity's
 * canonical payload, and deliver a credential secret behind a separate authorized call + an audit row.
 *
 * Everything it returns is a `:shared` wire DTO — the same door-through-the-codec pattern as
 * [HealthService]/[SyncService]. The routes ([net.devemperor.dictate.companion.server.routes.catalogRoutes])
 * only translate a path parameter and hand the DTO to `respondProtocol`.
 *
 * ## The credential invariant (F12, AC5)
 *
 * A secret NEVER appears in the index or in a [CatalogEntityResponse]: the index carries only a
 * fingerprint `contentHash` over the at-rest secret, and `sharedEntity` never returns a credential
 * kind (so `/entity/{id}` for a credential id is a 404). The plaintext leaves this service through
 * exactly one method — [credential] — which reads it from the [SecretStore] and writes one audit row
 * per delivery. That is the whole R8 mitigation.
 */
class CatalogService(
    private val entities: CatalogEntityRepository,
    private val secretStore: SecretStore,
    private val auditLog: CatalogAuditLog,
    private val clock: ClockPort,
) {

    /** `GET /v1/catalog` — every shared entity + a deterministic root hash. */
    fun index(): CatalogIndexResponse {
        val entries = entities.sharedEntries() + entities.sharedCredentials().mapNotNull { it.toIndexEntry() }
        return CatalogIndexResponse(rootHash = rootHash(entries), entries = entries)
    }

    /**
     * `GET /v1/catalog/entity/{id}` — one shared non-credential entity's canonical payload.
     *
     * @throws CompanionException.CatalogEntityNotFoundException when [id] is unknown, private, or a
     *   credential (whose payload is never served here).
     */
    fun entity(id: String): CatalogEntityResponse {
        val entity = entities.sharedEntity(id) ?: throw CompanionException.CatalogEntityNotFoundException()
        return CatalogEntityResponse(
            id = id,
            kind = entity.kind,
            contentHash = entity.contentHash,
            payload = entity.payload,
        )
    }

    /**
     * `GET /v1/catalog/credential/{id}` — the envelope-delivered secret value (F12, §4.3). Writes ONE
     * audit row per delivery, then returns the plaintext straight from the [SecretStore].
     *
     * @throws CompanionException.CatalogEntityNotFoundException when [id] is unknown, not shared, or has
     *   no stored secret.
     */
    fun credential(id: String, peerDeviceId: String): CatalogCredentialResponse {
        val meta = entities.sharedCredential(id) ?: throw CompanionException.CatalogEntityNotFoundException()
        val secret = secretStore.get(CredentialSecrets.credentialRef(id))
            ?: throw CompanionException.CatalogEntityNotFoundException()

        auditLog.record(
            peerDeviceId = peerDeviceId,
            entityId = id,
            kind = CatalogEntityKindWire.CREDENTIAL,
            at = clock.nowMillis(),
        )

        return CatalogCredentialResponse(
            id = id,
            provider = meta.provider,
            label = meta.label,
            secret = secret.decodeToString(),
        )
    }

    /**
     * The deterministic root hash (AC3): SHA-256 over the entries sorted by id and joined as
     * `id:contentHash` per line. Sorting makes it independent of DB row order; a single entity change
     * moves exactly one `contentHash` and therefore the whole hash. `Secrets.sha256` is pure and
     * already shared with the pairing path.
     */
    fun rootHash(entries: List<CatalogEntry>): String =
        Secrets.sha256(entries.sortedBy { it.id }.joinToString("\n") { "${it.id}:${it.contentHash}" })

    /**
     * A CREDENTIAL index row. `contentHash` is a fingerprint of the at-rest secret (Gap 6 fallback):
     * SHA-256 over the encrypted-at-rest plaintext bytes — it changes iff the key changes and never
     * reveals the plaintext (F12). A credential whose secret is missing from the store is skipped: it
     * cannot be delivered, so it must not be offered.
     */
    private fun SharedCredential.toIndexEntry(): CatalogEntry? {
        val secret = secretStore.get(CredentialSecrets.credentialRef(id)) ?: return null
        return CatalogEntry(
            id = id,
            kind = CatalogEntityKindWire.CREDENTIAL,
            contentHash = fingerprint(secret),
            updatedAt = updatedAt,
            label = label,
        )
    }

    /** SHA-256 hex of raw secret bytes — the stable, plaintext-free credential fingerprint (Gap 6). */
    private fun fingerprint(secret: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(secret)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
