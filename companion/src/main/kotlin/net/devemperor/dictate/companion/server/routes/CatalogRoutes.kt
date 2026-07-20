package net.devemperor.dictate.companion.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.devemperor.dictate.companion.domain.CatalogService
import net.devemperor.dictate.companion.domain.CompanionException
import net.devemperor.dictate.companion.server.plugins.device
import net.devemperor.dictate.companion.server.respondProtocol
import net.devemperor.dictate.shared.protocol.CatalogCredentialResponse
import net.devemperor.dictate.shared.protocol.CatalogEntityResponse
import net.devemperor.dictate.shared.protocol.CatalogIndexResponse
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.ValidationDetail
import net.devemperor.dictate.shared.protocol.Validations

/**
 * The `/v1/catalog` family (peer-katalog.md §4.1) — three authenticated GET routes, mounted inside the
 * `authenticated { … }` block so a peer proves a valid device secret or gets the uniform 401 (AC4).
 *
 * Reads through [respondProtocol] like every other route — the codec validates on the way out, so a
 * response that broke our own contract surfaces as a 500 here, not as a puzzle on the far side
 * (ADR-0016). The `{id}` path parameter is validated against the shared entity-id pattern before the
 * service is touched: a malformed id is a 400, an unknown/private id is a 404 (the service's job).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md §4.1
 */
fun Route.catalogRoutes(catalog: CatalogService) {

    get(Endpoints.CATALOG) {
        call.respondProtocol(
            HttpStatusCode.OK,
            catalog.index(),
            CatalogIndexResponse.serializer(),
            Validations.catalogIndexResponse,
        )
    }

    get("${Endpoints.CATALOG_ENTITY}/{id}") {
        val id = call.requireEntityId()
        call.respondProtocol(
            HttpStatusCode.OK,
            catalog.entity(id),
            CatalogEntityResponse.serializer(),
            Validations.catalogEntityResponse,
        )
    }

    get("${Endpoints.CATALOG_CREDENTIAL}/{id}") {
        val id = call.requireEntityId()
        // call.device is the audit identity — who is picking up this credential (§4.3).
        call.respondProtocol(
            HttpStatusCode.OK,
            catalog.credential(id, peerDeviceId = call.device.deviceId),
            CatalogCredentialResponse.serializer(),
            Validations.catalogCredentialResponse,
        )
    }
}

/**
 * The `{id}` path parameter, validated against the shared entity-id pattern ([Validations.isCatalogEntityId]).
 * A malformed id is a `VALIDATION_FAILED` (400), never passed to the service — the message names the
 * field, never a value (redaction rule). An absent parameter cannot occur (the route has `{id}`), but
 * is treated the same.
 */
private fun io.ktor.server.application.ApplicationCall.requireEntityId(): String {
    val id = parameters["id"].orEmpty()
    if (!Validations.isCatalogEntityId(id)) {
        throw CompanionException.ValidationException(
            listOf(ValidationDetail(path = "id", message = "must match the catalog entity-id format")),
        )
    }
    return id
}
