package net.devemperor.dictate.shared.client

import net.devemperor.dictate.shared.auth.AuthHeaders
import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.fakes.FakeTransport
import net.devemperor.dictate.shared.protocol.DispatchRequest
import net.devemperor.dictate.shared.protocol.DispatchResponse
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.ErrorCode
import net.devemperor.dictate.shared.protocol.ErrorEnvelope
import net.devemperor.dictate.shared.protocol.InputCommandResponse
import net.devemperor.dictate.shared.protocol.InputCommandWire
import net.devemperor.dictate.shared.protocol.InputCommandKindWire
import net.devemperor.dictate.shared.protocol.InputOutcomeWire
import net.devemperor.dictate.shared.protocol.InsertionOutcomeWire
import net.devemperor.dictate.shared.protocol.PairResponse
import net.devemperor.dictate.shared.protocol.ProtocolCodec
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.protocol.ValidationDetail
import net.devemperor.dictate.shared.protocol.Validations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Branch tests for [DispatchClient]'s error classification — one test per [DispatchError].
 *
 * The classification is what the Android side switches on to decide whether the user sees "PC
 * unreachable", "pair again" or "update the companion", so every branch has to be pinned. The
 * rule under all of them: **nothing but a parsed 200 with `delivered = true` is a success.**
 */
class DispatchClientTest {

    private val credentials = Credentials(deviceId = "device-1", deviceSecret = "secret-1")

    private fun client(transport: FakeTransport, credentials: Credentials? = this.credentials) =
        DispatchClient(transport) { credentials }

    private fun request(sessionId: String = "session-1", text: String = "hello") = DispatchRequest(
        sessionId = sessionId,
        text = text,
        createdAt = 1_700_000_000_000L,
        origin = SessionOriginWire.KEYBOARD,
    )

    private fun deliveredBody(outcome: InsertionOutcomeWire = InsertionOutcomeWire.TYPED_CTRL_V) =
        ProtocolCodec.encode(
            DispatchResponse(sessionId = "session-1", delivered = true, outcome = outcome),
            DispatchResponse.serializer(),
            Validations.dispatchResponse,
        )

    private fun errorBody(code: ErrorCode, details: List<ValidationDetail> = emptyList()) =
        ProtocolCodec.encode(
            ErrorEnvelope(code = code, message = code.name, details = details),
            ErrorEnvelope.serializer(),
            Validations.errorEnvelope,
        )

    private fun <T> failure(result: DispatchResult<T>): DispatchError {
        assertTrue("expected Failure, was $result", result is DispatchResult.Failure)
        return (result as DispatchResult.Failure).error
    }

    // ── Success ─────────────────────────────────────────────────────────────────────────

    @Test
    fun dispatch_200Delivered_isSuccess() {
        val transport = FakeTransport().respond(Endpoints.DISPATCH, 200, deliveredBody())

        val result = client(transport).dispatch(request())

        val response = (result as DispatchResult.Success).value
        assertTrue(response.delivered)
        assertEquals(InsertionOutcomeWire.TYPED_CTRL_V, response.outcome)
    }

    @Test
    fun dispatch_clipboardOnly_isStillASuccess() {
        // CLIPBOARD_ONLY means the text IS on the PC — it just was not typed. Delivered, so no
        // pending part; the user gets a notice instead (ADR-0018/0019).
        val transport = FakeTransport().respond(Endpoints.DISPATCH, 200, deliveredBody(InsertionOutcomeWire.CLIPBOARD_ONLY))

        val result = client(transport).dispatch(request())

        assertEquals(InsertionOutcomeWire.CLIPBOARD_ONLY, (result as DispatchResult.Success).value.outcome)
    }

    @Test
    fun dispatch_sendsTheAuthHeaders() {
        val transport = FakeTransport().respond(Endpoints.DISPATCH, 200, deliveredBody())

        client(transport).dispatch(request())

        val headers = transport.calls.single().headers
        assertEquals("Bearer secret-1", headers[Endpoints.HEADER_AUTHORIZATION])
        assertEquals("device-1", headers[Endpoints.HEADER_DEVICE_ID])
        assertEquals("1", headers[Endpoints.HEADER_PROTOCOL])
    }

    @Test
    fun pair_sendsNoAuthorizationHeader() {
        // The one-time token IS the credential — a Bearer header here would be a lie (ADR-0017).
        val body = ProtocolCodec.encode(
            PairResponse(deviceId = "device-1", deviceSecret = "s".repeat(43), serverName = "PC"),
            PairResponse.serializer(),
            Validations.pairResponse,
        )
        val transport = FakeTransport().respond(Endpoints.PAIR, 201, body)

        val result = client(transport, credentials = null).pair("K7M49QXR", "device-1", "Pixel 8")

        assertEquals("PC", (result as DispatchResult.Success).value.serverName)
        assertTrue(AuthHeaders.BEARER_PREFIX, !transport.calls.single().headers.containsKey(Endpoints.HEADER_AUTHORIZATION))
    }

    // ── Failure branches ────────────────────────────────────────────────────────────────

    @Test
    fun dispatch_ioException_isUnreachable() {
        val transport = FakeTransport().fail(Endpoints.DISPATCH, SocketTimeoutException("timeout"))

        val error = failure(client(transport).dispatch(request()))

        assertTrue(error.toString(), error is DispatchError.Unreachable)
        assertTrue(error.toString(), (error as DispatchError.Unreachable).cause.contains("SocketTimeoutException"))
    }

    @Test
    fun dispatch_200ButNotDelivered_isInsertionFailed_neverSuccess() {
        // A server that answers 200 while saying "not delivered" is contradicting itself. Trust the
        // delivery confirmation, not the status code — the text must not be treated as placed.
        val body = ProtocolCodec.encode(
            DispatchResponse(sessionId = "session-1", delivered = false, outcome = InsertionOutcomeWire.CLIPBOARD_ONLY),
            DispatchResponse.serializer(),
            Validations.dispatchResponse,
        )
        val transport = FakeTransport().respond(Endpoints.DISPATCH, 200, body)

        val error = failure(client(transport).dispatch(request()))

        assertEquals(DispatchError.InsertionFailed, error)
    }

    @Test
    fun dispatch_401Unauthorized_isUnauthorized() {
        val transport = FakeTransport().respond(Endpoints.DISPATCH, 401, errorBody(ErrorCode.UNAUTHORIZED))

        assertEquals(DispatchError.Unauthorized, failure(client(transport).dispatch(request())))
    }

    @Test
    fun dispatch_withoutCredentials_isUnauthorized_withoutTouchingTheNetwork() {
        val transport = FakeTransport()

        val error = failure(client(transport, credentials = null).dispatch(request()))

        assertEquals(DispatchError.Unauthorized, error)
        assertEquals(emptyList<Any>(), transport.calls)
    }

    @Test
    fun dispatch_400ValidationFailed_isInvalidWithDetails() {
        val details = listOf(ValidationDetail(path = "text", message = "must have at most 100000 characters"))
        val transport = FakeTransport().respond(Endpoints.DISPATCH, 400, errorBody(ErrorCode.VALIDATION_FAILED, details))

        val error = failure(client(transport).dispatch(request()))

        assertEquals(DispatchError.Invalid(details), error)
    }

    @Test
    fun dispatch_400ProtocolVersionUnsupported_isProtocolMismatch() {
        val transport = FakeTransport().respond(Endpoints.DISPATCH, 400, errorBody(ErrorCode.PROTOCOL_VERSION_UNSUPPORTED))

        assertEquals(DispatchError.ProtocolMismatch, failure(client(transport).dispatch(request())))
    }

    @Test
    fun dispatch_503InsertionFailed_isInsertionFailed() {
        val transport = FakeTransport().respond(Endpoints.DISPATCH, 503, errorBody(ErrorCode.INSERTION_FAILED))

        assertEquals(DispatchError.InsertionFailed, failure(client(transport).dispatch(request())))
    }

    @Test
    fun dispatch_500Internal_isServer() {
        val transport = FakeTransport().respond(Endpoints.DISPATCH, 500, errorBody(ErrorCode.INTERNAL))

        val error = failure(client(transport).dispatch(request()))

        assertEquals(DispatchError.Server(500, ErrorCode.INTERNAL.name), error)
    }

    @Test
    fun dispatch_unparsableErrorBody_isServer_andDoesNotEchoTheBody() {
        val transport = FakeTransport().respond(Endpoints.DISPATCH, 502, "<html>Bad Gateway from some proxy</html>")

        val error = failure(client(transport).dispatch(request()))

        val server = error as DispatchError.Server
        assertEquals(502, server.status)
        assertTrue(server.message, !server.message.contains("<html>"))
    }

    @Test
    fun dispatch_unparsableSuccessBody_isServer_neverSuccess() {
        val transport = FakeTransport().respond(Endpoints.DISPATCH, 200, "{ this is not our schema }")

        val error = failure(client(transport).dispatch(request()))

        assertTrue(error.toString(), error is DispatchError.Server)
    }

    @Test
    fun dispatch_payloadViolatingOurOwnContract_neverReachesTheWire() {
        val transport = FakeTransport()

        val error = failure(client(transport).dispatch(request(text = "")))

        assertEquals(listOf("text"), (error as DispatchError.Invalid).details.map { it.path })
        assertEquals(emptyList<Any>(), transport.calls)
    }

    // ── Pairing branches ────────────────────────────────────────────────────────────────

    @Test
    fun pair_401InvalidToken_isTokenInvalid() {
        val transport = FakeTransport().respond(Endpoints.PAIR, 401, errorBody(ErrorCode.INVALID_TOKEN))

        assertEquals(DispatchError.TokenInvalid, failure(client(transport).pair("K7M49QXR", "device-1", "Pixel 8")))
    }

    @Test
    fun pair_401TokenExpired_isTokenExpired() {
        val transport = FakeTransport().respond(Endpoints.PAIR, 401, errorBody(ErrorCode.TOKEN_EXPIRED))

        assertEquals(DispatchError.TokenExpired, failure(client(transport).pair("K7M49QXR", "device-1", "Pixel 8")))
    }

    @Test
    fun pair_409TokenConsumed_isTokenConsumed() {
        val transport = FakeTransport().respond(Endpoints.PAIR, 409, errorBody(ErrorCode.TOKEN_CONSUMED))

        assertEquals(DispatchError.TokenConsumed, failure(client(transport).pair("K7M49QXR", "device-1", "Pixel 8")))
    }

    // ── Input commands ──────────────────────────────────────────────────────────────────

    private fun inputBody(executed: Boolean = true, outcome: InputOutcomeWire = InputOutcomeWire.SENT) =
        ProtocolCodec.encode(
            InputCommandResponse(executed = executed, outcome = outcome),
            InputCommandResponse.serializer(),
            Validations.inputCommandResponse,
        )

    private fun inputCommands() = listOf(InputCommandWire(kind = InputCommandKindWire.CURSOR_LEFT, count = 3))

    @Test
    fun input_200Sent_isSuccess_andCarriesTheAuthHeaders() {
        val transport = FakeTransport().respond(Endpoints.INPUT, 200, inputBody())

        val result = client(transport).input(inputCommands())

        assertEquals(InputOutcomeWire.SENT, (result as DispatchResult.Success).value.outcome)
        val headers = transport.calls.single().headers
        assertEquals("Bearer secret-1", headers[Endpoints.HEADER_AUTHORIZATION])
        assertEquals("device-1", headers[Endpoints.HEADER_DEVICE_ID])
    }

    @Test
    fun input_404FromOldCompanion_isEndpointMissing_notServer() {
        // Ktor answers a bare, non-envelope 404 for an unknown route → must become the distinct
        // EndpointMissing so the app says "update the companion", not "PC unreachable".
        val transport = FakeTransport().respond(Endpoints.INPUT, 404, "Not Found")

        assertEquals(DispatchError.EndpointMissing, failure(client(transport).input(inputCommands())))
    }

    @Test
    fun input_503_isInsertionFailed_stillClassifiedNormally() {
        val transport = FakeTransport().respond(Endpoints.INPUT, 503, errorBody(ErrorCode.INSERTION_FAILED))

        assertEquals(DispatchError.InsertionFailed, failure(client(transport).input(inputCommands())))
    }

    @Test
    fun input_ioException_isUnreachable() {
        val transport = FakeTransport().fail(Endpoints.INPUT, SocketTimeoutException("timeout"))

        assertTrue(failure(client(transport).input(inputCommands())) is DispatchError.Unreachable)
    }

    // ── Health / cursor ─────────────────────────────────────────────────────────────────

    @Test
    fun health_200_isSuccess_andIsAGet() {
        val body = ProtocolCodec.encode(
            net.devemperor.dictate.shared.protocol.HealthResponse(serverName = "PC", appVersion = "1.0.0", canInsert = false),
            net.devemperor.dictate.shared.protocol.HealthResponse.serializer(),
            Validations.healthResponse,
        )
        val transport = FakeTransport().respond(Endpoints.HEALTH, 200, body)

        val result = client(transport).health()

        assertEquals(false, (result as DispatchResult.Success).value.canInsert)
        assertEquals("GET", transport.calls.single().method)
    }

    @Test
    fun cursor_ioException_isUnreachable() {
        val transport = FakeTransport().fail(Endpoints.SYNC_CURSOR, IOException("network is unreachable"))

        assertTrue(failure(client(transport).cursor()) is DispatchError.Unreachable)
    }
}
