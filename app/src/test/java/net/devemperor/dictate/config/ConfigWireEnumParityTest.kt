package net.devemperor.dictate.config

import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.prompt.PromptMode
import net.devemperor.dictate.config.ConfigWireMapping.toAIFunction
import net.devemperor.dictate.config.ConfigWireMapping.toAIProvider
import net.devemperor.dictate.config.ConfigWireMapping.toAmbiguityMode
import net.devemperor.dictate.config.ConfigWireMapping.toPromptMode
import net.devemperor.dictate.config.ConfigWireMapping.toWire
import net.devemperor.dictate.preferences.AmbiguityMode
import net.devemperor.dictate.shared.config.AmbiguityModeValue
import net.devemperor.dictate.shared.config.ModelFunction
import net.devemperor.dictate.shared.config.ProviderType
import net.devemperor.dictate.shared.config.PromptSelectionMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Value-equality parity between the `:shared-ai` **domain** enums and the `:shared` **wire** mirror
 * enums (spec §4.8, §13 D6). `:app` is the only module that sees both; [ConfigWireMapping] converts
 * by name, and the config-entity migration + resolver rely on that conversion being total and
 * lossless. If a value is added on one side only, the round-trip assertions below fail — the drift
 * cannot silently break a `contentHash`.
 */
class ConfigWireEnumParityTest {

    @Test
    fun `AIProvider names match ProviderType names`() {
        assertEquals(
            AIProvider.entries.map { it.name }.toSet(),
            ProviderType.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `AIProvider round-trips through ProviderType`() {
        for (p in AIProvider.entries) {
            assertEquals(p, p.toWire().toAIProvider())
        }
    }

    @Test
    fun `AmbiguityMode persistKeys match AmbiguityModeValue names`() {
        assertEquals(
            AmbiguityMode.entries.map { it.persistKey }.toSet(),
            AmbiguityModeValue.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `AmbiguityMode round-trips through AmbiguityModeValue`() {
        for (m in AmbiguityMode.entries) {
            assertEquals(m, m.toWire().toAmbiguityMode())
        }
    }

    @Test
    fun `PromptMode names match PromptSelectionMode names`() {
        assertEquals(
            PromptMode.entries.map { it.name }.toSet(),
            PromptSelectionMode.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `PromptMode round-trips through PromptSelectionMode`() {
        for (m in PromptMode.entries) {
            assertEquals(m, m.toWire().toPromptMode())
        }
    }

    @Test
    fun `AIFunction names match ModelFunction names`() {
        assertEquals(
            AIFunction.entries.map { it.name }.toSet(),
            ModelFunction.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `AIFunction round-trips through ModelFunction`() {
        for (f in AIFunction.entries) {
            assertEquals(f, f.toWire().toAIFunction())
        }
    }
}
