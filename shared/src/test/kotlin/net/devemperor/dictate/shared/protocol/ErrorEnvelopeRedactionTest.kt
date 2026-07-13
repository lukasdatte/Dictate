package net.devemperor.dictate.shared.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the redaction rule: an [ErrorEnvelope] travels into the logs of **both** sides, so it may
 * name the violated *limit* but never the violating *value*.
 *
 * This is not hypothetical. Konform interpolates `{value}` in a constraint hint with the
 * validated value itself (`Constraint.createHint`) — a constraint written as
 * `addConstraint("'{value}' is too long")` on `DispatchRequest::text` would copy the entire
 * dictated text into the error message, and from there into the log files. The built-in
 * constraints template the *limit*, not the value, and these tests keep it that way.
 */
class ErrorEnvelopeRedactionTest {

    private val secretText = "the user dictated this and nobody else may read it"

    @Test
    fun decode_oversizedText_reportsThePathAndTheLimit_butNotTheText() {
        val oversized = secretText.repeat(Endpoints.MAX_TEXT_LENGTH / secretText.length + 1)
        assertTrue(oversized.length > Endpoints.MAX_TEXT_LENGTH)

        val raw = ProtocolCodec.json.encodeToString(
            DispatchRequest.serializer(),
            DispatchRequest(sessionId = "session-1", text = oversized, createdAt = 1L, origin = SessionOriginWire.KEYBOARD),
        )
        val result = ProtocolCodec.decode(raw, DispatchRequest.serializer(), Validations.dispatchRequest)

        val details = (result as DecodeResult.Invalid).details
        assertEquals(listOf("text"), details.map { it.path })
        assertTrue(details[0].message, details[0].message.contains(Endpoints.MAX_TEXT_LENGTH.toString()))
        assertFalse(details[0].message, details[0].message.contains(secretText))
    }

    @Test
    fun envelope_builtFromAViolation_neverSerializesTheOffendingText() {
        val oversized = secretText.repeat(Endpoints.MAX_TEXT_LENGTH / secretText.length + 1)
        val request = DispatchRequest(sessionId = "session-1", text = oversized, createdAt = 1L, origin = SessionOriginWire.KEYBOARD)
        val raw = ProtocolCodec.json.encodeToString(DispatchRequest.serializer(), request)
        val details = (ProtocolCodec.decode(raw, DispatchRequest.serializer(), Validations.dispatchRequest) as DecodeResult.Invalid).details

        val envelope = ErrorEnvelope(code = ErrorCode.VALIDATION_FAILED, message = "validation failed", details = details)
        val wire = ProtocolCodec.encode(envelope, ErrorEnvelope.serializer(), Validations.errorEnvelope)

        assertFalse(wire, wire.contains(secretText))
    }

    @Test
    fun sendSideViolation_exceptionMessage_doesNotLeakTheText() {
        // The send-side guard throws — and its message is the one that ends up in logcat.
        val oversized = secretText.repeat(Endpoints.MAX_TEXT_LENGTH / secretText.length + 1)
        val request = DispatchRequest(sessionId = "session-1", text = oversized, createdAt = 1L, origin = SessionOriginWire.KEYBOARD)

        val thrown = try {
            ProtocolCodec.encode(request, DispatchRequest.serializer(), Validations.dispatchRequest)
            null
        } catch (e: ProtocolViolationException) {
            e
        }

        requireNotNull(thrown) { "expected the send-side guard to reject an oversized text" }
        assertFalse(thrown.message, thrown.message!!.contains(secretText))
        assertEquals(listOf("text"), thrown.details.map { it.path })
    }

    @Test
    fun pairingSecretViolation_doesNotLeakTheSecret() {
        val secret = "s".repeat(8)
        val response = PairResponse(deviceId = "a".repeat(16), deviceSecret = secret, serverName = "PC")

        val details = Validations.pairResponse(response).errors
            .map { ValidationDetail(it.dataPath.removePrefix("."), it.message) }

        assertEquals(listOf("deviceSecret"), details.map { it.path })
        assertFalse(details[0].message, details[0].message.contains(secret))
    }
}
