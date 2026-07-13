package net.devemperor.dictate.companion.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import net.devemperor.dictate.companion.domain.PairingService
import net.devemperor.dictate.companion.server.receiveValidated
import net.devemperor.dictate.companion.server.respondProtocol
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.PairRequest
import net.devemperor.dictate.shared.protocol.PairResponse
import net.devemperor.dictate.shared.protocol.Validations

/**
 * `POST /v1/pair` — the one unauthenticated route.
 *
 * 201, not 200: pairing *creates* a device. The body is the only place the device secret is ever
 * transmitted; from here on the phone proves itself with it and the PC keeps only its SHA-256.
 */
fun Route.pairRoutes(pairing: PairingService) {
    post(Endpoints.PAIR) {
        val request = call.receiveValidated(PairRequest.serializer(), Validations.pairRequest)

        val response = pairing.redeem(
            token = request.pairingToken,
            deviceId = request.deviceId,
            deviceName = request.deviceName,
        )

        call.respondProtocol(HttpStatusCode.Created, response, PairResponse.serializer(), Validations.pairResponse)
    }
}
