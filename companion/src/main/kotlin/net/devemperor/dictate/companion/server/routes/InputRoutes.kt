package net.devemperor.dictate.companion.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import net.devemperor.dictate.companion.domain.InputCommandService
import net.devemperor.dictate.companion.server.receiveValidated
import net.devemperor.dictate.companion.server.respondProtocol
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.InputCommandRequest
import net.devemperor.dictate.shared.protocol.InputCommandResponse
import net.devemperor.dictate.shared.protocol.Validations

/**
 * `POST /v1/input` — a batch of keyboard actions to replay on the PC (§5.3).
 *
 * Authenticated, additive (an old companion has no such route → 404, which the phone maps to
 * "update the companion"). Reads through [receiveValidated] and answers through [respondProtocol] —
 * never Ktor's `call.receive`/`call.respond`, so the Konform contract is enforced here too
 * (ADR-0016).
 *
 * Always 200 once the payload is valid: the *outcome* (SENT / NO_FOREGROUND_WINDOW / REJECTED) rides
 * in the body's `executed` flag, mirroring dispatch's `delivered` — a keyboard action that could not
 * be injected is a `executed = false`, not an HTTP error.
 */
fun Route.inputRoutes(input: InputCommandService) {
    post(Endpoints.INPUT) {
        val request = call.receiveValidated(InputCommandRequest.serializer(), Validations.inputCommandRequest)

        val response = input.perform(request)

        call.respondProtocol(HttpStatusCode.OK, response, InputCommandResponse.serializer(), Validations.inputCommandResponse)
    }
}
