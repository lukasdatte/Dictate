package net.devemperor.dictate.shared.config

import net.devemperor.dictate.shared.protocol.DecodeResult
import net.devemperor.dictate.shared.protocol.ProtocolViolationException
import net.devemperor.dictate.shared.protocol.ValidationDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v3-format tests for [CatalogCodec] — the single v3 door (spec §12, AK3).
 *
 * Guards: a v3 catalog survives a round-trip byte-stable, an additive field from a newer peer is
 * tolerated, and a broken file ([DecodeResult.Malformed]) is told apart from a contract violation
 * ([DecodeResult.Invalid]) exactly as `ProtocolCodec` does for the wire (ADR-0016).
 */
class CatalogCodecTest {

    private fun fullCatalog() = CatalogFileV3(
        entities = listOf(
            CatalogEntry.Provider(ProviderConfigEntity(id = "prov-1", providerType = ProviderType.OPENAI, label = "OpenAI", credentialRef = "cred-1")),
            CatalogEntry.Credential(ApiCredentialEntity(id = "cred-1", providerType = ProviderType.OPENAI, label = "Key", keyFingerprint = "0123456789abcdef")),
            CatalogEntry.Model(ModelRefEntity(id = "mod-1", providerRef = "prov-1", modelId = "gpt-4o-mini", function = ModelFunction.COMPLETION, parameterDefaults = mapOf("temperature" to "0.7"))),
            CatalogEntry.Prompt(PromptV3Entity(id = "pr-1", name = "Fix grammar", text = "Please fix.")),
            CatalogEntry.Profile(ProfileEntity(id = "prof-1", name = "Work", completionModelRef = "mod-1", orderedPrompts = listOf(ProfilePromptRef("pr-1", autoApply = true)))),
        ),
    )

    private fun <T> decodeOk(result: DecodeResult<T>): T {
        assertTrue("expected Ok, was $result", result is DecodeResult.Ok)
        return (result as DecodeResult.Ok).value
    }

    private fun <T> decodeInvalid(result: DecodeResult<T>): List<ValidationDetail> {
        assertTrue("expected Invalid, was $result", result is DecodeResult.Invalid)
        return (result as DecodeResult.Invalid).details
    }

    // -- Round-trip --

    @Test
    fun roundTrip_fullCatalog_isByteStable() {
        val raw = CatalogCodec.encode(fullCatalog())

        val decoded = decodeOk(CatalogCodec.decode(raw))
        assertEquals(fullCatalog(), decoded)
        // Re-encoding the decoded file yields identical bytes (canonical => idempotent).
        assertEquals(raw, CatalogCodec.encode(decoded))
    }

    @Test
    fun encode_usesKindDiscriminator() {
        val raw = CatalogCodec.encode(fullCatalog())

        assertTrue(raw, raw.contains(""""kind":"provider""""))
        assertTrue(raw, raw.contains(""""kind":"credential""""))
        assertTrue(raw, raw.contains(""""kind":"profile""""))
    }

    @Test
    fun encode_neverEmitsAKeyValue_onlyFingerprint() {
        val raw = CatalogCodec.encode(fullCatalog())

        assertTrue(raw, raw.contains("keyFingerprint"))
        assertFalse(raw.contains("apiKey"))
        assertFalse(raw.contains("secret"))
    }

    // -- Additive tolerance --

    @Test
    fun decode_unknownAdditiveField_isTolerated() {
        val raw = """
            {"version":3,"entities":[
              {"kind":"prompt","entity":{"id":"pr-1","name":"n","text":"t","futureField":"x"}}
            ]}
        """.trimIndent()

        val file = decodeOk(CatalogCodec.decode(raw))
        assertEquals("pr-1", (file.entities.single() as CatalogEntry.Prompt).entity.id)
    }

    // -- Malformed (broken file) --

    @Test
    fun decode_notJson_isMalformed() {
        assertTrue(CatalogCodec.decode("<html>oops</html>") is DecodeResult.Malformed)
    }

    @Test
    fun decode_unknownKindDiscriminator_isMalformed() {
        val raw = """{"version":3,"entities":[{"kind":"gateway_entry","entity":{"id":"x"}}]}"""

        assertTrue(CatalogCodec.decode(raw).toString(), CatalogCodec.decode(raw) is DecodeResult.Malformed)
    }

    @Test
    fun decode_missingRequiredField_isMalformed() {
        // A prompt entity without the required `text`.
        val raw = """{"version":3,"entities":[{"kind":"prompt","entity":{"id":"pr-1","name":"n"}}]}"""

        assertTrue(CatalogCodec.decode(raw).toString(), CatalogCodec.decode(raw) is DecodeResult.Malformed)
    }

    @Test
    fun decode_unknownEnumValue_isMalformed() {
        val raw = """{"version":3,"entities":[{"kind":"provider","entity":{"id":"p","providerType":"TELEPATHY","label":"L"}}]}"""

        assertTrue(CatalogCodec.decode(raw).toString(), CatalogCodec.decode(raw) is DecodeResult.Malformed)
    }

    // -- Invalid (contract violation) --

    @Test
    fun decode_emptyLabel_isInvalid_withIndexedPath() {
        val raw = """{"version":3,"entities":[{"kind":"provider","entity":{"id":"p","providerType":"OPENAI","label":""}}]}"""

        val details = decodeInvalid(CatalogCodec.decode(raw))
        assertEquals(listOf("entities[0].label"), details.map { it.path })
    }

    @Test
    fun decode_gatewayProvider_isInvalid() {
        val raw = """{"version":3,"entities":[{"kind":"provider","entity":{"id":"p","providerType":"OPENAI","label":"L","kind":"GATEWAY"}}]}"""

        val details = decodeInvalid(CatalogCodec.decode(raw))
        assertTrue(details.toString(), details.any { it.path == "entities[0]" && it.message.contains("GATEWAY") })
    }

    @Test
    fun decode_violationInSecondEntity_carriesTheIndex() {
        val raw = """
            {"version":3,"entities":[
              {"kind":"prompt","entity":{"id":"pr-1","name":"ok","text":"t"}},
              {"kind":"model","entity":{"id":"m","providerRef":"p","modelId":"","function":"COMPLETION"}}
            ]}
        """.trimIndent()

        val details = decodeInvalid(CatalogCodec.decode(raw))
        assertEquals(listOf("entities[1].modelId"), details.map { it.path })
    }

    // -- Send-side validation --

    @Test
    fun encode_violatingEntity_throwsWithDetails() {
        val broken = CatalogFileV3(entities = listOf(
            CatalogEntry.Model(ModelRefEntity(id = "m", providerRef = "p", modelId = "", function = ModelFunction.TRANSCRIPTION)),
        ))

        try {
            CatalogCodec.encode(broken)
            fail("expected ProtocolViolationException")
        } catch (e: ProtocolViolationException) {
            assertEquals(listOf("entities[0].modelId"), e.details.map { it.path })
        }
    }
}
