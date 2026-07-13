package net.devemperor.dictate.windows

import net.devemperor.dictate.shared.protocol.CursorResponse
import net.devemperor.dictate.shared.protocol.DispatchResponse
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.InsertionOutcomeWire
import net.devemperor.dictate.shared.protocol.ProtocolCodec
import net.devemperor.dictate.shared.protocol.Validations
import net.devemperor.dictate.shared.transport.DispatchTransport
import net.devemperor.dictate.shared.transport.HttpResponseLite
import java.io.IOException

/**
 * A hand-written [DispatchTransport] fake for the Windows-dispatch tests — only the socket is fake,
 * the real encoding and validation still run (the port is dumb by design, ADR-0015).
 *
 * The dispatch reply is programmable: a delivered outcome, a raw status/body, or an IOException.
 * The GET (sync cursor) always answers "server knows nothing" so a follow-up sync ends UpToDate.
 */
class ProgrammableTransport(
    private val dispatch: () -> HttpResponseLite,
) : DispatchTransport {

    /** Records every POST'ed path so a test can assert the dispatch endpoint was hit. */
    val postedPaths = mutableListOf<String>()

    override fun post(path: String, body: String, headers: Map<String, String>): HttpResponseLite {
        postedPaths += path
        return dispatch()
    }

    override fun get(path: String, headers: Map<String, String>): HttpResponseLite =
        HttpResponseLite(
            status = 200,
            body = ProtocolCodec.encode(CursorResponse(cursor = null), CursorResponse.serializer(), Validations.cursorResponse),
        )

    companion object {
        /** A 200 with a delivered [DispatchResponse]. */
        fun delivered(
            sessionId: String,
            outcome: InsertionOutcomeWire = InsertionOutcomeWire.TYPED_CTRL_V,
        ): ProgrammableTransport = ProgrammableTransport {
            HttpResponseLite(
                status = 200,
                body = ProtocolCodec.encode(
                    DispatchResponse(sessionId = sessionId, delivered = true, outcome = outcome),
                    DispatchResponse.serializer(),
                    Validations.dispatchResponse,
                ),
            )
        }

        /** A transport that raises IOException on the dispatch POST (unreachable PC). */
        fun unreachable(): ProgrammableTransport = ProgrammableTransport {
            throw IOException("no route to host")
        }
    }
}

/** Endpoint constant re-exported so tests read intent without importing Endpoints everywhere. */
val DISPATCH_PATH: String = Endpoints.DISPATCH
