package net.devemperor.dictate.companion.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.devemperor.dictate.companion.domain.HealthService
import net.devemperor.dictate.companion.server.respondProtocol
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.HealthResponse
import net.devemperor.dictate.shared.protocol.Validations

/**
 * `GET /v1/health` — authenticated on purpose.
 *
 * It reveals the machine's name and whether it can type; an unauthenticated probe of the tailnet
 * has no business learning either. The phone calls it right after pairing ("Verbindung testen").
 */
fun Route.healthRoutes(health: HealthService) {
    get(Endpoints.HEALTH) {
        call.respondProtocol(
            HttpStatusCode.OK,
            health.health(),
            HealthResponse.serializer(),
            Validations.healthResponse,
        )
    }
}
