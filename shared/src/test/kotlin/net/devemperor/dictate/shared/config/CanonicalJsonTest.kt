package net.devemperor.dictate.shared.config

import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-form tests for [CanonicalJson] — the determinism the whole hash/sync stack rests on.
 *
 * Pins three properties (spec §12): the canonical bytes are a fixed snapshot per entity type,
 * object keys come out sorted (so two platforms never disagree), and the envelope fields are
 * excluded (so a fork with a different `id`/`visibility` canonicalises identically).
 */
class CanonicalJsonTest {

    // -- Byte snapshots (keys MUST appear sorted, envelope MUST be gone) --

    @Test
    fun providerConfig_snapshot_keysSorted_envelopeStripped() {
        val entity = ProviderConfigEntity(
            id = "prov-1",
            contentHash = "deadbeef",
            updatedAt = 123L,
            visibility = Visibility.SHARED,
            sourceRef = SourceRef("peer-1", "orig-1", "hash-1"),
            providerType = ProviderType.OPENAI,
            kind = ProviderKind.LOCAL,
            label = "My OpenAI",
            baseUrl = null,
            credentialRef = "cred-1",
        )

        // Sorted payload keys: credentialRef, kind, label, providerType. baseUrl (null) is absent.
        // Every envelope field (id/contentHash/updatedAt/visibility/sourceRef/subscriptionMode) gone.
        assertEquals(
            """{"credentialRef":"cred-1","kind":"LOCAL","label":"My OpenAI","providerType":"OPENAI"}""",
            canonical(entity, ProviderConfigEntity.serializer()),
        )
    }

    @Test
    fun modelRef_snapshot_nestedMapKeysSorted() {
        val entity = ModelRefEntity(
            id = "m1",
            providerRef = "prov-1",
            modelId = "gpt-4o-mini",
            function = ModelFunction.COMPLETION,
            // Insertion order temperature->max_tokens; canonical MUST re-sort to max_tokens->temperature.
            parameterDefaults = linkedMapOf("temperature" to "0.7", "max_tokens" to "4096"),
        )

        assertEquals(
            """{"function":"COMPLETION","modelId":"gpt-4o-mini",""" +
                """"parameterDefaults":{"max_tokens":"4096","temperature":"0.7"},"providerRef":"prov-1"}""",
            canonical(entity, ModelRefEntity.serializer()),
        )
    }

    @Test
    fun profile_snapshot_arrayOrderPreserved() {
        val entity = ProfileEntity(
            id = "prof-1",
            name = "Work",
            orderedPrompts = listOf(ProfilePromptRef("p-a"), ProfilePromptRef("p-b", autoApply = true)),
        )

        // Arrays keep their order (significant); the object keys around them are still sorted.
        assertEquals(
            """{"ambiguityMode":"ALWAYS_INSERT","name":"Work",""" +
                """"orderedPrompts":[{"autoApply":false,"promptRef":"p-a"},{"autoApply":true,"promptRef":"p-b"}],""" +
                """"parameterOverrides":{},"stylePromptCustomText":"","stylePromptMode":"PREDEFINED",""" +
                """"systemPromptCustomText":"","systemPromptMode":"PREDEFINED"}""",
            canonical(entity, ProfileEntity.serializer()),
        )
    }

    // -- Unicode & escaping --

    @Test
    fun escaping_isMinimal_nonAsciiStaysLiteral() {
        val entity = PromptV3Entity(
            id = "p1",
            name = "Uber \"Zitat\"\n\t",
            text = "Gruße 😀",   // "Gru", sharp-s, space, grinning-face emoji
        )

        val out = canonical(entity, PromptV3Entity.serializer())

        assertTrue(out, out.contains("\\\"Zitat\\\""))       // quotes escaped
        assertTrue(out, out.contains("\\n\\t"))               // newline/tab as short escapes
        assertFalse(out, out.contains("\n"))                  // no RAW newline emitted
        assertTrue(out, out.contains("Gruße"))           // sharp-s stays literal
        assertTrue(out, out.contains("😀"))         // emoji surrogate pair stays literal
        assertFalse(out, out.contains("\\u00df"))             // sharp-s is NOT \u-escaped
    }

    @Test
    fun escaping_controlChar_belowSpace_isUnicodeEscapedLowercase() {
        // U+0001 has no short escape -> it must become the 6-char sequence  (lowercase, JCS).
        val name = "a" + '' + "b"
        val entity = PromptV3Entity(id = "p1", name = name, text = "t")

        val out = canonical(entity, PromptV3Entity.serializer())

        assertTrue(out, out.contains("a\\u0001b"))            // "\\u0001" = the literal escape seq
        assertFalse(out, out.contains(name))                 // the RAW control char is NOT emitted
    }

    // -- Envelope exclusion --

    @Test
    fun envelopeDiffers_payloadSame_bytesIdentical() {
        val base = ProviderConfigEntity(id = "a", providerType = ProviderType.GROQ, label = "L")
        val fork = base.copy(
            id = "b",
            contentHash = "whatever",
            updatedAt = 999L,
            visibility = Visibility.SHARED,
            sourceRef = SourceRef("peer", "orig", "h"),
            subscriptionMode = SubscriptionMode.SUBSCRIBE,
        )

        assertEquals(
            canonical(base, ProviderConfigEntity.serializer()),
            canonical(fork, ProviderConfigEntity.serializer()),
        )
    }

    @Test
    fun bytes_areUtf8OfTheCanonicalString() {
        val entity = PromptV3Entity(id = "p1", name = "Gruße", text = "t")
        val str = canonical(entity, PromptV3Entity.serializer())

        assertTrue(
            CanonicalJson.canonicalBytes(entity, PromptV3Entity.serializer())
                .contentEquals(str.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun <T> canonical(value: T, serializer: KSerializer<T>): String =
        CanonicalJson.canonicalString(value, serializer)
}
