package net.devemperor.dictate.shared.client

import io.konform.validation.Validation
import kotlinx.serialization.KSerializer
import net.devemperor.dictate.shared.auth.AuthHeaders
import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.protocol.CatalogCredentialResponse
import net.devemperor.dictate.shared.protocol.CatalogEntityResponse
import net.devemperor.dictate.shared.protocol.CatalogIndexResponse
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.Validations
import net.devemperor.dictate.shared.transport.DispatchTransport
import java.io.IOException

/**
 * A subscribing peer's view of another peer's catalog — the three GET calls of the `/v1/catalog`
 * family, and nothing else (peer-katalog.md §3.5).
 *
 * Pure and platform-free — shared verbatim between the desktop companion (a subscriber) and the
 * Android app (also a subscriber); the two-peer E2E drives a real server through this same client,
 * which is what keeps the two sides from drifting apart. Parallel to [DispatchClient]: it reuses the
 * same [DispatchTransport], [Credentials], [AuthHeaders] and the shared response plumbing
 * (`WireResponse.kt`), so it returns the same [DispatchResult]/[DispatchError] rather than a parallel
 * error family (see the [DispatchResult] KDoc for why one generic result, not three).
 *
 * Blocking. The caller owns the thread (a background executor, never the main thread).
 *
 * [credentials] is a **lambda, not a value**: the secret is read at call time, so re-pairing takes
 * effect without rebuilding the client.
 *
 * ## The 404 fork
 *
 * A **bare** 404 (no [net.devemperor.dictate.shared.protocol.ErrorEnvelope]) means the peer has no
 * catalog route at all — an older companion — and is re-mapped to [DispatchError.EndpointMissing] so
 * the UI can say "update that peer" (F13, AC1). A 404 that DID carry an envelope with code
 * `CATALOG_ENTITY_NOT_FOUND` is classified as [DispatchError.EntityGone] by the shared classifier —
 * the entity was deleted or un-shared at the source (§6.4). The two are told apart by the envelope's
 * presence, exactly as `DispatchClient.input()` tells its bare 404 apart.
 */
class CatalogClient(
    private val transport: DispatchTransport,
    private val credentials: () -> Credentials?,
) {

    /** `GET /v1/catalog` — the whole shared offer + rootHash. */
    fun index(): DispatchResult<CatalogIndexResponse> = catalogRead(
        path = Endpoints.CATALOG,
        responseSerializer = CatalogIndexResponse.serializer(),
        responseValidation = Validations.catalogIndexResponse,
    )

    /** `GET /v1/catalog/entity/{id}` — one non-credential entity's canonical payload. */
    fun entity(id: String): DispatchResult<CatalogEntityResponse> = catalogRead(
        path = "${Endpoints.CATALOG_ENTITY}/$id",
        responseSerializer = CatalogEntityResponse.serializer(),
        responseValidation = Validations.catalogEntityResponse,
    )

    /** `GET /v1/catalog/credential/{id}` — the envelope-delivered secret value (F12, §4.3). */
    fun credential(id: String): DispatchResult<CatalogCredentialResponse> = catalogRead(
        path = "${Endpoints.CATALOG_CREDENTIAL}/$id",
        responseSerializer = CatalogCredentialResponse.serializer(),
        responseValidation = Validations.catalogCredentialResponse,
    )

    // ── Plumbing ────────────────────────────────────────────────────────────────────────

    /**
     * A bodyless authenticated GET, then the bare-404 → [DispatchError.EndpointMissing] re-map that
     * distinguishes an old peer (no catalog route) from an entity that is gone (which the shared
     * classifier already turned into [DispatchError.EntityGone] from the envelope code).
     */
    private fun <R> catalogRead(
        path: String,
        responseSerializer: KSerializer<R>,
        responseValidation: Validation<R>,
    ): DispatchResult<R> {
        val credentials = credentials()
            ?: return DispatchResult.Failure(DispatchError.Unauthorized)

        val response = try {
            transport.get(path, AuthHeaders.forDevice(credentials))
        } catch (e: IOException) {
            return DispatchResult.Failure(DispatchError.Unreachable(e.describeWire()))
        }

        val result = response.parseWire(responseSerializer, responseValidation)
        if (result is DispatchResult.Failure) {
            val error = result.error
            if (error is DispatchError.Server && error.status == 404) {
                return DispatchResult.Failure(DispatchError.EndpointMissing)
            }
        }
        return result
    }
}
