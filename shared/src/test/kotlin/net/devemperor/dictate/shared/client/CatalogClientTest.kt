package net.devemperor.dictate.shared.client

import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.fakes.FakeTransport
import net.devemperor.dictate.shared.protocol.CatalogCredentialResponse
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import net.devemperor.dictate.shared.protocol.CatalogEntityResponse
import net.devemperor.dictate.shared.protocol.CatalogEntry
import net.devemperor.dictate.shared.protocol.CatalogIndexResponse
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.ErrorCode
import net.devemperor.dictate.shared.protocol.ErrorEnvelope
import net.devemperor.dictate.shared.protocol.ProtocolCodec
import net.devemperor.dictate.shared.protocol.Validations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Branch tests for [CatalogClient]'s classification — the 404 fork especially (AC1).
 *
 * The whole point of the family being additive is that an old peer (no catalog route → bare 404) is
 * told apart from a catalog-aware peer that reports a deleted/unshared entity
 * (`CATALOG_ENTITY_NOT_FOUND` envelope → [DispatchError.EntityGone]). Both are 404s; only the envelope
 * distinguishes them, and this pins that.
 */
class CatalogClientTest {

    private val credentials = Credentials(deviceId = "device-1", deviceSecret = "secret-long-enough")
    private val validHash = "a".repeat(Endpoints.HASH_LENGTH)

    private fun client(transport: FakeTransport, credentials: Credentials? = this.credentials) =
        CatalogClient(transport) { credentials }

    private fun <T> failure(result: DispatchResult<T>): DispatchError {
        assertTrue("expected Failure, was $result", result is DispatchResult.Failure)
        return (result as DispatchResult.Failure).error
    }

    private fun errorBody(code: ErrorCode) = ProtocolCodec.encode(
        ErrorEnvelope(code = code, message = code.name),
        ErrorEnvelope.serializer(),
        Validations.errorEnvelope,
    )

    // ── Success ─────────────────────────────────────────────────────────────────────────

    @Test
    fun index_200_decodesTheOffer() {
        val body = ProtocolCodec.encode(
            CatalogIndexResponse(rootHash = validHash, entries = listOf(CatalogEntry("p-1", CatalogEntityKindWire.PROMPT, validHash, 1L, "Prompt"))),
            CatalogIndexResponse.serializer(),
            Validations.catalogIndexResponse,
        )
        val transport = FakeTransport().respond(Endpoints.CATALOG, 200, body)

        val result = client(transport).index()

        assertTrue(result is DispatchResult.Success)
        assertEquals(1, (result as DispatchResult.Success).value.entries.size)
    }

    @Test
    fun entity_200_decodesThePayload() {
        val body = ProtocolCodec.encode(
            CatalogEntityResponse(id = "p-1", kind = CatalogEntityKindWire.PROMPT, contentHash = validHash, payload = "{\"name\":\"x\"}"),
            CatalogEntityResponse.serializer(),
            Validations.catalogEntityResponse,
        )
        val transport = FakeTransport().respond("${Endpoints.CATALOG_ENTITY}/p-1", 200, body)

        val result = client(transport).entity("p-1")

        assertEquals("{\"name\":\"x\"}", (result as DispatchResult.Success).value.payload)
    }

    @Test
    fun credential_200_decodesTheSecret() {
        val body = ProtocolCodec.encode(
            CatalogCredentialResponse(id = "c-1", provider = "OPENAI", label = "Key", secret = "sk-123"),
            CatalogCredentialResponse.serializer(),
            Validations.catalogCredentialResponse,
        )
        val transport = FakeTransport().respond("${Endpoints.CATALOG_CREDENTIAL}/c-1", 200, body)

        val result = client(transport).credential("c-1")

        assertEquals("sk-123", (result as DispatchResult.Success).value.secret)
    }

    // ── The 404 fork (AC1) ────────────────────────────────────────────────────────────────

    @Test
    fun index_bare404_isEndpointMissing_notEntityGone() {
        // An old peer without a catalog route answers a bare Ktor 404 (no ErrorEnvelope body).
        val transport = FakeTransport().respond(Endpoints.CATALOG, 404, "Not Found")

        assertEquals(DispatchError.EndpointMissing, failure(client(transport).index()))
    }

    @Test
    fun entity_404WithCatalogEnvelope_isEntityGone() {
        val transport = FakeTransport().respond("${Endpoints.CATALOG_ENTITY}/p-1", 404, errorBody(ErrorCode.CATALOG_ENTITY_NOT_FOUND))

        assertEquals(DispatchError.EntityGone, failure(client(transport).entity("p-1")))
    }

    @Test
    fun credential_404WithCatalogEnvelope_isEntityGone() {
        val transport = FakeTransport().respond("${Endpoints.CATALOG_CREDENTIAL}/c-1", 404, errorBody(ErrorCode.CATALOG_ENTITY_NOT_FOUND))

        assertEquals(DispatchError.EntityGone, failure(client(transport).credential("c-1")))
    }

    // ── The rest of the classification ─────────────────────────────────────────────────────

    @Test
    fun index_401_isUnauthorized() {
        val transport = FakeTransport().respond(Endpoints.CATALOG, 401, errorBody(ErrorCode.UNAUTHORIZED))

        assertEquals(DispatchError.Unauthorized, failure(client(transport).index()))
    }

    @Test
    fun index_withoutCredentials_isUnauthorized_withoutTouchingTheWire() {
        val transport = FakeTransport() // no answer queued: a call would blow up
        assertEquals(DispatchError.Unauthorized, failure(client(transport, credentials = null).index()))
        assertTrue("no request should have gone on the wire", transport.calls.isEmpty())
    }

    @Test
    fun index_ioFailure_isUnreachable() {
        val transport = FakeTransport().fail(Endpoints.CATALOG, IOException("boom"))

        assertTrue(failure(client(transport).index()) is DispatchError.Unreachable)
    }
}
