package net.devemperor.dictate.shared.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

/**
 * The determinism matrix for [contentHash] (spec §12, AK2):
 * same payload -> same hash; any payload change -> new hash; `orderedPrompts` reorder -> new hash
 * (array order is significant); an envelope-only change -> SAME hash (envelope is excluded).
 */
class ContentHashTest {

    private fun provider() = ProviderConfigEntity(
        id = "prov-1",
        providerType = ProviderType.OPENAI,
        kind = ProviderKind.LOCAL,
        label = "OpenAI",
        baseUrl = "https://api.openai.com",
        credentialRef = "cred-1",
    )

    private fun hash(e: ProviderConfigEntity) = contentHash(e, ProviderConfigEntity.serializer())

    // -- Format & correctness --

    @Test
    fun hash_is64LowercaseHex_andMatchesAnIndependentRenderer() {
        val h = hash(provider())

        assertTrue(h, Regex("^[0-9a-f]{64}$").matches(h))

        // Independent renderer (unsigned BigInteger) — catches a signed-byte sign-extension bug in
        // the "%02x" mask that a length check alone might miss.
        val bytes = CanonicalJson.canonicalBytes(provider(), ProviderConfigEntity.serializer())
        val expected = BigInteger(1, MessageDigest.getInstance("SHA-256").digest(bytes))
            .toString(16).padStart(64, '0')
        assertEquals(expected, h)
    }

    // -- Same payload -> same hash --

    @Test
    fun samePayload_sameHash() {
        assertEquals(hash(provider()), hash(provider()))
    }

    // -- Every payload field change -> new hash --

    @Test
    fun everyPayloadFieldChange_changesHash() {
        val base = hash(provider())
        assertNotEquals(base, hash(provider().copy(providerType = ProviderType.GROQ)))
        assertNotEquals(base, hash(provider().copy(kind = ProviderKind.GATEWAY)))
        assertNotEquals(base, hash(provider().copy(label = "Other")))
        assertNotEquals(base, hash(provider().copy(baseUrl = "https://example.com")))
        assertNotEquals(base, hash(provider().copy(baseUrl = null)))
        assertNotEquals(base, hash(provider().copy(credentialRef = "cred-2")))
        assertNotEquals(base, hash(provider().copy(credentialRef = null)))
    }

    @Test
    fun promptFieldChanges_changeHash() {
        val p = PromptV3Entity(id = "p1", name = "N", text = "T", requiresSelection = false, autoApply = false)
        val base = contentHash(p, PromptV3Entity.serializer())
        assertNotEquals(base, contentHash(p.copy(name = "N2"), PromptV3Entity.serializer()))
        assertNotEquals(base, contentHash(p.copy(text = "T2"), PromptV3Entity.serializer()))
        assertNotEquals(base, contentHash(p.copy(requiresSelection = true), PromptV3Entity.serializer()))
        assertNotEquals(base, contentHash(p.copy(autoApply = true), PromptV3Entity.serializer()))
    }

    @Test
    fun modelParameterDefaultsChange_changesHash() {
        val m = ModelRefEntity(id = "m1", providerRef = "p", modelId = "gpt", function = ModelFunction.COMPLETION)
        val base = contentHash(m, ModelRefEntity.serializer())
        assertNotEquals(
            base,
            contentHash(m.copy(parameterDefaults = mapOf("temperature" to "0.7")), ModelRefEntity.serializer()),
        )
        assertNotEquals(base, contentHash(m.copy(function = ModelFunction.TRANSCRIPTION), ModelRefEntity.serializer()))
    }

    // -- orderedPrompts reorder -> NEW hash (array order significant) --

    @Test
    fun orderedPromptsReorder_changesHash() {
        val a = ProfileEntity(
            id = "prof-1",
            name = "P",
            orderedPrompts = listOf(ProfilePromptRef("x"), ProfilePromptRef("y")),
        )
        val b = a.copy(orderedPrompts = listOf(ProfilePromptRef("y"), ProfilePromptRef("x")))

        assertNotEquals(
            contentHash(a, ProfileEntity.serializer()),
            contentHash(b, ProfileEntity.serializer()),
        )
    }

    // -- Envelope-only change -> SAME hash (envelope excluded) --

    @Test
    fun envelopeOnlyChange_sameHash() {
        val forked = provider().copy(
            id = "prov-2",
            contentHash = "stale-value",
            updatedAt = 42L,
            visibility = Visibility.SHARED,
            sourceRef = SourceRef("peer", "orig", "h"),
            subscriptionMode = SubscriptionMode.ONE_SHOT,
        )

        assertEquals(hash(provider()), hash(forked))
    }

    // -- contentHashOfElement: raw-bytes equivalence + forward-compat (finding logic-C-1) --

    @Test
    fun contentHashOfElement_matchesTypedHash_forSameVersionPayload() {
        // The file entity object carries the envelope; contentHashOfElement must strip it and land on
        // the SAME digest as the typed contentHash — otherwise same-version files stop verifying.
        val element = CanonicalJson.json.encodeToJsonElement(ProviderConfigEntity.serializer(), provider())
        assertEquals(hash(provider()), contentHashOfElement(element))
    }

    @Test
    fun contentHashOfElement_unknownAdditiveKey_changesHash() {
        // A newer writer's superset payload hashes differently (the additive field is folded in) and
        // a tampered value would too — the primitive-level guard behind the file-import forward-compat.
        val element = CanonicalJson.json.encodeToJsonElement(ProviderConfigEntity.serializer(), provider())
        val withFutureField = JsonObject(
            element.jsonObject.toMutableMap().apply { put("futureField", JsonPrimitive("x")) },
        )
        assertNotEquals(contentHashOfElement(element), contentHashOfElement(withFutureField))
    }

    @Test
    fun mapKeyOrderInParameterDefaults_doesNotChangeHash() {
        val a = ModelRefEntity(
            id = "m1", providerRef = "p", modelId = "gpt", function = ModelFunction.COMPLETION,
            parameterDefaults = linkedMapOf("temperature" to "0.7", "max_tokens" to "4096"),
        )
        val b = a.copy(parameterDefaults = linkedMapOf("max_tokens" to "4096", "temperature" to "0.7"))

        assertEquals(
            contentHash(a, ModelRefEntity.serializer()),
            contentHash(b, ModelRefEntity.serializer()),
        )
    }
}
