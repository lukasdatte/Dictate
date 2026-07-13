package net.devemperor.dictate.companion.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import net.devemperor.dictate.companion.domain.SyncService
import net.devemperor.dictate.companion.server.plugins.device
import net.devemperor.dictate.companion.server.receiveValidated
import net.devemperor.dictate.companion.server.respondProtocol
import net.devemperor.dictate.shared.protocol.CursorResponse
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.SyncRequest
import net.devemperor.dictate.shared.protocol.SyncResponse
import net.devemperor.dictate.shared.protocol.Validations

/**
 * `GET /v1/sync/cursor` + `POST /v1/sync` — the phone mirrors its history onto the PC (ADR-0020).
 *
 * One-way by design: the server never sends history back. Its cursor is nothing but a receive
 * acknowledgement, and an upsert simply overwrites — the phone is the authority.
 */
fun Route.syncRoutes(sync: SyncService) {

    get(Endpoints.SYNC_CURSOR) {
        call.respondProtocol(
            HttpStatusCode.OK,
            sync.cursor(),
            CursorResponse.serializer(),
            Validations.cursorResponse,
        )
    }

    post(Endpoints.SYNC) {
        val request = call.receiveValidated(SyncRequest.serializer(), Validations.syncRequest)

        call.respondProtocol(
            HttpStatusCode.OK,
            sync.apply(call.device, request),
            SyncResponse.serializer(),
            Validations.syncResponse,
        )
    }
}
