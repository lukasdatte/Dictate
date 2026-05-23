package net.devemperor.dictate.database.converter

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [Converters] — boolean and string-list conversion.
 *
 * The string-list contract is load-bearing for ADR-0007's dual-column
 * window: the empty list MUST round-trip through the empty string so
 * the SQL `DEFAULT ''` on `audio_file_paths` deserialises to
 * `emptyList()` and matches the Kotlin entity default.
 */
class ConvertersTest {

    private val converter = Converters()

    // ── Boolean ────────────────────────────────────────────────────

    @Test
    fun `fromBoolean true is 1`() {
        assertEquals(1, converter.fromBoolean(true))
    }

    @Test
    fun `fromBoolean false is 0`() {
        assertEquals(0, converter.fromBoolean(false))
    }

    @Test
    fun `toBoolean 0 is false`() {
        assertEquals(false, converter.toBoolean(0))
    }

    @Test
    fun `toBoolean 1 is true`() {
        assertEquals(true, converter.toBoolean(1))
    }

    @Test
    fun `toBoolean non-zero is true`() {
        // Defensive: any non-zero stored int decodes to `true`.
        assertEquals(true, converter.toBoolean(42))
    }

    // ── String list ────────────────────────────────────────────────

    @Test
    fun `fromStringList empty list encodes to empty string`() {
        // Load-bearing for SQL `DEFAULT ''` ↔ Kotlin `emptyList()`.
        assertEquals("", converter.fromStringList(emptyList()))
    }

    @Test
    fun `fromStringList single entry has no delimiter`() {
        assertEquals("/cache/audio/sess_seg1.m4a",
            converter.fromStringList(listOf("/cache/audio/sess_seg1.m4a")))
    }

    @Test
    fun `fromStringList multi entries are pipe-joined`() {
        assertEquals(
            "/a.m4a|/b.m4a|/c.m4a",
            converter.fromStringList(listOf("/a.m4a", "/b.m4a", "/c.m4a"))
        )
    }

    @Test
    fun `toStringList empty string decodes to empty list`() {
        assertEquals(emptyList<String>(), converter.toStringList(""))
    }

    @Test
    fun `toStringList single entry decodes without split`() {
        assertEquals(listOf("/a.m4a"), converter.toStringList("/a.m4a"))
    }

    @Test
    fun `toStringList multi entries split on pipe`() {
        assertEquals(
            listOf("/a.m4a", "/b.m4a", "/c.m4a"),
            converter.toStringList("/a.m4a|/b.m4a|/c.m4a")
        )
    }

    @Test
    fun `round-trip preserves three entries`() {
        val original = listOf("/cache/audio/sess_seg1.m4a", "/cache/audio/sess_seg2.m4a", "/cache/audio/sess_seg3.m4a")
        assertEquals(original, converter.toStringList(converter.fromStringList(original)))
    }

    @Test
    fun `round-trip preserves empty list`() {
        // Same invariant as above, isolated: the dual-column window
        // depends on `'' ↔ emptyList()` being symmetric.
        assertEquals(emptyList<String>(), converter.toStringList(converter.fromStringList(emptyList())))
    }
}
