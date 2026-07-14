package net.devemperor.dictate.companion.server

import net.devemperor.dictate.companion.CompanionContainer
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.data.SqlDelightHistoryRepository
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.fakes.FakeTextInserter
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.shared.auth.AuthHeaders
import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.client.DispatchClient
import net.devemperor.dictate.shared.client.DispatchError
import net.devemperor.dictate.shared.client.DispatchResult
import net.devemperor.dictate.shared.protocol.DecodeResult
import net.devemperor.dictate.shared.protocol.DispatchRequest
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.ErrorCode
import net.devemperor.dictate.shared.protocol.ErrorEnvelope
import net.devemperor.dictate.shared.protocol.InsertionOutcomeWire
import net.devemperor.dictate.shared.protocol.ProtocolCodec
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.protocol.Validations
import net.devemperor.dictate.shared.transport.HttpResponseLite
import net.devemperor.dictate.shared.transport.OkHttpDispatchTransport
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The heart of the safety net: a **real** `embeddedServer(CIO, port = 0)`, driven by the **real**
 * `shared` [DispatchClient] over **real** HTTP on 127.0.0.1.
 *
 * Not `testApplication`, and not a fake transport. Both sides of this protocol are written against
 * the same `:shared` module, and the only way to prove they actually agree — on paths, headers,
 * status codes, the error envelope, JSON defaults, UTF-8 — is to make them talk to each other over
 * a socket. Everything a fake would abstract away is exactly where a client/server pair drifts.
 *
 * The only fakes are the ones that *have* to be: the Win32 inserter (this VM runs Linux) and the
 * clock (a 120-second token TTL is not a 120-second test).
 */
class CompanionE2ETest {

    private val inserter = FakeTextInserter()
    private val clock = MutableClock()
    private val database = CompanionDatabase.inMemory()
    private val devices = SqlDelightDeviceRepository(database)
    private val history = SqlDelightHistoryRepository(database)

    private lateinit var container: CompanionContainer
    private lateinit var server: CompanionServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        container = CompanionContainer.forTest(
            inserter = inserter,
            clock = clock,
            devices = devices,
            history = history,
            serverName = SERVER_NAME,
        )
        server = CompanionServer(container, host = "127.0.0.1", port = 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.boundPort()}"
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ── Pairing ─────────────────────────────────────────────────────────────────────────

    @Test
    fun pairing_happyPath_yieldsASecretThatIsNotTheToken() {
        val token = container.pairingService.issue().token

        val response = client().pair(token, DEVICE_ID, DEVICE_NAME).success()

        assertEquals(DEVICE_ID, response.deviceId)
        assertEquals(SERVER_NAME, response.serverName)
        assertNotEquals(token, response.deviceSecret)
        assertTrue("the device secret is 256 bit, base64url", response.deviceSecret.length >= 32)
        assertEquals(DEVICE_NAME, devices.findById(DEVICE_ID)?.name)
    }

    @Test
    fun pairing_withAnUnissuedToken_isTokenInvalid() {
        container.pairingService.issue()

        val error = client().pair("ZZZZZZZZ", DEVICE_ID, DEVICE_NAME).failure()

        assertEquals(DispatchError.TokenInvalid, error)
        assertNull(devices.findById(DEVICE_ID))
    }

    @Test
    fun pairing_reusingATheAlreadyRedeemedToken_isTokenConsumed() {
        val token = container.pairingService.issue().token
        client().pair(token, DEVICE_ID, DEVICE_NAME).success()

        val error = client().pair(token, "second-device-id", "Pixel 9").failure()

        // 409, not 401: one token, one device — and the phone can say so precisely.
        assertEquals(DispatchError.TokenConsumed, error)
        assertNull(devices.findById("second-device-id"))
    }

    @Test
    fun pairing_afterTheTokenTtl_isTokenExpired() {
        val token = container.pairingService.issue().token
        clock.advance(Endpoints.PAIRING_TOKEN_TTL_MILLIS)

        val error = client().pair(token, DEVICE_ID, DEVICE_NAME).failure()

        assertEquals(DispatchError.TokenExpired, error)
        assertNull(devices.findById(DEVICE_ID))
    }

    // ── Dispatch ────────────────────────────────────────────────────────────────────────

    @Test
    fun dispatch_deliversTheTextVerbatim_andRecordsIt() {
        val credentials = pairedCredentials()

        val response = client(credentials).dispatch(request(SESSION_ID, TRICKY_TEXT)).success()

        assertTrue(response.delivered)
        assertFalse(response.duplicate)
        assertEquals(InsertionOutcomeWire.TYPED_CTRL_V, response.outcome)
        // Byte-for-byte: no trimming, no newline mangling, emoji and umlauts intact over the wire.
        assertEquals(listOf(TRICKY_TEXT), inserter.inserted)

        val row = history.findById(SESSION_ID)!!
        assertEquals(TRICKY_TEXT, row.text)
        assertEquals(DEVICE_ID, row.deviceId)
        assertTrue(row.dispatched)
        assertEquals(InsertionOutcome.TYPED_CTRL_V, row.lastOutcome)
    }

    @Test
    fun dispatch_withAWrongSecret_isUnauthorized_andStoresNothing() {
        pairedCredentials()
        val wrong = Credentials(deviceId = DEVICE_ID, deviceSecret = "not-the-secret-but-long-enough-000")

        val error = client(wrong).dispatch(request(SESSION_ID, "hello")).failure()

        assertEquals(DispatchError.Unauthorized, error)
        assertEquals(0, history.count(null))
        assertEquals(emptyList<String>(), inserter.inserted)
    }

    @Test
    fun dispatch_fromAnUnknownDevice_isUnauthorized() {
        val credentials = pairedCredentials()
        val unknown = credentials.copy(deviceId = "never-paired-device")

        val error = client(unknown).dispatch(request(SESSION_ID, "hello")).failure()

        // The same 401 as a wrong secret: the phone learns nothing about which device ids exist.
        assertEquals(DispatchError.Unauthorized, error)
        assertEquals(0, history.count(null))
    }

    @Test
    fun dispatch_withoutAnyCredentials_isUnauthorized() {
        val error = DispatchClient(OkHttpDispatchTransport(baseUrl), credentials = { null })
            .dispatch(request(SESSION_ID, "hello"))
            .failure()

        assertEquals(DispatchError.Unauthorized, error)
    }

    @Test
    fun dispatch_ofTheSameSessionTwice_isIdempotent() {
        val credentials = pairedCredentials()
        val client = client(credentials)

        val first = client.dispatch(request(SESSION_ID, "hello")).success()
        val second = client.dispatch(request(SESSION_ID, "hello")).success()

        assertFalse(first.duplicate)
        assertTrue("the phone's retry after an ambiguous timeout must not double the row", second.duplicate)
        assertTrue(second.delivered)
        assertEquals(1, history.count(null))
        // The text IS inserted twice — that is deliberate: the phone only retries when it did not
        // hear a 200, i.e. when the user quite possibly never saw the text land.
        assertEquals(2, inserter.inserted.size)
    }

    @Test
    fun dispatch_whenTheInserterOnlyReachedTheClipboard_isStillDelivered() {
        val credentials = pairedCredentials()
        inserter.nextOutcome = InsertionOutcome.CLIPBOARD_ONLY

        val response = client(credentials).dispatch(request(SESSION_ID, "hello")).success()

        // 200, not 503: the text is on the PC and one Ctrl+V away. The phone acknowledges it and
        // shows an INFO hint instead of offering the same text a second time as a pending part.
        assertTrue(response.delivered)
        assertEquals(InsertionOutcomeWire.CLIPBOARD_ONLY, response.outcome)
    }

    @Test
    fun dispatch_whenTheInserterFails_is503_andTheClientFallsBackToPending() {
        val credentials = pairedCredentials()
        inserter.nextOutcome = InsertionOutcome.FAILED

        val error = client(credentials).dispatch(request(SESSION_ID, "hello")).failure()

        assertEquals(DispatchError.InsertionFailed, error)
        assertEquals("insert first, persist second — a failed insertion leaves no row", 0, history.count(null))
    }

    @Test
    fun dispatch_atTheTextLimit_isAccepted() {
        val credentials = pairedCredentials()
        val text = "x".repeat(Endpoints.MAX_TEXT_LENGTH)

        val response = client(credentials).dispatch(request(SESSION_ID, text)).success()

        assertTrue(response.delivered)
        assertEquals(Endpoints.MAX_TEXT_LENGTH, history.findById(SESSION_ID)?.text?.length)
    }

    @Test
    fun dispatch_pastTheTextLimit_isRejectedBeforeItEvenLeavesThePhone() {
        val credentials = pairedCredentials()
        val text = "x".repeat(Endpoints.MAX_TEXT_LENGTH + 1)

        val error = client(credentials).dispatch(request(SESSION_ID, text)).failure()

        // Konform validates on encode too — the client refuses to put its own broken payload on the
        // wire, and names the property that is wrong (ADR-0016).
        val invalid = error as DispatchError.Invalid
        assertEquals(listOf("text"), invalid.details.map { it.path })
        assertEquals(0, history.count(null))
    }

    @Test
    fun dispatch_pastTheTextLimit_isAlsoRejectedByTheServer() {
        val credentials = pairedCredentials()
        val oversize = "x".repeat(Endpoints.MAX_TEXT_LENGTH + 1)

        // Deliberately bypassing the client's own encode-validation: the point is to prove the
        // *server* enforces the same limit. "Zod on both sides" is only true if both sides say no.
        val response = rawPost(
            Endpoints.DISPATCH,
            """{"protocolVersion":1,"sessionId":"$SESSION_ID","text":"$oversize","createdAt":42,"origin":"KEYBOARD"}""",
            AuthHeaders.forDevice(credentials),
        )

        assertEquals(400, response.status)
        val envelope = response.envelope()
        assertEquals(ErrorCode.VALIDATION_FAILED, envelope.code)
        assertEquals(listOf("text"), envelope.details.map { it.path })
        // The envelope is logged on both sides — it may name the limit, never the text.
        assertFalse(envelope.details.single().message.contains("xxx"))
        assertEquals(0, history.count(null))
    }

    @Test
    fun dispatch_withAnUnsupportedProtocolVersion_is400_beforeAnythingElse() {
        val credentials = pairedCredentials()

        val response = rawPost(
            Endpoints.DISPATCH,
            """{"protocolVersion":2,"sessionId":"$SESSION_ID","text":"hello","createdAt":42,"origin":"KEYBOARD"}""",
            AuthHeaders.forDevice(credentials),
        )

        assertEquals(400, response.status)
        assertEquals(ErrorCode.PROTOCOL_VERSION_UNSUPPORTED, response.envelope().code)
        assertEquals(emptyList<String>(), inserter.inserted)
    }

    @Test
    fun aBodyThatIsNotEvenJson_is400_notA500() {
        val credentials = pairedCredentials()

        val response = rawPost(Endpoints.DISPATCH, "this is not json", AuthHeaders.forDevice(credentials))

        assertEquals(400, response.status)
        assertEquals(ErrorCode.VALIDATION_FAILED, response.envelope().code)
        assertEquals(listOf("<body>"), response.envelope().details.map { it.path })
    }

    @Test
    fun aMalformedBody_neverEchoesTheDictatedTextInTheEnvelope() {
        // Regression (review — L2-F2): a body truncated mid-`text` makes kotlinx-serialization throw
        // a decode error whose message can quote a window of the raw input — i.e. the dictated text.
        // That message must NOT reach the ErrorEnvelope, which is logged on BOTH sides and is the one
        // structure the redaction contract (ADR-0016) says never carries the dictated text.
        val credentials = pairedCredentials()
        val sentinel = "TOPSECRETDICTATIONdoNotLeakMe"
        // Deliberately truncated: no closing quote/brace → SerializationException → Malformed branch.
        val truncated = """{"protocolVersion":1,"sessionId":"$SESSION_ID","createdAt":42,"origin":"KEYBOARD","text":"$sentinel"""

        val response = rawPost(Endpoints.DISPATCH, truncated, AuthHeaders.forDevice(credentials))

        assertEquals(400, response.status)
        assertEquals(ErrorCode.VALIDATION_FAILED, response.envelope().code)
        assertEquals(listOf("<body>"), response.envelope().details.map { it.path })
        assertFalse(
            "the malformed-decode message leaked the dictated text into the wire envelope",
            response.body.contains(sentinel),
        )
    }

    @Test
    fun anUnsupportedProtocolHeader_isRejectedBeforeAuth() {
        // No valid credentials at all — and yet the answer is 400 PROTOCOL_VERSION_UNSUPPORTED, not
        // 401: an outdated peer must be told to update, not left guessing about its secret.
        val response = rawGet(
            Endpoints.HEALTH,
            mapOf(
                Endpoints.HEADER_PROTOCOL to "2",
                Endpoints.HEADER_DEVICE_ID to "unknown-device-id",
                Endpoints.HEADER_AUTHORIZATION to "Bearer nope",
            ),
        )

        assertEquals(400, response.status)
        assertEquals(ErrorCode.PROTOCOL_VERSION_UNSUPPORTED, response.envelope().code)
    }

    // ── Health ──────────────────────────────────────────────────────────────────────────

    @Test
    fun health_reportsWhetherThisMachineCanActuallyType() {
        val credentials = pairedCredentials()

        assertTrue(client(credentials).health().success().canInsert)

        inserter.available = false
        val health = client(credentials).health().success()

        assertFalse(health.canInsert)
        assertEquals(SERVER_NAME, health.serverName)
        assertEquals(CompanionContainer.APP_VERSION, health.appVersion)
    }

    @Test
    fun health_withoutPairing_isUnauthorized() {
        val error = client(Credentials(DEVICE_ID, "a-secret-that-was-never-issued-000")).health().failure()

        assertEquals(DispatchError.Unauthorized, error)
    }

    // ── Transport failures ──────────────────────────────────────────────────────────────

    @Test
    fun aServerThatDoesNotAnswerInTime_isUnreachable_neverDelivered() {
        val credentials = pairedCredentials()
        inserter.delayMillis = 3_000

        val impatient = OkHttpClient.Builder()
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        val client = DispatchClient(OkHttpDispatchTransport(baseUrl, impatient), credentials = { credentials })

        val error = client.dispatch(request(SESSION_ID, "hello")).failure()

        // A timeout is *ambiguous* — the PC may well have got the text (and here, it did). It is
        // classified as a failure anyway: one pending part too many beats a text that vanishes.
        assertTrue("$error", error is DispatchError.Unreachable)
    }

    @Test
    fun aClosedPort_isUnreachable() {
        server.stop()

        val error = client(Credentials(DEVICE_ID, "secret-long-enough-to-pass-validation"))
            .dispatch(request(SESSION_ID, "hello"))
            .failure()

        assertTrue("$error", error is DispatchError.Unreachable)
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────

    private fun client(credentials: Credentials? = null) =
        DispatchClient(OkHttpDispatchTransport(baseUrl), credentials = { credentials })

    /** Pairs a device through the real HTTP route and returns its credentials. */
    private fun pairedCredentials(): Credentials {
        val token = container.pairingService.issue().token
        val response = client().pair(token, DEVICE_ID, DEVICE_NAME).success()
        return Credentials(deviceId = response.deviceId, deviceSecret = response.deviceSecret)
    }

    private fun request(sessionId: String, text: String) = DispatchRequest(
        sessionId = sessionId,
        text = text,
        createdAt = 42L,
        origin = SessionOriginWire.KEYBOARD,
    )

    /** Bypasses [DispatchClient] so a payload the client would refuse to send can still be sent. */
    private fun rawPost(path: String, body: String, headers: Map<String, String>): HttpResponseLite =
        OkHttpDispatchTransport(baseUrl).post(path, body, headers)

    private fun rawGet(path: String, headers: Map<String, String>): HttpResponseLite =
        OkHttpDispatchTransport(baseUrl).get(path, headers)

    private fun HttpResponseLite.envelope(): ErrorEnvelope =
        (ProtocolCodec.decode(body, ErrorEnvelope.serializer(), Validations.errorEnvelope) as DecodeResult.Ok).value

    private fun <T> DispatchResult<T>.success(): T = when (this) {
        is DispatchResult.Success -> value
        is DispatchResult.Failure -> throw AssertionError("expected success, got $error")
    }

    private fun <T> DispatchResult<T>.failure(): DispatchError = when (this) {
        is DispatchResult.Success -> throw AssertionError("expected a failure, got $value")
        is DispatchResult.Failure -> error
    }

    private companion object {
        const val DEVICE_ID = "test-device-0001"
        const val DEVICE_NAME = "Pixel 8"
        const val SERVER_NAME = "test-pc"
        const val SESSION_ID = "session-0001"

        /** Umlauts, an emoji, a newline and trailing whitespace — everything a naive server would eat. */
        const val TRICKY_TEXT = "Grüße aus München 🎉\nZeile zwei  "
    }
}
