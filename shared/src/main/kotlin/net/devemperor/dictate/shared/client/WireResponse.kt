package net.devemperor.dictate.shared.client

import io.konform.validation.Validation
import kotlinx.serialization.KSerializer
import net.devemperor.dictate.shared.protocol.DecodeResult
import net.devemperor.dictate.shared.protocol.ErrorCode
import net.devemperor.dictate.shared.protocol.ErrorEnvelope
import net.devemperor.dictate.shared.protocol.ProtocolCodec
import net.devemperor.dictate.shared.protocol.Validations
import net.devemperor.dictate.shared.transport.HttpResponseLite
import java.io.IOException

/**
 * The response half of the wire, shared by [DispatchClient] and [CatalogClient].
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015). Both clients speak the same `:shared` protocol over the same transport, so
 * the "parse a 2xx through the codec, classify a non-2xx through the one [ErrorEnvelope] mapping"
 * logic is one implementation, not two: a second copy in the [CatalogClient] would be the exact
 * "repeat the same failure arm" the [DispatchResult] KDoc warns against.
 */

/** Parses a 2xx body through the codec; classifies any non-2xx through [classifyWireError]. */
internal fun <R> HttpResponseLite.parseWire(
    serializer: KSerializer<R>,
    validation: Validation<R>,
): DispatchResult<R> {
    if (status !in 200..299) return DispatchResult.Failure(classifyWireError(status, body))

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
 * The status alone is not enough — a 400 is a validation failure *or* a protocol mismatch, a 401 is a
 * bad secret *or* one of three token conditions, a 404 is a missing catalog entity *or* (bare, no
 * envelope) a missing route — so the [ErrorEnvelope] decides. When the envelope will not parse (a
 * proxy's HTML error page, or a bare Ktor 404) the status still yields a usable answer, which is what
 * lets a caller re-map a bare `Server(404)` to `EndpointMissing`.
 */
internal fun classifyWireError(status: Int, body: String): DispatchError {
    val envelope = (ProtocolCodec.decode(body, ErrorEnvelope.serializer(), Validations.errorEnvelope)
        as? DecodeResult.Ok)?.value
        // Deliberately not echoing the body: an unparsable error page is untrusted content and this
        // message goes into the logs. The length is enough to diagnose it.
        ?: return DispatchError.Server(status, "unparsable error body (${body.length} bytes)")

    return when (envelope.code) {
        ErrorCode.PROTOCOL_VERSION_UNSUPPORTED -> DispatchError.ProtocolMismatch
        ErrorCode.VALIDATION_FAILED -> DispatchError.Invalid(envelope.details)
        ErrorCode.UNAUTHORIZED -> DispatchError.Unauthorized
        ErrorCode.INVALID_TOKEN -> DispatchError.TokenInvalid
        ErrorCode.TOKEN_EXPIRED -> DispatchError.TokenExpired
        ErrorCode.TOKEN_CONSUMED -> DispatchError.TokenConsumed
        ErrorCode.INSERTION_FAILED -> DispatchError.InsertionFailed
        ErrorCode.CATALOG_ENTITY_NOT_FOUND -> DispatchError.EntityGone
        ErrorCode.INTERNAL -> DispatchError.Server(status, envelope.message)
    }
}

/** Never the message alone: a bare `SocketTimeoutException` message is often null or empty. */
internal fun IOException.describeWire(): String =
    "${this::class.java.simpleName}: ${message.orEmpty()}"
