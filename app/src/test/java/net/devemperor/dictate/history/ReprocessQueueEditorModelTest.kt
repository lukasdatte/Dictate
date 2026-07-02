package net.devemperor.dictate.history

import net.devemperor.dictate.core.PromptQueueSlot
import net.devemperor.dictate.database.entity.PromptEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ReprocessQueueEditorModel] — the UI-free editor state
 * (preload / add / remove / reorder / snapshot) behind
 * [ReprocessQueueEditorBottomSheet]. Pure JVM, no Android types.
 */
class ReprocessQueueEditorModelTest {

    private fun savedPrompt(
        id: Int,
        name: String? = "Prompt $id",
        text: String? = "Instruction $id"
    ) = PromptEntity(
        id = id, pos = id, name = name, prompt = text,
        requiresSelection = true, autoApply = false
    )

    // ── Preload ───────────────────────────────────────────────────────────

    @Test
    fun `preload resolves the historical queue in order and drops deleted prompts`() {
        val store = mapOf(1 to savedPrompt(1), 3 to savedPrompt(3))

        val model = ReprocessQueueEditorModel.preload(
            historicalEntityIds = listOf(3, 2, 1), // 2 was deleted since the run
            resolvePrompt = { store[it] }
        )

        assertEquals(listOf(3, 1), model.entries.map { it.entityId })
        assertEquals(listOf("Instruction 3", "Instruction 1"), model.entries.map { it.text })
        assertEquals(listOf("Prompt 3", "Prompt 1"), model.entries.map { it.displayName })
    }

    // ── Add ───────────────────────────────────────────────────────────────

    @Test
    fun `addSavedPrompt appends once - duplicates and blank content are rejected`() {
        val model = ReprocessQueueEditorModel.empty()

        assertTrue(model.addSavedPrompt(savedPrompt(5)))
        assertFalse("same prompt must not be queueable twice", model.addSavedPrompt(savedPrompt(5)))
        assertFalse("blank content is unusable", model.addSavedPrompt(savedPrompt(6, text = "  ")))
        assertFalse("null content is unusable", model.addSavedPrompt(savedPrompt(7, text = null)))

        assertEquals(1, model.size)
        assertTrue(model.containsSavedPrompt(5))
    }

    @Test
    fun `saved prompt without a name falls back to a content preview label`() {
        val model = ReprocessQueueEditorModel.empty()
        model.addSavedPrompt(savedPrompt(5, name = null, text = "Translate to French"))
        assertEquals("Translate to French", model.entries.single().displayName)
    }

    @Test
    fun `addFreeText trims, rejects blank and labels with a single-line preview`() {
        val model = ReprocessQueueEditorModel.empty()

        assertFalse(model.addFreeText("   "))
        assertTrue(model.addFreeText("  Summarize this.\nSecond line  "))

        val entry = model.entries.single()
        assertEquals("Summarize this.\nSecond line", entry.text)
        assertEquals(null, entry.entityId)
        assertEquals("Summarize this.", entry.displayName)
    }

    @Test
    fun `free-text display preview is capped for long single lines`() {
        val model = ReprocessQueueEditorModel.empty()
        model.addFreeText("x".repeat(60))
        assertEquals("x".repeat(40) + "…", model.entries.single().displayName)
    }

    // ── Remove / reorder ──────────────────────────────────────────────────

    @Test
    fun `removeAt deletes exactly the indexed entry and frees the saved prompt for re-adding`() {
        val model = ReprocessQueueEditorModel.empty()
        model.addSavedPrompt(savedPrompt(1))
        model.addFreeText("free")
        model.addSavedPrompt(savedPrompt(2))

        assertTrue(model.removeAt(0))
        assertEquals(listOf(null, 2), model.entries.map { it.entityId })
        assertFalse(model.containsSavedPrompt(1))
        assertTrue("removed prompt is addable again", model.addSavedPrompt(savedPrompt(1)))

        assertFalse(model.removeAt(-1))
        assertFalse(model.removeAt(99))
    }

    @Test
    fun `move reorders entries and ignores out-of-range indices`() {
        val model = ReprocessQueueEditorModel.empty()
        model.addSavedPrompt(savedPrompt(1))
        model.addSavedPrompt(savedPrompt(2))
        model.addFreeText("free")

        assertTrue(model.move(2, 0))
        assertEquals(listOf(null, 1, 2), model.entries.map { it.entityId })

        assertTrue(model.move(1, 2))
        assertEquals(listOf(null, 2, 1), model.entries.map { it.entityId })

        assertFalse(model.move(-1, 0))
        assertFalse(model.move(0, 3))
        assertTrue("no-op move succeeds", model.move(1, 1))
        assertEquals(listOf(null, 2, 1), model.entries.map { it.entityId })
    }

    // ── Transport mapping ─────────────────────────────────────────────────

    @Test
    fun `toSlots emits content-carrying slots in editor order`() {
        val model = ReprocessQueueEditorModel.empty()
        model.addSavedPrompt(savedPrompt(4))
        model.addFreeText("free instruction")

        assertEquals(
            listOf(
                PromptQueueSlot.ofContent("Instruction 4", 4),
                PromptQueueSlot.ofFreeText("free instruction")
            ),
            model.toSlots()
        )
    }

    // ── Snapshot round-trip (rotation safety) ─────────────────────────────

    @Test
    fun `snapshot and fromSnapshot round-trip the full editor state`() {
        val model = ReprocessQueueEditorModel.empty()
        model.addSavedPrompt(savedPrompt(4))
        model.addFreeText("free instruction")
        model.move(1, 0)

        val restored = ReprocessQueueEditorModel.fromSnapshot(model.snapshot())

        assertEquals(model.entries, restored.entries)
        assertEquals(model.toSlots(), restored.toSlots())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromSnapshot rejects diverged snapshot lists`() {
        ReprocessQueueEditorModel.fromSnapshot(
            ReprocessQueueEditorModel.Snapshot(
                texts = listOf("a"),
                entityIds = emptyList(),
                displayNames = listOf("a")
            )
        )
    }
}
