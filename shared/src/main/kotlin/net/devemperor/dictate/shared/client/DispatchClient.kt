package net.devemperor.dictate.shared.client

import kotlinx.serialization.KSerializer
import io.konform.validation.Validation
import net.devemperor.dictate.shared.auth.AuthHeaders
import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.protocol.CursorResponse
import net.devemperor.dictate.shared.protocol.DecodeResult
import net.devemperor.dictate.shared.protocol.DispatchRequest
import net.devemperor.dictate.shared.protocol.DispatchResponse
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.ErrorCode
import net.devemperor.dictate.shared.protocol.ErrorEnvelope
import net.devemperor.dictate.shared.protocol.HealthResponse
import net.devemperor.dictate.shared.protocol.PairRequest
import net.devemperor.dictate.shared.protocol.PairResponse
import net.devemperor.dictate.shared.protocol.ProtocolCodec
import net.devemperor.dictate.shared.protocol.ProtocolViolationException
import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.protocol.SyncRequest
import net.devemperor.dictate.shared.protocol.SyncResponse
import net.devemperor.dictate.shared.protocol.Validations
import net.devemperor.dictate.shared.transport.DispatchTransport
import net.devemperor.dictate.shared.transport.HttpResponseLite
import java.io.IOException

/**
 * The phone's view of the companion — every call the protocol offers, and nothing else.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015); the companion's E2E tests drive its own server through this same client,
 * which is what keeps the two sides from drifting apart.
 *
 * Blocking. The caller owns the thread (a background executor, never the main thread).
 *
 * [credentials] is a **lambda, not a value**: the secret is read from the preferences at call
 * time, so re-pairing takes effect without rebuilding the client.
 */
class DispatchClient(
    private val transport: DispatchTransport,
    private val credentials: () -> Credentials?,
) {

    /**
     * Redeems the one-time pairing token for a long-lived device secret.
     *
     * Unauthenticated: the token *is* the credential (ADR-0017).
     */
    fun pair(token: String, deviceId: String, deviceName: String): DispatchResult<PairResponse> =
        call(
            request = PairRequest(pairingToken = token, deviceId = deviceId, deviceName = deviceName),
            requestSerializer = PairRequest.serializer(),
            requestValidation = Validations.pairRequest,
            path = Endpoints.PAIR,
            headers = AuthHeaders.forPairing(),
            responseSerializer = PairResponse.serializer(),
            responseValidation = Validations.pairResponse,
        )

    /**
     * Sends one finished dictation to the PC.
     *
     * **The core rule of the whole package:** a [DispatchResult.Success] is returned *only* for a
     * parsed 200 whose `delivered` is true. Every other outcome — timeout, aborted connection,
     * unparsable body, a 200 that says `delivered = false` — is a failure, and the text falls into
     * the existing pending-part mechanism. Anything else risks a text that silently vanishes.
     */
    fun dispatch(request: DispatchRequest): DispatchResult<DispatchResponse> {
        val result = authenticated { credentials ->
            call(
                request = request,
                requestSerializer = DispatchRequest.serializer(),
                requestValidation = Validations.dispatchRequest,
                path = Endpoints.DISPATCH,
                headers = AuthHeaders.forDevice(credentials),
                responseSerializer = DispatchResponse.serializer(),
                responseValidation = Validations.dispatchResponse,
            )
        }

        if (result is DispatchResult.Success && !result.value.delivered) {
            // The server should have answered 503 instead. It did not, so trust the flag, not the
            // status: `delivered` is the delivery confirmation, and it says no.
            return DispatchResult.Failure(DispatchError.InsertionFailed)
        }
        return result
    }

    fun health(): DispatchResult<HealthResponse> = authenticated { credentials ->
        read(
            path = Endpoints.HEALTH,
            headers = AuthHeaders.forDevice(credentials),
            responseSerializer = HealthResponse.serializer(),
            responseValidation = Validations.healthResponse,
        )
    }

    /** Asks the companion how far it knows our history — the sync's self-healing round trip (ADR-0020). */
    fun cursor(): DispatchResult<CursorResponse> = authenticated { credentials ->
        read(
            path = Endpoints.SYNC_CURSOR,
            headers = AuthHeaders.forDevice(credentials),
            responseSerializer = CursorResponse.serializer(),
            responseValidation = Validations.cursorResponse,
        )
    }

    /** Pushes one page of history. Idempotent server-side over `sessionId`. */
    fun sync(items: List<SessionUpsert>): DispatchResult<SyncResponse> = authenticated { credentials ->
        call(
            request = SyncRequest(items = items),
            requestSerializer = SyncRequest.serializer(),
            requestValidation = Validations.syncRequest,
            path = Endpoints.SYNC,
            headers = AuthHeaders.forDevice(credentials),
            responseSerializer = SyncResponse.serializer(),
            responseValidation = Validations.syncResponse,
        )
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────

    private fun <T> authenticated(block: (Credentials) -> DispatchResult<T>): DispatchResult<T> {
        // Not paired at all is indistinguishable *to the caller* from a rejected secret: both mean
        // "this text is not going to the PC right now" and both end in the pending-part fallback.
        val credentials = credentials() ?: return DispatchResult.Failure(DispatchError.Unauthorized)
        return block(credentials)
    }

    private fun <Q, R> call(
        request: Q,
        requestSerializer: KSerializer<Q>,
        requestValidation: Validation<Q>,
        path: String,
        headers: Map<String, String>,
        responseSerializer: KSerializer<R>,
        responseValidation: Validation<R>,
    ): DispatchResult<R> {
        val body = try {
            ProtocolCodec.encode(request, requestSerializer, requestValidation)
        } catch (e: ProtocolViolationException) {
            // Our own payload is out of contract — never put it on the wire; report it as the bug
            // class it is, with the property paths intact.
            return DispatchResult.Failure(DispatchError.Invalid(e.details))
        }

        val response = try {
            transport.post(path, body, headers)
        } catch (e: IOException) {
            return DispatchResult.Failure(DispatchError.Unreachable(e.describe()))
        }
        return response.parse(responseSerializer, responseValidation)
    }

    private fun <R> read(
        path: String,
        headers: Map<String, String>,
        responseSerializer: KSerializer<R>,
        responseValidation: Validation<R>,
    ): DispatchResult<R> {
        val response = try {
            transport.get(path, headers)
        } catch (e: IOException) {
            return DispatchResult.Failure(DispatchError.Unreachable(e.describe()))
        }
        return response.parse(responseSerializer, responseValidation)
    }

    private fun <R> HttpResponseLite.parse(
        serializer: KSerializer<R>,
        validation: Validation<R>,
    ): DispatchResult<R> {
        if (status !in 200..299) return DispatchResult.Failure(classifyError(status, body))

        return when (val decoded = ProtocolCodec.decode(body, serializer, validation)) {
            is DecodeResult.Ok -> DispatchResult.Success(decoded.value)
            is DecodeResult.Malformed ->
                DispatchResult.Failure(DispatchError.Server(status, "unparsable response: ${decoded.reason}"))
            is DecodeResult.Invalid ->
                DispatchResult.Failure(DispatchError.Server(status, "response out of contract: ${decoded.details}"))
        }
    }

    /**
     * Maps a non-2xx onto the classification.
     *
     * The status alone is not enough — a 400 is a validation failure *or* a protocol mismatch, and
     * a 401 is a bad secret *or* one of three token conditions — so the [ErrorEnvelope] decides.
     * When the envelope will not parse (a proxy's HTML error page, say) the status still yields a
     * usable answer.
     */
    private fun classifyError(status: Int, body: String): DispatchError {
        val envelope = (ProtocolCodec.decode(body, ErrorEnvelope.serializer(), Validations.errorEnvelope)
            as? DecodeResult.Ok)?.value
            // Deliberately not echoing the body: an unparsable error page is untrusted content and
            // this message goes into the logs. The length is enough to diagnose it.
            ?: return DispatchError.Server(status, "unparsable error body (${body.length} bytes)")

        return when (envelope.code) {
            ErrorCode.PROTOCOL_VERSION_UNSUPPORTED -> DispatchError.ProtocolMismatch
            ErrorCode.VALIDATION_FAILED -> DispatchError.Invalid(envelope.details)
            ErrorCode.UNAUTHORIZED -> DispatchError.Unauthorized
            ErrorCode.INVALID_TOKEN -> DispatchError.TokenInvalid
            ErrorCode.TOKEN_EXPIRED -> DispatchError.TokenExpired
            ErrorCode.TOKEN_CONSUMED -> DispatchError.TokenConsumed
            ErrorCode.INSERTION_FAILED -> DispatchError.InsertionFailed
            ErrorCode.INTERNAL -> DispatchError.Server(status, envelope.message)
        }
    }

    /** Never the message alone: a bare `SocketTimeoutException` message is often null or empty. */
    private fun IOException.describe(): String =
        "${this::class.java.simpleName}: ${message.orEmpty()}"
}
