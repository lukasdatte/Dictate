package net.devemperor.dictate.core

import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.database.entity.PromptType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [PcTextPills] (pc-dictation-activity F7) — the text-pill row's filter + mapping.
 */
class PcTextPillsTest {

    private fun pill(id: Int, type: PromptType, name: String? = null, prompt: String? = null) =
        PromptEntity(id = id, pos = id, name = name, prompt = prompt, type = type.name)

    @Test
    fun `filter keeps only TEXT pills`() {
        val prompts = listOf(
            pill(1, PromptType.TEXT, name = "Signature", prompt = "Best, Lukas"),
            pill(2, PromptType.PROMPT, name = "Formal", prompt = "Rewrite formally"),
            pill(3, PromptType.TEXT, name = "Email", prompt = "me@example.com"),
        )
        val result = PcTextPills.filter(prompts)
        assertEquals(listOf(1, 3), result.map { it.id })
    }

    @Test
    fun `filter drops everything when there are no TEXT pills`() {
        val prompts = listOf(pill(1, PromptType.PROMPT), pill(2, PromptType.PROMPT))
        assertEquals(emptyList<PromptEntity>(), PcTextPills.filter(prompts))
    }

    @Test
    fun `toRequest types the pill content as a STATIC_PROMPT (maps to TYPE_TEXT on the PC)`() {
        val req = PcTextPills.toRequest(pill(1, PromptType.TEXT, name = "Sig", prompt = "Best, Lukas"))
        assertEquals("Best, Lukas", req.text)
        assertEquals(InsertionSource.STATIC_PROMPT, req.source)
    }

    @Test
    fun `toRequest degrades a null content to empty text`() {
        assertEquals("", PcTextPills.toRequest(pill(1, PromptType.TEXT, prompt = null)).text)
    }
}
