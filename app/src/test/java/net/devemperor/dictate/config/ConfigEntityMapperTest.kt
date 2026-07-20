package net.devemperor.dictate.config

import net.devemperor.dictate.shared.config.AmbiguityModeValue
import net.devemperor.dictate.shared.config.ModelFunction
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.ProfilePromptRef
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.ProviderType
import net.devemperor.dictate.shared.config.SourceRef
import net.devemperor.dictate.shared.config.Visibility
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trip fidelity of [ConfigEntityMapper] (spec §7.1): DTO → Room → DTO preserves every field,
 * including the flattened [SourceRef] provenance and the ordered profile prompts, and the
 * parameter-map JSON is deterministic (key-sorted).
 */
class ConfigEntityMapperTest {

    @Test
    fun `provider config round-trips including source ref`() {
        val dto = ProviderConfigEntity(
            id = "p1",
            contentHash = "h",
            updatedAt = 42,
            visibility = Visibility.SHARED,
            sourceRef = SourceRef("peer", "orig", "ohash"),
            providerType = ProviderType.CUSTOM,
            label = "My Custom",
            baseUrl = "https://x/v1/",
            credentialRef = "c1",
        )
        assertEquals(dto, ConfigEntityMapper.toDto(ConfigEntityMapper.toRoom(dto)))
    }

    @Test
    fun `model ref round-trips including parameter defaults`() {
        val dto = ModelRefEntity(
            id = "m1",
            contentHash = "h",
            updatedAt = 7,
            providerRef = "p1",
            modelId = "gpt-4o-mini",
            function = ModelFunction.COMPLETION,
            parameterDefaults = mapOf("temperature" to "0.7", "max_tokens" to "4096"),
        )
        assertEquals(dto, ConfigEntityMapper.toDto(ConfigEntityMapper.toRoom(dto)))
    }

    @Test
    fun `profile round-trips including ordered prompts`() {
        val dto = ProfileEntity(
            id = "pr1",
            contentHash = "h",
            updatedAt = 3,
            name = "Default",
            transcriptionModelRef = "mt",
            completionModelRef = "mc",
            orderedPrompts = listOf(ProfilePromptRef("u1", true), ProfilePromptRef("u2", false)),
            ambiguityMode = AmbiguityModeValue.AUTO,
            parameterOverrides = mapOf("temperature" to "0.2"),
        )
        val prompts = ConfigEntityMapper.profilePromptRows(dto)
        assertEquals(dto, ConfigEntityMapper.toDto(ConfigEntityMapper.toRoom(dto), prompts))
    }

    @Test
    fun `parameter map encoding is key-sorted and deterministic`() {
        val a = ConfigEntityMapper.encodeParams(mapOf("max_tokens" to "4096", "temperature" to "0.7"))
        val b = ConfigEntityMapper.encodeParams(mapOf("temperature" to "0.7", "max_tokens" to "4096"))
        assertEquals(a, b)
        assertEquals("""{"max_tokens":"4096","temperature":"0.7"}""", a)
    }
}
