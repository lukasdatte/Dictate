package net.devemperor.dictate.shared.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Wire-format tests for [ProtocolCodec] — the single door every payload passes through.
 *
 * Guards the three properties the protocol rests on: a DTO survives a round-trip unchanged, an
 * additive field from a newer peer is tolerated (that is what allows optional fields without a
 * [ProtocolVersion] bump), and a broken wire format is told apart from a contract violation.
 */
class ProtocolCodecTest {

    private fun dispatchRequest() = DispatchRequest(
        sessionId = "session-1",
        text = "hello windows",
        createdAt = 1_700_000_000_000L,
        origin = SessionOriginWire.KEYBOARD,
    )

    private fun <T> decodeOk(result: DecodeResult<T>): T {
        assertTrue("expected Ok, was $result", result is DecodeResult.Ok)
        return (result as DecodeResult.Ok).value
    }

    private fun <T> decodeInvalid(result: DecodeResult<T>): List<ValidationDetail> {
        assertTrue("expected Invalid, was $result", result is DecodeResult.Invalid)
        return (result as DecodeResult.Invalid).details
    }

    // ── Round-trips ─────────────────────────────────────────────────────────────────────

    @Test
    fun roundTrip_pairRequest() {
        val original = PairRequest(pairingToken = "K7M49QXR", deviceId = "11111111-2222-3333-4444-555555555555", deviceName = "Pixel 8")
        val raw = ProtocolCodec.encode(original, PairRequest.serializer(), Validations.pairRequest)

        assertEquals(original, decodeOk(ProtocolCodec.decode(raw, PairRequest.serializer(), Validations.pairRequest)))
    }

    @Test
    fun roundTrip_pairResponse() {
        val original = PairResponse(
            deviceId = "11111111-2222-3333-4444-555555555555",
            deviceSecret = "a".repeat(43),
            serverName = "WORKSTATION",
        )
        val raw = ProtocolCodec.encode(original, PairResponse.serializer(), Validations.pairResponse)

        assertEquals(original, decodeOk(ProtocolCodec.decode(raw, PairResponse.serializer(), Validations.pairResponse)))
    }

    @Test
    fun roundTrip_dispatchRequest() {
        val raw = ProtocolCodec.encode(dispatchRequest(), DispatchRequest.serializer(), Validations.dispatchRequest)

        assertEquals(dispatchRequest(), decodeOk(ProtocolCodec.decode(raw, DispatchRequest.serializer(), Validations.dispatchRequest)))
    }

    @Test
    fun roundTrip_dispatchResponse() {
        val original = DispatchResponse(
            sessionId = "session-1",
            delivered = true,
            outcome = InsertionOutcomeWire.CLIPBOARD_ONLY,
            duplicate = true,
        )
        val raw = ProtocolCodec.encode(original, DispatchResponse.serializer(), Validations.dispatchResponse)

        assertEquals(original, decodeOk(ProtocolCodec.decode(raw, DispatchResponse.serializer(), Validations.dispatchResponse)))
    }

    @Test
    fun roundTrip_cursorResponse_withAndWithoutCursor() {
        val withCursor = CursorResponse(cursor = SyncCursor(lastCreatedAt = 42L, lastSessionId = "session-9"))
        val rawWith = ProtocolCodec.encode(withCursor, CursorResponse.serializer(), Validations.cursorResponse)
        assertEquals(withCursor, decodeOk(ProtocolCodec.decode(rawWith, CursorResponse.serializer(), Validations.cursorResponse)))

        val empty = CursorResponse(cursor = null)
        val rawEmpty = ProtocolCodec.encode(empty, CursorResponse.serializer(), Validations.cursorResponse)
        // explicitNulls = false → an unknown cursor is an absent key, not `"cursor": null`.
        assertTrue(rawEmpty, !rawEmpty.contains("cursor"))
        assertEquals(empty, decodeOk(ProtocolCodec.decode(rawEmpty, CursorResponse.serializer(), Validations.cursorResponse)))
    }

    @Test
    fun roundTrip_syncRequestAndResponse() {
        val request = SyncRequest(
            items = listOf(
                SessionUpsert("session-1", "one", 1L, SessionOriginWire.KEYBOARD, dispatched = true),
                SessionUpsert("session-2", "two", 2L, SessionOriginWire.POST_PROCESSING, dispatched = false),
            ),
        )
        val rawRequest = ProtocolCodec.encode(request, SyncRequest.serializer(), Validations.syncRequest)
        assertEquals(request, decodeOk(ProtocolCodec.decode(rawRequest, SyncRequest.serializer(), Validations.syncRequest)))

        val response = SyncResponse(accepted = 2, cursor = SyncCursor(2L, "session-2"))
        val rawResponse = ProtocolCodec.encode(response, SyncResponse.serializer(), Validations.syncResponse)
        assertEquals(response, decodeOk(ProtocolCodec.decode(rawResponse, SyncResponse.serializer(), Validations.syncResponse)))
    }

    @Test
    fun roundTrip_healthResponse() {
        val original = HealthResponse(serverName = "WORKSTATION", appVersion = "1.0.0", canInsert = false, supportsInputCommands = true)
        val raw = ProtocolCodec.encode(original, HealthResponse.serializer(), Validations.healthResponse)

        assertEquals(original, decodeOk(ProtocolCodec.decode(raw, HealthResponse.serializer(), Validations.healthResponse)))
    }

    @Test
    fun decode_healthFromOldCompanion_defaultsInputSupportToFalse() {
        // An older companion's health body has no `supportsInputCommands` key at all → the additive
        // default must land on `false`, so the phone treats it as "no keyboard-action channel".
        val raw = """{"protocolVersion":1,"serverName":"PC","appVersion":"1.0.0","canInsert":true}"""

        val health = decodeOk(ProtocolCodec.decode(raw, HealthResponse.serializer(), Validations.healthResponse))
        assertEquals(false, health.supportsInputCommands)
    }

    @Test
    fun roundTrip_inputCommandRequest() {
        val original = InputCommandRequest(
            commands = listOf(
                InputCommandWire(kind = InputCommandKindWire.TYPE_TEXT, text = "hi"),
                InputCommandWire(kind = InputCommandKindWire.CURSOR_LEFT, count = 4),
                InputCommandWire(kind = InputCommandKindWire.REDO),
            ),
        )
        val raw = ProtocolCodec.encode(original, InputCommandRequest.serializer(), Validations.inputCommandRequest)

        assertEquals(original, decodeOk(ProtocolCodec.decode(raw, InputCommandRequest.serializer(), Validations.inputCommandRequest)))
    }

    @Test
    fun roundTrip_inputCommandResponse() {
        val original = InputCommandResponse(executed = false, outcome = InputOutcomeWire.NO_FOREGROUND_WINDOW)
        val raw = ProtocolCodec.encode(original, InputCommandResponse.serializer(), Validations.inputCommandResponse)

        assertEquals(original, decodeOk(ProtocolCodec.decode(raw, InputCommandResponse.serializer(), Validations.inputCommandResponse)))
    }

    @Test
    fun encode_inputCommand_textOnCursorMove_throws() {
        val broken = InputCommandRequest(commands = listOf(InputCommandWire(kind = InputCommandKindWire.CURSOR_LEFT, text = "oops")))

        try {
            ProtocolCodec.encode(broken, InputCommandRequest.serializer(), Validations.inputCommandRequest)
            fail("expected ProtocolViolationException")
        } catch (e: ProtocolViolationException) {
            assertEquals(listOf("commands[0]"), e.details.map { it.path })
        }
    }

    @Test
    fun roundTrip_errorEnvelope() {
        val original = ErrorEnvelope(
            code = ErrorCode.VALIDATION_FAILED,
            message = "validation failed",
            details = listOf(ValidationDetail(path = "text", message = "must have at most 100000 characters")),
        )
        val raw = ProtocolCodec.encode(original, ErrorEnvelope.serializer(), Validations.errorEnvelope)

        assertEquals(original, decodeOk(ProtocolCodec.decode(raw, ErrorEnvelope.serializer(), Validations.errorEnvelope)))
    }

    // ── Wire-compatibility ──────────────────────────────────────────────────────────────

    @Test
    fun encode_alwaysWritesProtocolVersion() {
        val raw = ProtocolCodec.encode(dispatchRequest(), DispatchRequest.serializer(), Validations.dispatchRequest)

        assertTrue(raw, raw.contains(""""protocolVersion":1"""))
    }

    @Test
    fun decode_unknownAdditiveField_isTolerated() {
        val raw = """
            {"protocolVersion":1,"sessionId":"session-1","text":"hello windows",
             "createdAt":1700000000000,"origin":"KEYBOARD","futureField":"from a newer peer"}
        """.trimIndent()

        assertEquals(dispatchRequest(), decodeOk(ProtocolCodec.decode(raw, DispatchRequest.serializer(), Validations.dispatchRequest)))
    }

    @Test
    fun decode_missingRequiredField_isMalformed() {
        val raw = """{"protocolVersion":1,"text":"hello","createdAt":1,"origin":"KEYBOARD"}"""

        val result = ProtocolCodec.decode(raw, DispatchRequest.serializer(), Validations.dispatchRequest)

        assertTrue(result.toString(), result is DecodeResult.Malformed)
    }

    @Test
    fun decode_notEvenJson_isMalformed() {
        val result = ProtocolCodec.decode("<html>502 Bad Gateway</html>", DispatchRequest.serializer(), Validations.dispatchRequest)

        assertTrue(result.toString(), result is DecodeResult.Malformed)
    }

    @Test
    fun decode_unknownEnumValue_isMalformed() {
        val raw = """{"protocolVersion":1,"sessionId":"s","text":"t","createdAt":1,"origin":"TELEPATHY"}"""

        val result = ProtocolCodec.decode(raw, DispatchRequest.serializer(), Validations.dispatchRequest)

        assertTrue(result.toString(), result is DecodeResult.Malformed)
    }

    @Test
    fun decode_unsupportedProtocolVersion_isInvalidWithPath() {
        val raw = """{"protocolVersion":2,"sessionId":"s","text":"t","createdAt":1,"origin":"KEYBOARD"}"""

        val details = decodeInvalid(ProtocolCodec.decode(raw, DispatchRequest.serializer(), Validations.dispatchRequest))

        assertEquals(listOf("protocolVersion"), details.map { it.path })
        assertTrue(details[0].message, details[0].message.contains("unsupported protocol version 2"))
    }

    @Test
    fun decode_valueViolation_isInvalidWithPropertyPath() {
        val raw = """{"protocolVersion":1,"sessionId":"","text":"t","createdAt":-1,"origin":"KEYBOARD"}"""

        val details = decodeInvalid(ProtocolCodec.decode(raw, DispatchRequest.serializer(), Validations.dispatchRequest))

        assertEquals(setOf("sessionId", "createdAt"), details.map { it.path }.toSet())
    }

    @Test
    fun decode_violationInsideSyncItem_carriesTheIndexInThePath() {
        val request = SyncRequest(
            items = listOf(
                SessionUpsert("session-1", "one", 1L, SessionOriginWire.KEYBOARD, dispatched = true),
                SessionUpsert("", "two", 2L, SessionOriginWire.KEYBOARD, dispatched = false),
            ),
        )
        val raw = ProtocolCodec.json.encodeToString(SyncRequest.serializer(), request)

        val details = decodeInvalid(ProtocolCodec.decode(raw, SyncRequest.serializer(), Validations.syncRequest))

        assertEquals(listOf("items[1].sessionId"), details.map { it.path })
    }

    // ── Send-side validation ────────────────────────────────────────────────────────────

    @Test
    fun encode_violatingPayload_throwsWithDetails() {
        val broken = dispatchRequest().copy(sessionId = "")

        try {
            ProtocolCodec.encode(broken, DispatchRequest.serializer(), Validations.dispatchRequest)
            fail("expected ProtocolViolationException")
        } catch (e: ProtocolViolationException) {
            assertEquals(listOf("sessionId"), e.details.map { it.path })
        }
    }
}
