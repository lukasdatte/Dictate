package net.devemperor.dictate.shared.transport

import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.client.DispatchClient
import net.devemperor.dictate.shared.client.DispatchError
import net.devemperor.dictate.shared.client.DispatchResult
import net.devemperor.dictate.shared.protocol.DispatchRequest
import net.devemperor.dictate.shared.protocol.DispatchResponse
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.InsertionOutcomeWire
import net.devemperor.dictate.shared.protocol.ProtocolCodec
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.protocol.Validations
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Real HTTP: real headers, real timeouts, a real half-closed socket.
 *
 * The fake transport proves the *classification*; this proves the *layer beneath it*, which is
 * where the dangerous cases live. A timeout and a truncated response are exactly the situations in
 * which a text could be reported as delivered when it was not — so both are asserted to come out
 * as [DispatchError.Unreachable], and never as a success.
 */
class OkHttpDispatchTransportTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Short timeouts — the production defaults (3 s / 8 s) would make this suite crawl. */
    private fun transport() = OkHttpDispatchTransport(
        baseUrl = server.url("/").toString(),
        client = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build(),
    )

    private fun client() = DispatchClient(transport()) { Credentials("device-1", "secret-1") }

    private fun request() = DispatchRequest(
        sessionId = "session-1",
        text = "hello windows",
        createdAt = 1_700_000_000_000L,
        origin = SessionOriginWire.KEYBOARD,
    )

    private fun deliveredBody() = ProtocolCodec.encode(
        DispatchResponse(sessionId = "session-1", delivered = true, outcome = InsertionOutcomeWire.TYPED_CTRL_V),
        DispatchResponse.serializer(),
        Validations.dispatchResponse,
    )

    @Test
    fun dispatch_happyPath_sendsCorrectPathHeadersAndBody() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(deliveredBody()))

        val result = client().dispatch(request())

        assertTrue(result.toString(), result is DispatchResult.Success)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(Endpoints.DISPATCH, recorded.path)
        assertEquals("Bearer secret-1", recorded.getHeader(Endpoints.HEADER_AUTHORIZATION))
        assertEquals("device-1", recorded.getHeader(Endpoints.HEADER_DEVICE_ID))
        assertEquals("1", recorded.getHeader(Endpoints.HEADER_PROTOCOL))
        assertTrue(recorded.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        assertTrue(recorded.body.readUtf8().contains(""""sessionId":"session-1""""))
    }

    @Test
    fun baseUrlWithTrailingSlash_doesNotProduceADoubleSlashPath() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(deliveredBody()))
        val transport = OkHttpDispatchTransport(baseUrl = server.url("/").toString())

        DispatchClient(transport) { Credentials("device-1", "secret-1") }.dispatch(request())

        assertEquals(Endpoints.DISPATCH, server.takeRequest().path)
    }

    @Test
    fun serverSlowerThanTheReadTimeout_isUnreachable_neverDelivered() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(deliveredBody())
                .setBodyDelay(5, TimeUnit.SECONDS),
        )

        val result = client().dispatch(request())

        val error = (result as DispatchResult.Failure).error
        assertTrue(error.toString(), error is DispatchError.Unreachable)
    }

    @Test
    fun connectionKilledMidResponseBody_isUnreachable_neverDelivered() {
        // The dangerous one: the status line said 200, the body never finished. A truncated body
        // must NOT be handed up as a delivery confirmation.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(deliveredBody())
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val result = client().dispatch(request())

        val error = (result as DispatchResult.Failure).error
        assertTrue(error.toString(), error is DispatchError.Unreachable)
    }

    @Test
    fun serverNotListening_isUnreachable() {
        server.shutdown()

        val result = client().dispatch(request())

        assertTrue(result.toString(), (result as DispatchResult.Failure).error is DispatchError.Unreachable)
    }

    @Test
    fun health_isAGetWithoutABody() {
        val body = ProtocolCodec.encode(
            net.devemperor.dictate.shared.protocol.HealthResponse(serverName = "PC", appVersion = "1.0.0", canInsert = true),
            net.devemperor.dictate.shared.protocol.HealthResponse.serializer(),
            Validations.healthResponse,
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = client().health()

        assertTrue((result as DispatchResult.Success).value.canInsert)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals(Endpoints.HEALTH, recorded.path)
        assertEquals(0L, recorded.bodySize)
    }
}
