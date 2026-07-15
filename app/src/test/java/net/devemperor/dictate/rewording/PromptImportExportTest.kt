package net.devemperor.dictate.rewording

import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.database.entity.PromptType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip + legacy-import coverage for [PromptImportExport].
 *
 * Robolectric-hosted because the parser/serialiser use real `org.json` (the
 * plain-JVM `android.jar` stubs would throw). The classification rule itself is
 * pinned separately by `PromptTypeClassifierTest`; this suite pins the JSON
 * version handling around it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptImportExportTest {

    @Test
    fun `export writes version 2 with an explicit type per prompt`() {
        val json = PromptImportExport.buildExport(
            listOf(
                PromptEntity(id = 1, pos = 0, name = "Formalize", prompt = "Make it formal", requiresSelection = true, autoApply = false, type = PromptType.PROMPT.name),
                PromptEntity(id = 2, pos = 1, name = "Greeting", prompt = "Beste Grüße", requiresSelection = false, autoApply = false, type = PromptType.TEXT.name),
            )
        )
        assertEquals(2, json.getInt("version"))
        val arr = json.getJSONArray("prompts")
        assertEquals("PROMPT", arr.getJSONObject(0).getString("type"))
        assertEquals("TEXT", arr.getJSONObject(1).getString("type"))
    }

    @Test
    fun `export then import round-trips the type verbatim`() {
        val original = listOf(
            PromptEntity(id = 1, pos = 0, name = "Formalize", prompt = "Make it formal", requiresSelection = true, autoApply = false, type = PromptType.PROMPT.name),
            PromptEntity(id = 2, pos = 1, name = "Greeting", prompt = "Beste Grüße", requiresSelection = false, autoApply = false, type = PromptType.TEXT.name),
        )
        val parsed = PromptImportExport.parse(PromptImportExport.buildExport(original).toString())

        assertEquals(2, parsed.size)
        assertEquals(PromptType.PROMPT, parsed[0].typeEnum)
        assertEquals("Make it formal", parsed[0].prompt)
        assertEquals(PromptType.TEXT, parsed[1].typeEnum)
        // v2 is explicit — a TEXT prompt is NOT re-stripped, it is already literal.
        assertEquals("Beste Grüße", parsed[1].prompt)
    }

    @Test
    fun `v1 file without a type field classifies bracketed prompts as stripped TEXT`() {
        val v1 = """
            {"version":1,"prompts":[
              {"name":"[Dictate is great]","prompt":"[Dictate is great]","requiresSelection":false,"autoApply":false},
              {"name":"Formalize","prompt":"Make it formal","requiresSelection":true,"autoApply":false}
            ]}
        """.trimIndent()

        val parsed = PromptImportExport.parse(v1)

        assertEquals(2, parsed.size)
        // Bracketed -> TEXT, name + prompt stripped (F2 name strip).
        assertEquals(PromptType.TEXT, parsed[0].typeEnum)
        assertEquals("Dictate is great", parsed[0].prompt)
        assertEquals("Dictate is great", parsed[0].name)
        // Plain instruction stays PROMPT, unchanged.
        assertEquals(PromptType.PROMPT, parsed[1].typeEnum)
        assertEquals("Make it formal", parsed[1].prompt)
    }

    @Test
    fun `v2 file with an unknown type falls back to PROMPT`() {
        val v2 = """{"version":2,"prompts":[{"name":"X","prompt":"do x","type":"BANANA"}]}"""
        val parsed = PromptImportExport.parse(v2)
        assertEquals(PromptType.PROMPT, parsed.single().typeEnum)
    }
}
