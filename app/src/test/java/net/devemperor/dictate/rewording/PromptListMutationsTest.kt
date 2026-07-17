package net.devemperor.dictate.rewording

import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.database.entity.PromptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Position/copy arithmetic for the prompts overview ([PromptListMutations]):
 * drag-and-drop persistence writes `pos = index` for drifted rows only, and
 * duplication produces a fresh row that keeps the source's type and flags.
 */
class PromptListMutationsTest {

    private fun entity(id: Int, pos: Int, name: String = "P$id", type: PromptType = PromptType.PROMPT) =
        PromptEntity(id, pos, name, "prompt $id", requiresSelection = id % 2 == 0, autoApply = false, type = type.name)

    @Test
    fun `copyOf keeps content and type, resets id, appends suffix`() {
        val source = PromptEntity(7, 2, "Greeting", "Hello!", requiresSelection = true, autoApply = true, type = PromptType.TEXT.name)

        val copy = PromptListMutations.copyOf(source, "(Copy)", pos = 5)

        assertEquals(0, copy.id)
        assertEquals(5, copy.pos)
        assertEquals("Greeting (Copy)", copy.name)
        assertEquals("Hello!", copy.prompt)
        assertEquals(true, copy.requiresSelection)
        assertEquals(true, copy.autoApply)
        assertEquals(PromptType.TEXT, copy.typeEnum)
    }

    @Test
    fun `resequenced returns nothing for an already ordered list`() {
        val list = listOf(entity(1, 0), entity(2, 1), entity(3, 2))
        assertTrue(PromptListMutations.resequenced(list).isEmpty())
    }

    @Test
    fun `resequenced rewrites only drifted rows after a move`() {
        // Item id=3 dragged from index 2 to index 0.
        val list = listOf(entity(3, 2), entity(1, 0), entity(2, 1))

        val changed = PromptListMutations.resequenced(list)

        assertEquals(listOf(3 to 0, 1 to 1, 2 to 2), changed.map { it.id to it.pos })
    }

    @Test
    fun `resequenced closes the gap after a mid-list insertion`() {
        // Copy (id=9) inserted at index 1 with a tail pos of 3.
        val list = listOf(entity(1, 0), entity(9, 3), entity(2, 1), entity(3, 2))

        val changed = PromptListMutations.resequenced(list)

        assertEquals(listOf(9 to 1, 2 to 2, 3 to 3), changed.map { it.id to it.pos })
    }
}
