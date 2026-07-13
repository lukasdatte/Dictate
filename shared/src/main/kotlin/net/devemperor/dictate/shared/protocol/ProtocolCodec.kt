package net.devemperor.dictate.shared.protocol

import io.konform.validation.Validation
import io.konform.validation.ValidationError
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The outcome of reading a payload off the wire.
 *
 * [Malformed] and [Invalid] are kept apart because they mean different things to the caller:
 * malformed is a **broken peer** (not even JSON, or a required field missing — nothing to say
 * back except "400, your wire format is broken"), invalid is a **contract violation** that maps
 * 1:1 onto `ErrorEnvelope(VALIDATION_FAILED, details)` and tells the peer exactly which property
 * is wrong.
 */
sealed class DecodeResult<out T> {
    data class Ok<T>(val value: T) : DecodeResult<T>()
    data class Malformed(val reason: String) : DecodeResult<Nothing>()
    data class Invalid(val details: List<ValidationDetail>) : DecodeResult<Nothing>()
}

/**
 * Thrown by [ProtocolCodec.encode] when we are about to send a payload that violates our own
 * contract.
 *
 * A send-side bug must surface where it is born, not as a puzzling 400 on the far side. It
 * carries the [details] so the caller can map it straight onto its own error classification
 * without parsing a message string.
 */
class ProtocolViolationException(
    val details: List<ValidationDetail>,
) : IllegalArgumentException("outgoing payload violates its own contract: $details")

/**
 * The ONE wire authority — the only way a payload enters or leaves the protocol.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015).
 *
 * Client and server both call exactly this; neither can skip the validation, because there is no
 * other door. It mirrors the role `StructuredResponseCodec` plays for the AI conversation layer
 * (ADR-0012 §2: "the single wire authority").
 *
 * Encoding validates too, and that is not belt-and-braces: it is where "Zod on both sides" is
 * actually paid for. A payload is checked once by its sender and once by its receiver, so a bug
 * on either side is caught on the side that owns it.
 */
object ProtocolCodec {

    /**
     * `ignoreUnknownKeys` — an additive field from a newer peer must not break an older one;
     * that is exactly what lets an optional field ship without a [ProtocolVersion] bump.
     * `encodeDefaults` — otherwise `protocolVersion` (a defaulted field) would never be written.
     * `explicitNulls = false` — a null cursor is an absent key, not `"cursor": null`.
     */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** @throws ProtocolViolationException if [value] does not satisfy [validation]. */
    fun <T> encode(value: T, serializer: KSerializer<T>, validation: Validation<T>): String {
        val errors = validation(value).errors
        if (errors.isNotEmpty()) throw ProtocolViolationException(errors.toDetails())
        return json.encodeToString(serializer, value)
    }

    fun <T> decode(raw: String, serializer: KSerializer<T>, validation: Validation<T>): DecodeResult<T> {
        val value = try {
            json.decodeFromString(serializer, raw)
        } catch (e: SerializationException) {
            // Covers malformed JSON, a missing required field and a wrong type alike — all of
            // them mean the same to the caller: this peer is not speaking our wire format.
            return DecodeResult.Malformed(e.message ?: e::class.java.simpleName)
        }

        val errors = validation(value).errors
        return if (errors.isEmpty()) DecodeResult.Ok(value) else DecodeResult.Invalid(errors.toDetails())
    }

    /**
     * Konform renders a path as `.items[3].sessionId`; the wire format drops the leading dot so
     * the peer sees `items[3].sessionId` (and a top-level property as plain `text`).
     */
    private fun List<ValidationError>.toDetails(): List<ValidationDetail> =
        map { ValidationDetail(path = it.dataPath.removePrefix("."), message = it.message) }
}
