package net.devemperor.dictate.shared.protocol

import io.konform.validation.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary tests for every constraint in [Validations] — each one is exercised at `n` (accepted)
 * and `n + 1` (rejected), because an off-by-one in a shared schema is a bug both sides agree on
 * and neither side notices.
 */
class ValidationsTest {

    private fun <T> paths(validation: Validation<T>, value: T): List<String> =
        validation(value).errors.map { it.dataPath.removePrefix(".") }

    private fun <T> assertValid(validation: Validation<T>, value: T) {
        assertEquals(emptyList<String>(), paths(validation, value))
    }

    // ── PairRequest ─────────────────────────────────────────────────────────────────────

    private fun pairRequest(
        token: String = "K7M49QXR",
        deviceId: String = "11111111-2222-3333-4444-555555555555",
        deviceName: String = "Pixel 8",
        version: Int = ProtocolVersion.CURRENT,
    ) = PairRequest(protocolVersion = version, pairingToken = token, deviceId = deviceId, deviceName = deviceName)

    @Test
    fun pairRequest_validToken_isAccepted() {
        assertValid(Validations.pairRequest, pairRequest())
    }

    @Test
    fun pairRequest_tokenTooShort_isRejected() {
        assertEquals(listOf("pairingToken"), paths(Validations.pairRequest, pairRequest(token = "K7M49QX")))
    }

    @Test
    fun pairRequest_tokenWithAmbiguousCrockfordLetters_isRejected() {
        // I, L, O and U are excluded from the alphabet precisely because a human misreads them.
        listOf("K7M49QXI", "K7M49QXL", "K7M49QXO", "K7M49QXU").forEach { token ->
            assertEquals(token, listOf("pairingToken"), paths(Validations.pairRequest, pairRequest(token = token)))
        }
    }

    @Test
    fun pairRequest_tokenInItsDisplayFormWithDash_isRejected() {
        // The QR and the wire carry the normalised token; the dashed "K7M4-9QXR" is display only.
        assertEquals(listOf("pairingToken"), paths(Validations.pairRequest, pairRequest(token = "K7M4-9QXR")))
    }

    @Test
    fun pairRequest_deviceIdBoundaries() {
        assertValid(Validations.pairRequest, pairRequest(deviceId = "a".repeat(Endpoints.MIN_DEVICE_ID_LENGTH)))
        assertValid(Validations.pairRequest, pairRequest(deviceId = "a".repeat(Endpoints.MAX_DEVICE_ID_LENGTH)))

        assertEquals(listOf("deviceId"), paths(Validations.pairRequest, pairRequest(deviceId = "a".repeat(Endpoints.MIN_DEVICE_ID_LENGTH - 1))))
        assertEquals(listOf("deviceId"), paths(Validations.pairRequest, pairRequest(deviceId = "a".repeat(Endpoints.MAX_DEVICE_ID_LENGTH + 1))))
    }

    @Test
    fun pairRequest_deviceNameBoundaries() {
        assertValid(Validations.pairRequest, pairRequest(deviceName = "a"))
        assertValid(Validations.pairRequest, pairRequest(deviceName = "a".repeat(Endpoints.MAX_DEVICE_NAME_LENGTH)))

        assertEquals(listOf("deviceName"), paths(Validations.pairRequest, pairRequest(deviceName = "")))
        assertEquals(listOf("deviceName"), paths(Validations.pairRequest, pairRequest(deviceName = "a".repeat(Endpoints.MAX_DEVICE_NAME_LENGTH + 1))))
    }

    @Test
    fun pairRequest_unsupportedProtocolVersion_isRejected() {
        assertEquals(listOf("protocolVersion"), paths(Validations.pairRequest, pairRequest(version = 2)))
        assertEquals(listOf("protocolVersion"), paths(Validations.pairRequest, pairRequest(version = 0)))
    }

    // ── DispatchRequest ─────────────────────────────────────────────────────────────────

    private fun dispatchRequest(
        sessionId: String = "session-1",
        text: String = "hello",
        createdAt: Long = 1L,
        version: Int = ProtocolVersion.CURRENT,
    ) = DispatchRequest(
        protocolVersion = version,
        sessionId = sessionId,
        text = text,
        createdAt = createdAt,
        origin = SessionOriginWire.KEYBOARD,
    )

    @Test
    fun dispatchRequest_textBoundaries() {
        assertValid(Validations.dispatchRequest, dispatchRequest(text = "a"))
        assertValid(Validations.dispatchRequest, dispatchRequest(text = "a".repeat(Endpoints.MAX_TEXT_LENGTH)))

        assertEquals(listOf("text"), paths(Validations.dispatchRequest, dispatchRequest(text = "")))
        assertEquals(listOf("text"), paths(Validations.dispatchRequest, dispatchRequest(text = "a".repeat(Endpoints.MAX_TEXT_LENGTH + 1))))
    }

    @Test
    fun dispatchRequest_sessionIdBoundaries() {
        assertValid(Validations.dispatchRequest, dispatchRequest(sessionId = "a"))
        assertValid(Validations.dispatchRequest, dispatchRequest(sessionId = "a".repeat(Endpoints.MAX_SESSION_ID_LENGTH)))

        assertEquals(listOf("sessionId"), paths(Validations.dispatchRequest, dispatchRequest(sessionId = "")))
        assertEquals(listOf("sessionId"), paths(Validations.dispatchRequest, dispatchRequest(sessionId = "a".repeat(Endpoints.MAX_SESSION_ID_LENGTH + 1))))
    }

    @Test
    fun dispatchRequest_createdAtBoundaries() {
        assertValid(Validations.dispatchRequest, dispatchRequest(createdAt = 0L))

        assertEquals(listOf("createdAt"), paths(Validations.dispatchRequest, dispatchRequest(createdAt = -1L)))
    }

    // ── SyncRequest ─────────────────────────────────────────────────────────────────────

    private fun upsert(sessionId: String = "session-1", text: String = "t", createdAt: Long = 1L) =
        SessionUpsert(sessionId, text, createdAt, SessionOriginWire.KEYBOARD, dispatched = false)

    @Test
    fun syncRequest_batchSizeBoundaries() {
        val full = List(Endpoints.MAX_SYNC_BATCH) { upsert(sessionId = "session-$it") }
        assertValid(Validations.syncRequest, SyncRequest(items = full))

        val overfull = List(Endpoints.MAX_SYNC_BATCH + 1) { upsert(sessionId = "session-$it") }
        assertEquals(listOf("items"), paths(Validations.syncRequest, SyncRequest(items = overfull)))
    }

    @Test
    fun syncRequest_emptyBatch_isAccepted() {
        // An empty page is how "nothing new" looks — it must not be a protocol violation.
        assertValid(Validations.syncRequest, SyncRequest(items = emptyList()))
    }

    @Test
    fun syncRequest_emptyTextInAnItem_isAccepted() {
        // Unlike a dispatch: the sync mirrors the history as it is.
        assertValid(Validations.syncRequest, SyncRequest(items = listOf(upsert(text = ""))))
    }

    @Test
    fun syncRequest_badItem_carriesTheIndexInThePath() {
        val items = listOf(upsert(), upsert(sessionId = ""), upsert(createdAt = -1L))

        assertEquals(
            listOf("items[1].sessionId", "items[2].createdAt"),
            paths(Validations.syncRequest, SyncRequest(items = items)),
        )
    }

    // ── InputCommandRequest ─────────────────────────────────────────────────────────────

    private fun typeText(text: String = "hi") = InputCommandWire(kind = InputCommandKindWire.TYPE_TEXT, text = text)
    private fun cursorLeft(count: Int = 1) = InputCommandWire(kind = InputCommandKindWire.CURSOR_LEFT, count = count)

    @Test
    fun inputCommandRequest_batchSizeBoundaries() {
        val full = List(Endpoints.MAX_INPUT_BATCH) { cursorLeft() }
        assertValid(Validations.inputCommandRequest, InputCommandRequest(commands = full))

        val overfull = List(Endpoints.MAX_INPUT_BATCH + 1) { cursorLeft() }
        assertEquals(listOf("commands"), paths(Validations.inputCommandRequest, InputCommandRequest(commands = overfull)))
    }

    @Test
    fun inputCommandRequest_emptyBatch_isRejected() {
        // An empty batch is nothing to do — the send window never flushes one, so it is a bug.
        assertEquals(listOf("commands"), paths(Validations.inputCommandRequest, InputCommandRequest(commands = emptyList())))
    }

    @Test
    fun inputCommandRequest_countBoundaries() {
        assertValid(Validations.inputCommandRequest, InputCommandRequest(commands = listOf(cursorLeft(count = Endpoints.MAX_INPUT_REPEAT))))

        assertEquals(listOf("commands[0].count"), paths(Validations.inputCommandRequest, InputCommandRequest(commands = listOf(cursorLeft(count = 0)))))
        assertEquals(listOf("commands[0].count"), paths(Validations.inputCommandRequest, InputCommandRequest(commands = listOf(cursorLeft(count = Endpoints.MAX_INPUT_REPEAT + 1)))))
    }

    @Test
    fun inputCommandRequest_textOnlyOnTypeText() {
        assertValid(Validations.inputCommandRequest, InputCommandRequest(commands = listOf(typeText())))

        // text on a non-TYPE_TEXT command → whole-element violation at commands[0]
        assertEquals(
            listOf("commands[0]"),
            paths(Validations.inputCommandRequest, InputCommandRequest(commands = listOf(InputCommandWire(kind = InputCommandKindWire.BACKSPACE, text = "x")))),
        )
        // TYPE_TEXT without text → same whole-element violation
        assertEquals(
            listOf("commands[0]"),
            paths(Validations.inputCommandRequest, InputCommandRequest(commands = listOf(InputCommandWire(kind = InputCommandKindWire.TYPE_TEXT, text = null)))),
        )
    }

    @Test
    fun inputCommandRequest_textLengthBoundaries() {
        assertValid(Validations.inputCommandRequest, InputCommandRequest(commands = listOf(typeText("a".repeat(Endpoints.MAX_TEXT_LENGTH)))))

        // Length is a whole-element constrain (see Validations), so the path is the element, not `.text`.
        assertEquals(listOf("commands[0]"), paths(Validations.inputCommandRequest, InputCommandRequest(commands = listOf(typeText("a".repeat(Endpoints.MAX_TEXT_LENGTH + 1))))))
    }

    @Test
    fun inputCommandRequest_unsupportedProtocolVersion_isRejected() {
        assertEquals(
            listOf("protocolVersion"),
            paths(Validations.inputCommandRequest, InputCommandRequest(protocolVersion = 2, commands = listOf(cursorLeft()))),
        )
    }

    // ── Responses ───────────────────────────────────────────────────────────────────────

    @Test
    fun pairResponse_shortSecret_isRejected() {
        val response = PairResponse(deviceId = "a".repeat(16), deviceSecret = "too-short", serverName = "PC")

        assertEquals(listOf("deviceSecret"), paths(Validations.pairResponse, response))
    }

    @Test
    fun syncResponse_negativeAcceptedCount_isRejected() {
        assertEquals(listOf("accepted"), paths(Validations.syncResponse, SyncResponse(accepted = -1)))
    }

    @Test
    fun healthResponse_unsupportedProtocolVersion_isRejected() {
        val response = HealthResponse(protocolVersion = 2, serverName = "PC", appVersion = "1.0.0", canInsert = true)

        assertEquals(listOf("protocolVersion"), paths(Validations.healthResponse, response))
    }

    @Test
    fun errorEnvelope_isNeverRejected_evenOnAnUnsupportedVersion() {
        // The complaint must survive the very mismatch it is complaining about.
        val envelope = ErrorEnvelope(protocolVersion = 99, code = ErrorCode.PROTOCOL_VERSION_UNSUPPORTED, message = "nope")

        assertTrue(Validations.errorEnvelope(envelope).isValid)
    }

    // ── Catalog (peer-katalog.md §3.3, AC2: every catalog DTO has a Validation + a violation case) ──

    private val validHash = "a".repeat(Endpoints.HASH_LENGTH)

    private fun catalogEntry(
        id: String = "prompt-1",
        hash: String = validHash,
        label: String = "My prompt",
    ) = CatalogEntry(id = id, kind = CatalogEntityKindWire.PROMPT, contentHash = hash, updatedAt = 1L, label = label)

    @Test
    fun catalogIndexResponse_valid_isAccepted() {
        assertValid(Validations.catalogIndexResponse, CatalogIndexResponse(rootHash = validHash, entries = listOf(catalogEntry())))
    }

    @Test
    fun catalogIndexResponse_nonHexRootHash_isRejected() {
        assertEquals(
            listOf("rootHash"),
            paths(Validations.catalogIndexResponse, CatalogIndexResponse(rootHash = "Z".repeat(Endpoints.HASH_LENGTH), entries = emptyList())),
        )
    }

    @Test
    fun catalogIndexResponse_entryWithBadContentHash_isRejected() {
        // Too short → not 64 hex chars. The path pinpoints the offending row.
        assertEquals(
            listOf("entries[0].contentHash"),
            paths(Validations.catalogIndexResponse, CatalogIndexResponse(rootHash = validHash, entries = listOf(catalogEntry(hash = "abc")))),
        )
    }

    @Test
    fun catalogIndexResponse_tooManyEntries_isRejected() {
        val entries = (0..Endpoints.MAX_CATALOG_ENTRIES).map { catalogEntry(id = "prompt-$it") }
        assertEquals(listOf("entries"), paths(Validations.catalogIndexResponse, CatalogIndexResponse(rootHash = validHash, entries = entries)))
    }

    @Test
    fun catalogEntityResponse_valid_isAccepted() {
        assertValid(
            Validations.catalogEntityResponse,
            CatalogEntityResponse(id = "prompt-1", kind = CatalogEntityKindWire.PROMPT, contentHash = validHash, payload = "{\"name\":\"x\"}"),
        )
    }

    @Test
    fun catalogEntityResponse_emptyPayload_isRejected() {
        assertEquals(
            listOf("payload"),
            paths(Validations.catalogEntityResponse, CatalogEntityResponse(id = "prompt-1", kind = CatalogEntityKindWire.PROMPT, contentHash = validHash, payload = "")),
        )
    }

    @Test
    fun catalogEntityResponse_payloadTooLong_isRejected() {
        val payload = "a".repeat(Endpoints.MAX_ENTITY_PAYLOAD_LENGTH + 1)
        assertEquals(
            listOf("payload"),
            paths(Validations.catalogEntityResponse, CatalogEntityResponse(id = "prompt-1", kind = CatalogEntityKindWire.PROMPT, contentHash = validHash, payload = payload)),
        )
    }

    @Test
    fun catalogCredentialResponse_valid_isAccepted() {
        assertValid(
            Validations.catalogCredentialResponse,
            CatalogCredentialResponse(id = "cred-1", provider = "OPENAI", label = "My key", secret = "sk-123"),
        )
    }

    @Test
    fun catalogCredentialResponse_emptySecret_isRejected() {
        // A blank secret is meaningless; the constraint carries no {value}, so the key can never leak.
        assertEquals(
            listOf("secret"),
            paths(Validations.catalogCredentialResponse, CatalogCredentialResponse(id = "cred-1", provider = "OPENAI", label = "My key", secret = "")),
        )
    }

    @Test
    fun isCatalogEntityId_acceptsUuidAndRejectsWhitespace() {
        assertTrue(Validations.isCatalogEntityId("11111111-2222-3333-4444-555555555555"))
        assertTrue(!Validations.isCatalogEntityId("has space"))
        assertTrue(!Validations.isCatalogEntityId(""))
        assertTrue(!Validations.isCatalogEntityId("a".repeat(Endpoints.MAX_ENTITY_ID_LENGTH + 1)))
    }
}
