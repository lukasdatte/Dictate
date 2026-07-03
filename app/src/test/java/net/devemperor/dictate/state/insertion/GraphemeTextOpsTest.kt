package net.devemperor.dictate.state.insertion

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Grapheme-cluster math tests for [GraphemeTextOps] — the pure core of the
 * F-018 (grapheme backspace) and F-020 (grapheme animation chunks) fixes.
 *
 * The fixture strings cover every cluster family the findings name:
 * surrogate-pair emoji, ZWJ family sequence, skin-tone modifier, regional
 * indicator flag, combining diacritics, and plain ASCII.
 */
class GraphemeTextOpsTest {

    // Cluster fixtures (escapes keep the file editor-safe and self-documenting).
    private companion object {
        /** U+1F600 GRINNING FACE — one surrogate pair, 2 UTF-16 units. */
        const val EMOJI = "😀"

        /** man+ZWJ+woman+ZWJ+girl family — 8 UTF-16 units. */
        const val ZWJ_FAMILY = "👨‍👩‍👧"

        /** thumbs-up + medium skin tone modifier — 4 UTF-16 units. */
        const val SKIN_TONE = "👍🏽"

        /** regional indicators D+E (German flag) — 4 UTF-16 units. */
        const val FLAG_DE = "🇩🇪"

        /** e + combining acute accent — 2 UTF-16 units. */
        const val E_ACUTE_COMBINING = "é"
    }

    // ── lastGraphemeUnitCount (backspace sizing, F-018) ────────────────

    @Test
    fun `ascii char counts one unit`() {
        assertEquals(1, GraphemeTextOps.lastGraphemeUnitCount("abc"))
    }

    @Test
    fun `surrogate-pair emoji counts both units`() {
        assertEquals(2, GraphemeTextOps.lastGraphemeUnitCount("x$EMOJI"))
    }

    @Test
    fun `ZWJ family emoji counts the whole sequence`() {
        assertEquals(8, GraphemeTextOps.lastGraphemeUnitCount("x$ZWJ_FAMILY"))
    }

    @Test
    fun `skin-tone emoji counts base plus modifier`() {
        assertEquals(4, GraphemeTextOps.lastGraphemeUnitCount("x$SKIN_TONE"))
    }

    @Test
    fun `flag counts both regional indicators`() {
        assertEquals(4, GraphemeTextOps.lastGraphemeUnitCount("x$FLAG_DE"))
    }

    @Test
    fun `combining diacritic counts base plus mark`() {
        assertEquals(2, GraphemeTextOps.lastGraphemeUnitCount("x$E_ACUTE_COMBINING"))
    }

    @Test
    fun `single ascii char counts one`() {
        assertEquals(1, GraphemeTextOps.lastGraphemeUnitCount("a"))
    }

    @Test
    fun `text that IS one emoji counts the full pair`() {
        assertEquals(2, GraphemeTextOps.lastGraphemeUnitCount(EMOJI))
    }

    @Test
    fun `empty text counts zero`() {
        assertEquals(0, GraphemeTextOps.lastGraphemeUnitCount(""))
    }

    @Test
    fun `lone high surrogate still counts one (defensive, never zero)`() {
        // Corrupt buffers (e.g. produced by the pre-fix backspace) must not
        // wedge the delete: a lone surrogate is one deletable unit.
        assertEquals(1, GraphemeTextOps.lastGraphemeUnitCount("x\uD83D"))
    }

    // ── graphemeClusters (animation chunks, F-020) ─────────────────────

    @Test
    fun `ascii splits into single chars`() {
        assertEquals(listOf("h", "i"), GraphemeTextOps.graphemeClusters("hi"))
    }

    @Test
    fun `emoji stays one cluster between ascii`() {
        assertEquals(listOf("a", EMOJI, "b"), GraphemeTextOps.graphemeClusters("a${EMOJI}b"))
    }

    @Test
    fun `ZWJ family is one cluster`() {
        assertEquals(listOf(ZWJ_FAMILY), GraphemeTextOps.graphemeClusters(ZWJ_FAMILY))
    }

    @Test
    fun `skin-tone emoji is one cluster`() {
        assertEquals(listOf("x", SKIN_TONE), GraphemeTextOps.graphemeClusters("x$SKIN_TONE"))
    }

    @Test
    fun `combining diacritic stays attached to its base`() {
        assertEquals(
            listOf("c", "a", "f", E_ACUTE_COMBINING),
            GraphemeTextOps.graphemeClusters("caf$E_ACUTE_COMBINING"),
        )
    }

    @Test
    fun `empty text yields no clusters`() {
        assertEquals(emptyList<String>(), GraphemeTextOps.graphemeClusters(""))
    }

    @Test
    fun `joining clusters reproduces the input exactly`() {
        val text = "Hi $EMOJI$ZWJ_FAMILY caf$E_ACUTE_COMBINING $FLAG_DE!"
        assertEquals(text, GraphemeTextOps.graphemeClusters(text).joinToString(""))
    }
}
