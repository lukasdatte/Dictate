package net.devemperor.dictate.ai.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for the DictateUtils.getPunctuationPromptForLanguage →
 * [PromptTemplates.getPunctuationPromptForLanguage] logic move (A3.5). Pins the
 * fallback behaviour: null/empty/"detect"/unknown → English, region subtag →
 * base language. (Byte-parity of all 57 language strings vs the former Java
 * table was verified during the move.)
 */
class PromptTemplatesPunctuationTest {

    private val english = PromptTemplates.PUNCTUATION_CAPITALIZATION

    @Test
    fun `null empty and detect fall back to english`() {
        assertEquals(english, PromptTemplates.getPunctuationPromptForLanguage(null))
        assertEquals(english, PromptTemplates.getPunctuationPromptForLanguage(""))
        assertEquals(english, PromptTemplates.getPunctuationPromptForLanguage("detect"))
    }

    @Test
    fun `unknown language falls back to english`() {
        assertEquals(english, PromptTemplates.getPunctuationPromptForLanguage("xx"))
    }

    @Test
    fun `known language is resolved`() {
        assertEquals(
            "Hallo, wie geht es dir? Mir geht es gut! Ja, es beginnt um 15:00 Uhr.",
            PromptTemplates.getPunctuationPromptForLanguage("de")
        )
    }

    @Test
    fun `language code is case-insensitive`() {
        assertEquals(
            PromptTemplates.getPunctuationPromptForLanguage("de"),
            PromptTemplates.getPunctuationPromptForLanguage("DE")
        )
    }

    @Test
    fun `region subtag falls back to base language`() {
        // "de-AT" is not a table key; the base "de" is.
        assertEquals(
            PromptTemplates.getPunctuationPromptForLanguage("de"),
            PromptTemplates.getPunctuationPromptForLanguage("de-AT")
        )
    }

    @Test
    fun `region-specific key wins over base when present`() {
        // zh-cn and zh-tw are distinct table keys.
        val cn = PromptTemplates.getPunctuationPromptForLanguage("zh-cn")
        val tw = PromptTemplates.getPunctuationPromptForLanguage("zh-tw")
        assertEquals("你好，你好吗？我很好！是的，下午 3:00 开始。", cn)
        assertEquals("你好，你好嗎？我很好！是的，下午 3:00 開始。", tw)
    }
}
