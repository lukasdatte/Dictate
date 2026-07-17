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

    // --- Regression: TEXT pills must never carry selection / auto-apply flags ---
    // ADR-0024: a TEXT pill inserts its content literally, so requiresSelection /
    // autoApply are meaningless. PromptEditActivity.savePrompt forces both off; the
    // import path is the other write seam and must clamp identically, or a
    // hand-crafted / legacy file persists a TEXT pill with stale flags that then
    // resurface in the editor and in a re-export.

    @Test
    fun `v2 TEXT pill imported with autoApply and requiresSelection true clamps both off`() {
        val v2 = """
            {"version":2,"prompts":[
              {"name":"Signature","prompt":"Beste Grüße","requiresSelection":true,"autoApply":true,"type":"TEXT"}
            ]}
        """.trimIndent()

        val pill = PromptImportExport.parse(v2).single()

        assertEquals(PromptType.TEXT, pill.typeEnum)
        assertEquals(false, pill.requiresSelection)
        assertEquals(false, pill.autoApply)
    }

    @Test
    fun `v1 bracketed pill imported with autoApply true clamps flags off once classified TEXT`() {
        val v1 = """
            {"version":1,"prompts":[
              {"name":"[Sig]","prompt":"[Beste Grüße]","requiresSelection":true,"autoApply":true}
            ]}
        """.trimIndent()

        val pill = PromptImportExport.parse(v1).single()

        assertEquals(PromptType.TEXT, pill.typeEnum)
        assertEquals(false, pill.requiresSelection)
        assertEquals(false, pill.autoApply)
    }

    @Test
    fun `PROMPT pill keeps its selection and auto-apply flags on import`() {
        val v2 = """
            {"version":2,"prompts":[
              {"name":"Formalize","prompt":"Make it formal","requiresSelection":true,"autoApply":true,"type":"PROMPT"}
            ]}
        """.trimIndent()

        val pill = PromptImportExport.parse(v2).single()

        assertEquals(PromptType.PROMPT, pill.typeEnum)
        assertEquals(true, pill.requiresSelection)
        assertEquals(true, pill.autoApply)
    }

    @Test
    fun `round-trip of a TEXT pill that started with a stale autoApply flag settles clean`() {
        // A DB polluted before the import fix (or a hand-edited file) can carry a
        // TEXT pill with autoApply=true. Export is faithful (writes what it sees),
        // but re-import must normalise, so a second round is idempotent and clean.
        val polluted = listOf(
            PromptEntity(id = 1, pos = 0, name = "Sig", prompt = "Beste Grüße", requiresSelection = true, autoApply = true, type = PromptType.TEXT.name),
        )
        val exported = PromptImportExport.buildExport(polluted).toString()

        val pill = PromptImportExport.parse(exported).single()

        assertEquals(PromptType.TEXT, pill.typeEnum)
        assertEquals(false, pill.requiresSelection)
        assertEquals(false, pill.autoApply)
    }

    // --- Edge cases: malformed input ---

    @Test(expected = org.json.JSONException::class)
    fun `empty string throws so the Activity can show import-failed`() {
        PromptImportExport.parse("")
    }

    @Test(expected = org.json.JSONException::class)
    fun `non-JSON garbage throws so the Activity can show import-failed`() {
        PromptImportExport.parse("this is not json")
    }

    @Test
    fun `bare empty array parses to no prompts`() {
        assertEquals(0, PromptImportExport.parse("[]").size)
    }

    @Test
    fun `entries missing name or prompt are skipped`() {
        val json = """
            {"version":2,"prompts":[
              {"prompt":"orphan prompt","type":"PROMPT"},
              {"name":"orphan name","type":"PROMPT"},
              {"name":"","prompt":"empty name","type":"PROMPT"},
              {"name":"empty prompt","prompt":"","type":"PROMPT"},
              {"name":"keep","prompt":"kept","type":"PROMPT"}
            ]}
        """.trimIndent()

        val parsed = PromptImportExport.parse(json)

        assertEquals(1, parsed.size)
        assertEquals("keep", parsed.single().name)
    }

    @Test
    fun `non-object array entries are skipped and positions stay contiguous`() {
        val json = """
            {"version":2,"prompts":[
              42,
              "a bare string",
              null,
              {"name":"first","prompt":"one","type":"PROMPT"},
              [1,2,3],
              {"name":"second","prompt":"two","type":"PROMPT"}
            ]}
        """.trimIndent()

        val parsed = PromptImportExport.parse(json)

        assertEquals(2, parsed.size)
        assertEquals("first", parsed[0].name)
        assertEquals(0, parsed[0].pos)
        assertEquals("second", parsed[1].name)
        assertEquals(1, parsed[1].pos)
    }

    // --- Unicode / emoji round-trip ---

    @Test
    fun `unicode and emoji survive an export - import round trip verbatim`() {
        val original = listOf(
            PromptEntity(id = 1, pos = 0, name = "Emoji 🎉🚀", prompt = "Grüße — “quoted”, \n newline, tab\t, 日本語, \\backslash\\", requiresSelection = false, autoApply = false, type = PromptType.TEXT.name),
        )
        val parsed = PromptImportExport.parse(PromptImportExport.buildExport(original).toString()).single()

        assertEquals("Emoji 🎉🚀", parsed.name)
        assertEquals("Grüße — “quoted”, \n newline, tab\t, 日本語, \\backslash\\", parsed.prompt)
    }

    // --- Large input ---

    @Test
    fun `a large export round-trips every entry`() {
        val original = (0 until 500).map {
            PromptEntity(id = it + 1, pos = it, name = "Prompt $it", prompt = "Body $it ".repeat(200), requiresSelection = it % 2 == 0, autoApply = false, type = PromptType.PROMPT.name)
        }
        val parsed = PromptImportExport.parse(PromptImportExport.buildExport(original).toString())

        assertEquals(500, parsed.size)
        assertEquals("Prompt 0", parsed.first().name)
        assertEquals("Prompt 499", parsed.last().name)
        assertEquals(499, parsed.last().pos)
    }
}
