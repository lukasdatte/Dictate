package net.devemperor.dictate.shared.client

import kotlinx.serialization.KSerializer
import io.konform.validation.Validation
import net.devemperor.dictate.shared.auth.AuthHeaders
import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.protocol.CursorResponse
import net.devemperor.dictate.shared.protocol.DispatchRequest
import net.devemperor.dictate.shared.protocol.DispatchResponse
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.HealthResponse
import net.devemperor.dictate.shared.protocol.InputCommandRequest
import net.devemperor.dictate.shared.protocol.InputCommandResponse
import net.devemperor.dictate.shared.protocol.InputCommandWire
import net.devemperor.dictate.shared.protocol.PairRequest
import net.devemperor.dictate.shared.protocol.PairResponse
import net.devemperor.dictate.shared.protocol.ProtocolCodec
import net.devemperor.dictate.shared.protocol.ProtocolViolationException
import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.protocol.SyncRequest
import net.devemperor.dictate.shared.protocol.SyncResponse
import net.devemperor.dictate.shared.protocol.Validations
import net.devemperor.dictate.shared.transport.DispatchTransport
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

    /**
     * Replays a batch of keyboard actions on the PC (§4.4). Order of [commands] is the execution
     * order.
     *
     * A 404 is re-mapped from the generic [DispatchError.Server] to [DispatchError.EndpointMissing]:
     * an old companion has no such route, and the app must tell the user to update it rather than
     * blame the network. Every other outcome flows through the shared classification.
     */
    fun input(commands: List<InputCommandWire>): DispatchResult<InputCommandResponse> {
        val result = authenticated { credentials ->
            call(
                request = InputCommandRequest(commands = commands),
                requestSerializer = InputCommandRequest.serializer(),
                requestValidation = Validations.inputCommandRequest,
                path = Endpoints.INPUT,
                headers = AuthHeaders.forDevice(credentials),
                responseSerializer = InputCommandResponse.serializer(),
                responseValidation = Validations.inputCommandResponse,
            )
        }
        if (result is DispatchResult.Failure) {
            val error = result.error
            if (error is DispatchError.Server && error.status == 404) {
                return DispatchResult.Failure(DispatchError.EndpointMissing)
            }
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
            return DispatchResult.Failure(DispatchError.Unreachable(e.describeWire()))
        }
        // Response parsing + non-2xx classification are shared with CatalogClient (WireResponse.kt).
        return response.parseWire(responseSerializer, responseValidation)
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
            return DispatchResult.Failure(DispatchError.Unreachable(e.describeWire()))
        }
        return response.parseWire(responseSerializer, responseValidation)
    }
}
