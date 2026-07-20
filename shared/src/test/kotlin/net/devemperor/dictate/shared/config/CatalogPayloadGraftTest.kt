package net.devemperor.dictate.shared.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The subscriber-side payload graft (peer-katalog.md §6.2): a pulled canonical payload adopts a local
 * copy's envelope, and the reconstructed copy carries the SOURCE's content hash even with a different
 * local id/provenance — the property the whole hash-based sync rests on.
 */
class CatalogPayloadGraftTest {

    private fun hash(e: PromptV3Entity) = contentHash(e, PromptV3Entity.serializer())
    private fun hash(e: ProviderConfigEntity) = contentHash(e, ProviderConfigEntity.serializer())

    @Test
    fun graft_replacesPayload_andKeepsLocalEnvelope() {
        val source = PromptV3Entity(
            id = "source-id",
            visibility = Visibility.SHARED,
            name = "Formal rewrite",
            text = "Rewrite this formally.",
            requiresSelection = true,
        )
        val payload = CanonicalJson.canonicalString(source, PromptV3Entity.serializer())

        val localCopy = PromptV3Entity(
            id = "local-id",
            visibility = Visibility.PRIVATE,
            sourceRef = SourceRef("peer-A", "source-id", "oldhash"),
            subscriptionMode = SubscriptionMode.SUBSCRIBE,
            name = "stale name",
            text = "stale text",
        )

        val grafted = CatalogPayloadGraft.graft(localCopy, PromptV3Entity.serializer(), payload)

        // Payload adopted from the source …
        assertEquals("Formal rewrite", grafted.name)
        assertEquals("Rewrite this formally.", grafted.text)
        assertEquals(true, grafted.requiresSelection)
        // … envelope kept from the local copy.
        assertEquals("local-id", grafted.id)
        assertEquals(Visibility.PRIVATE, grafted.visibility)
        assertEquals(SubscriptionMode.SUBSCRIBE, grafted.subscriptionMode)
        assertEquals(SourceRef("peer-A", "source-id", "oldhash"), grafted.sourceRef)
    }

    @Test
    fun graftedCopy_hashesIdenticallyToSource_despiteDifferentEnvelope() {
        val source = ProviderConfigEntity(
            id = "source-id",
            visibility = Visibility.SHARED,
            providerType = ProviderType.OPENAI,
            label = "Shared OpenAI",
            baseUrl = "https://api.example.com",
            credentialRef = "cred-1",
        )
        val payload = CanonicalJson.canonicalString(source, ProviderConfigEntity.serializer())
        val local = ProviderConfigEntity(
            id = "local-id",
            providerType = ProviderType.GROQ,
            label = "stale",
        )

        val grafted = CatalogPayloadGraft.graft(local, ProviderConfigEntity.serializer(), payload)

        // The envelope-stripped hash is byte-for-byte the source's — different id, same content hash.
        assertNotEquals(source.id, grafted.id)
        assertEquals(hash(source), hash(grafted))
    }

    @Test
    fun graft_ignoresEnvelopeKeysSmuggledIntoThePayload() {
        val local = PromptV3Entity(id = "local-id", name = "keep", text = "keep")
        // A payload that tries to rewrite the local id + provenance through envelope keys.
        val hostile = """{"id":"attacker","sourceRef":{"peerId":"evil","originalId":"x","originalContentHash":"y"},"name":"new","text":"new"}"""

        val grafted = CatalogPayloadGraft.graft(local, PromptV3Entity.serializer(), hostile)

        assertEquals("local-id", grafted.id)
        assertEquals(null, grafted.sourceRef)
        assertEquals("new", grafted.name)
    }
}
