package net.devemperor.dictate.companion.server

import io.konform.validation.Validation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.devemperor.dictate.companion.domain.CompanionException
import net.devemperor.dictate.shared.protocol.DecodeResult
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.ErrorCode
import net.devemperor.dictate.shared.protocol.ErrorEnvelope
import net.devemperor.dictate.shared.protocol.ProtocolCodec
import net.devemperor.dictate.shared.protocol.ProtocolVersion
import net.devemperor.dictate.shared.protocol.ValidationDetail
import net.devemperor.dictate.shared.protocol.Validations

/**
 * The server's door to the wire — the counterpart of `DispatchClient`'s on the phone.
 *
 * Every route reads its body through [receiveValidated] and writes its answer through
 * [respondProtocol]; **neither** uses Ktor's `call.receive<T>()` / `call.respond(dto)`. That is the
 * whole point: `call.receive<T>()` would deserialise straight past the Konform validation, and the
 * contract would be enforced on the phone and nowhere else (ADR-0016). `ProtocolCodec` is the only
 * door, on both sides, in both directions.
 */

/**
 * Protocol version **first** — before auth, before validation.
 *
 * An outdated peer must hear "your protocol version is not supported", not a puzzling list of
 * validation errors about fields it does not have. Konform *also* pins the version (so a bug on our
 * own send side is caught), but its verdict would arrive as `VALIDATION_FAILED`, which is the wrong
 * story to tell.
 *
 * @throws CompanionException.ProtocolVersionException
 */
fun requireSupportedProtocol(version: Int) {
    if (!ProtocolVersion.isSupported(version)) {
        throw CompanionException.ProtocolVersionException(version.toString())
    }
}

/** The bodyless requests (`GET /v1/health`, `GET /v1/sync/cursor`) carry the version in a header. */
fun ApplicationCall.requireSupportedProtocolHeader() {
    // Absent is tolerated: the header is how a *bodyless* request states its version, and a peer
    // that omits it has said nothing we can contradict. A present-but-unparsable one has.
    val raw = request.header(Endpoints.HEADER_PROTOCOL) ?: return
    val version = raw.toIntOrNull() ?: throw CompanionException.ProtocolVersionException(raw)
    requireSupportedProtocol(version)
}

suspend fun <T> ApplicationCall.receiveValidated(
    serializer: KSerializer<T>,
    validation: Validation<T>,
): T {
    val raw = receiveText()
    requireSupportedProtocolInBody(raw)

    return when (val decoded = ProtocolCodec.decode(raw, serializer, validation)) {
        is DecodeResult.Ok -> decoded.value
        is DecodeResult.Invalid -> throw CompanionException.ValidationException(decoded.details)
        is DecodeResult.Malformed ->
            // A broken wire format and a violated constraint both leave the peer with the same job
            // (fix the payload), so they share the 400 — the "<body>" path distinguishes the two.
            //
            // The message is a CONSTANT, never `decoded.reason`: a kotlinx-serialization decode error
            // can quote a window of the raw input in its message (the dictated text), and this detail
            // flows into the ErrorEnvelope, which is logged on both sides and must never carry the
            // dictated text (ADR-0016 redaction contract; ProtocolCalls.respondEnvelope KDoc).
            throw CompanionException.ValidationException(
                listOf(ValidationDetail(path = "<body>", message = "malformed request body")),
            )
    }
}

/**
 * Peeks at `protocolVersion` **before** the typed decode.
 *
 * It has to be read out of the raw JSON: a v2 peer may have renamed or dropped a field this DTO
 * declares as required, in which case the typed decode fails as `Malformed` and the version — the
 * one thing that explains *why* — would never be looked at.
 */
private fun requireSupportedProtocolInBody(raw: String) {
    val version = try {
        ProtocolCodec.json.parseToJsonElement(raw).jsonObject["protocolVersion"]?.jsonPrimitive?.content?.toIntOrNull()
    } catch (e: IllegalArgumentException) {
        // Not JSON at all (or not an object). Not our business here — the decode below reports it
        // as Malformed, with a message that names the actual problem.
        null
    }
    if (version != null) requireSupportedProtocol(version)
}

/**
 * Writes a response through the codec, which **validates it on the way out**.
 *
 * A response that violates our own contract is a bug on this side, and it surfaces here as a 500
 * rather than as a mysterious "response out of contract" on the phone.
 */
suspend fun <T> ApplicationCall.respondProtocol(
    status: HttpStatusCode,
    value: T,
    serializer: KSerializer<T>,
    validation: Validation<T>,
) {
    respondText(
        text = ProtocolCodec.encode(value, serializer, validation),
        contentType = ContentType.Application.Json,
        status = status,
    )
}

/**
 * The **only** place an [ErrorEnvelope] is built on this side.
 *
 * [message] and [details] are logged on both sides, so neither may carry a dictated text or a
 * secret — the domain exceptions are written to that rule and this function never takes anything
 * else (`ErrorEnvelopeRedactionTest` in `:shared` pins the format).
 */
suspend fun ApplicationCall.respondEnvelope(
    status: HttpStatusCode,
    code: ErrorCode,
    message: String,
    details: List<ValidationDetail> = emptyList(),
) {
    respondText(
        text = ProtocolCodec.encode(
            ErrorEnvelope(code = code, message = message, details = details),
            ErrorEnvelope.serializer(),
            Validations.errorEnvelope,
        ),
        contentType = ContentType.Application.Json,
        status = status,
    )
}
