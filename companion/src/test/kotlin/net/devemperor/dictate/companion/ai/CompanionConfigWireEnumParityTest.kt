package net.devemperor.dictate.companion.ai

import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.prompt.PromptMode
import net.devemperor.dictate.companion.ai.CompanionConfigWireMapping.toAIProvider
import net.devemperor.dictate.companion.ai.CompanionConfigWireMapping.toAmbiguityMode
import net.devemperor.dictate.companion.ai.CompanionConfigWireMapping.toPromptMode
import net.devemperor.dictate.preferences.AmbiguityMode
import net.devemperor.dictate.shared.config.AmbiguityModeValue
import net.devemperor.dictate.shared.config.PromptSelectionMode
import net.devemperor.dictate.shared.config.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Value-equality parity between the `:shared` **wire** enums and the `:shared-ai` **domain** enums for
 * the conversions [CompanionConfigWireMapping] performs (D5.a) — the companion mirror of the app's
 * `ConfigWireEnumParityTest`. [ProfileBackedAiConfig] and [net.devemperor.dictate.companion.pipeline.ConfigProfileSource]
 * rely on these being total and name-lossless; if a value is added on one side only, a name-set
 * assertion fails here rather than a resolved profile silently degrading.
 */
class CompanionConfigWireEnumParityTest {

    @Test
    fun providerTypeNames_matchAIProviderNames() {
        assertEquals(
            AIProvider.entries.map { it.name }.toSet(),
            ProviderType.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun everyProviderType_mapsToTheSameNamedAIProvider() {
        for (pt in ProviderType.entries) assertEquals(pt.name, pt.toAIProvider().name)
    }

    @Test
    fun ambiguityModeValueNames_matchAmbiguityModePersistKeys() {
        assertEquals(
            AmbiguityMode.entries.map { it.persistKey }.toSet(),
            AmbiguityModeValue.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun everyAmbiguityModeValue_mapsToTheSameNamedAmbiguityMode() {
        for (v in AmbiguityModeValue.entries) assertEquals(v.name, v.toAmbiguityMode().persistKey)
    }

    @Test
    fun promptSelectionModeNames_matchPromptModeNames() {
        assertEquals(
            PromptMode.entries.map { it.name }.toSet(),
            PromptSelectionMode.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun everyPromptSelectionMode_mapsToTheSameNamedPromptMode() {
        for (v in PromptSelectionMode.entries) assertEquals(v.name, v.toPromptMode().name)
    }
}
