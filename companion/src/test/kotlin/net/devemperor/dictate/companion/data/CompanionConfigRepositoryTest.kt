package net.devemperor.dictate.companion.data

import net.devemperor.dictate.shared.config.AmbiguityModeValue
import net.devemperor.dictate.shared.config.ModelFunction
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.ProfilePromptRef
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.ProviderType
import net.devemperor.dictate.shared.config.contentHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The config-entity repository round-trips (desktop-host.md §9): row ⇄ C1 DTO, hash recompute on write
 * (§5.3), provenance null for local entities, and the ordered-prompt transaction.
 */
class CompanionConfigRepositoryTest {

    private val database = CompanionDatabase.inMemory()
    private var clock = 1000L
    private val repo = CompanionConfigRepository(database, now = { clock })

    @Test
    fun provider_roundTrips_andRecomputesContentHashOnWrite() {
        val saved = repo.save(ProviderConfigEntity(id = "p1", providerType = ProviderType.OPENAI, label = "My OpenAI"))
        // The stored hash is the canonical hash of the payload — NOT whatever was passed in.
        assertEquals(contentHash(saved.copy(contentHash = ""), ProviderConfigEntity.serializer()), saved.contentHash)
        assertEquals(1000L, saved.updatedAt)

        val loaded = repo.providerConfig("p1")!!
        assertEquals("My OpenAI", loaded.label)
        assertEquals(ProviderType.OPENAI, loaded.providerType)
        assertEquals(saved.contentHash, loaded.contentHash)
        assertNull("a locally created entity has no provenance", loaded.sourceRef)
    }

    @Test
    fun editingAPayloadField_changesTheContentHash() {
        val first = repo.save(ProviderConfigEntity(id = "p1", providerType = ProviderType.OPENAI, label = "A"))
        clock = 2000L
        val second = repo.save(first.copy(label = "B"))
        assertNotEquals("a payload change must move the hash", first.contentHash, second.contentHash)
        assertEquals(2000L, repo.providerConfig("p1")!!.updatedAt)
    }

    @Test
    fun model_roundTripsWithParameterDefaults() {
        val saved = repo.save(
            ModelRefEntity(
                id = "m1", providerRef = "p1", modelId = "gpt-4o-mini", function = ModelFunction.COMPLETION,
                parameterDefaults = mapOf("temperature" to "0.7", "max_tokens" to "4096"),
            )
        )
        val loaded = repo.modelRef("m1")!!
        assertEquals("gpt-4o-mini", loaded.modelId)
        assertEquals(mapOf("temperature" to "0.7", "max_tokens" to "4096"), loaded.parameterDefaults)
        assertEquals(saved.contentHash, loaded.contentHash)
    }

    @Test
    fun prompt_roundTrips() {
        repo.save(PromptV3Entity(id = "pr1", name = "Formal", text = "Rewrite formally", requiresSelection = true))
        val loaded = repo.prompt("pr1")!!
        assertEquals("Formal", loaded.name)
        assertEquals("Rewrite formally", loaded.text)
        assertTrue(loaded.requiresSelection)
    }

    @Test
    fun profile_persistsOrderedPromptsAndRoundTrips() {
        repo.save(
            ProfileEntity(
                id = "prof1", name = "German", ambiguityMode = AmbiguityModeValue.AUTO,
                completionModelRef = "m1",
                orderedPrompts = listOf(
                    ProfilePromptRef(promptRef = "pr1", autoApply = true),
                    ProfilePromptRef(promptRef = "pr2", autoApply = false),
                ),
            )
        )
        val loaded = repo.profile("prof1")!!
        assertEquals("German", loaded.name)
        assertEquals(AmbiguityModeValue.AUTO, loaded.ambiguityMode)
        assertEquals("order is preserved", listOf("pr1", "pr2"), loaded.orderedPrompts.map { it.promptRef })
        assertEquals(listOf(true, false), loaded.orderedPrompts.map { it.autoApply })
    }

    @Test
    fun resavingAProfile_replacesItsPromptList_ratherThanAppending() {
        repo.save(ProfileEntity(id = "prof1", name = "P", orderedPrompts = listOf(ProfilePromptRef("a"), ProfilePromptRef("b"))))
        repo.save(repo.profile("prof1")!!.copy(orderedPrompts = listOf(ProfilePromptRef("c"))))
        assertEquals(listOf("c"), repo.profile("prof1")!!.orderedPrompts.map { it.promptRef })
    }

    @Test
    fun delete_removesTheEntity() {
        repo.save(ProviderConfigEntity(id = "p1", providerType = ProviderType.GROQ, label = "x"))
        repo.deleteProviderConfig("p1")
        assertNull(repo.providerConfig("p1"))
        assertTrue(repo.providerConfigs().isEmpty())
    }
}
