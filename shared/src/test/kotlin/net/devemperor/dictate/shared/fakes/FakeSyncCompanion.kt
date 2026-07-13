package net.devemperor.dictate.shared.fakes

import net.devemperor.dictate.shared.protocol.CursorResponse
import net.devemperor.dictate.shared.protocol.DecodeResult
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.ErrorCode
import net.devemperor.dictate.shared.protocol.ErrorEnvelope
import net.devemperor.dictate.shared.protocol.ProtocolCodec
import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.protocol.SyncCursor
import net.devemperor.dictate.shared.protocol.SyncRequest
import net.devemperor.dictate.shared.protocol.SyncResponse
import net.devemperor.dictate.shared.protocol.Validations
import net.devemperor.dictate.shared.sync.Cursor
import net.devemperor.dictate.shared.sync.toCursor
import net.devemperor.dictate.shared.transport.DispatchTransport
import net.devemperor.dictate.shared.transport.HttpResponseLite
import java.io.IOException

/**
 * An in-memory stand-in for the companion's sync endpoints, sitting where the socket would be.
 *
 * Canned responses cannot test paging honestly: the interesting properties — the cursor really
 * advances, a repeated page really is a no-op, an interrupted run really resumes without gaps —
 * only exist if something on the other end actually *stores* the rows. So this one does, with the
 * same idempotent-upsert-over-sessionId semantics the real server will have (ADR-0020).
 *
 * It decodes and encodes through the real [ProtocolCodec], so the wire contract is exercised in
 * both directions — only the transport is fake.
 */
class FakeSyncCompanion(
    /** The real thing accepts at most a page of this size; the fake enforces it too. */
    private val maxBatch: Int = Endpoints.MAX_SYNC_BATCH,
) : DispatchTransport {

    private val stored = linkedMapOf<String, SessionUpsert>()

    /** Set to make the next `POST /v1/sync` fail as if the network had dropped. */
    var failNextSyncWith: IOException? = null

    /** Set to make `GET /v1/sync/cursor` fail. */
    var failCursorWith: IOException? = null

    /** Every page the client pushed, in order — lets a test assert that nothing was sent twice. */
    val pushedPages = mutableListOf<List<SessionUpsert>>()

    val rows: List<SessionUpsert> get() = stored.values.toList()

    /** Simulates a companion whose database was wiped: it forgets everything, cursor included. */
    fun wipe() = stored.clear()

    fun cursor(): SyncCursor? = stored.values.map { it.toCursor() }.maxWithOrNull(Cursor)

    override fun get(path: String, headers: Map<String, String>): HttpResponseLite {
        require(path == Endpoints.SYNC_CURSOR) { "FakeSyncCompanion does not serve GET $path" }
        failCursorWith?.let { throw it }

        return ok(ProtocolCodec.encode(CursorResponse(cursor = cursor()), CursorResponse.serializer(), Validations.cursorResponse))
    }

    override fun post(path: String, body: String, headers: Map<String, String>): HttpResponseLite {
        require(path == Endpoints.SYNC) { "FakeSyncCompanion does not serve POST $path" }
        failNextSyncWith?.let {
            failNextSyncWith = null
            throw it
        }

        val request = when (val decoded = ProtocolCodec.decode(body, SyncRequest.serializer(), Validations.syncRequest)) {
            is DecodeResult.Ok -> decoded.value
            is DecodeResult.Invalid -> return error(400, ErrorCode.VALIDATION_FAILED)
            is DecodeResult.Malformed -> return error(400, ErrorCode.VALIDATION_FAILED)
        }
        require(request.items.size <= maxBatch) { "page of ${request.items.size} exceeds the server cap of $maxBatch" }

        pushedPages += request.items
        // Idempotent over sessionId — a resent page overwrites and changes nothing else.
        request.items.forEach { stored[it.sessionId] = it }

        val response = SyncResponse(accepted = request.items.size, cursor = cursor())
        return ok(ProtocolCodec.encode(response, SyncResponse.serializer(), Validations.syncResponse))
    }

    private fun ok(body: String) = HttpResponseLite(200, body)

    private fun error(status: Int, code: ErrorCode) = HttpResponseLite(
        status,
        ProtocolCodec.encode(ErrorEnvelope(code = code, message = code.name), ErrorEnvelope.serializer(), Validations.errorEnvelope),
    )
}
