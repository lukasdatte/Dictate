package net.devemperor.dictate.companion.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import net.devemperor.dictate.companion.domain.DispatchService
import net.devemperor.dictate.companion.server.plugins.device
import net.devemperor.dictate.companion.server.receiveValidated
import net.devemperor.dictate.companion.server.respondProtocol
import net.devemperor.dictate.shared.protocol.DispatchRequest
import net.devemperor.dictate.shared.protocol.DispatchResponse
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.Validations

/**
 * `POST /v1/dispatch` — a finished dictation arrives.
 *
 * The 200 **is** the delivery confirmation (ADR-0017): there is no second acknowledgement channel,
 * so this route may only answer 200 once the text has actually been placed. Everything that could
 * go wrong leaves as an exception and becomes an `ErrorEnvelope` in the one mapper.
 */
fun Route.dispatchRoutes(dispatch: DispatchService) {
    post(Endpoints.DISPATCH) {
        val request = call.receiveValidated(DispatchRequest.serializer(), Validations.dispatchRequest)

        val response = dispatch.dispatch(call.device, request)

        call.respondProtocol(HttpStatusCode.OK, response, DispatchResponse.serializer(), Validations.dispatchResponse)
    }
}
