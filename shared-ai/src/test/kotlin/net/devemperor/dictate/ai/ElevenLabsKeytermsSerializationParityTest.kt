package net.devemperor.dictate.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization test for the A3.4 org.json → kotlinx-serialization swap of
 * [ElevenLabsKeytermsParser.toJson] / [ElevenLabsKeytermsParser.fromJson].
 *
 * The stored `ElevenLabsKeytermsParsed` pref is a closed toJson → fromJson
 * round-trip; the wire (the multipart body) sends the parsed List, not the JSON
 * string. So the load-bearing contract is: the round-trip is lossless, empty /
 * `[]` inputs map to an empty list, and legacy org.json-produced arrays still
 * parse. These assertions were true under org.json and must stay true.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §8.1
 */
class ElevenLabsKeytermsSerializationParityTest {

    @Test
    fun `empty list serializes to bracket-pair`() {
        assertEquals("[]", ElevenLabsKeytermsParser.toJson(emptyList()))
    }

    @Test
    fun `empty and bracket-pair inputs parse to empty list`() {
        assertTrue(ElevenLabsKeytermsParser.fromJson("").isEmpty())
        assertTrue(ElevenLabsKeytermsParser.fromJson("   ").isEmpty())
        assertTrue(ElevenLabsKeytermsParser.fromJson("[]").isEmpty())
    }

    @Test
    fun `round-trip preserves ASCII terms and order`() {
        val terms = listOf("Alpha", "Beta", "Gamma")
        assertEquals(terms, ElevenLabsKeytermsParser.fromJson(ElevenLabsKeytermsParser.toJson(terms)))
    }

    @Test
    fun `round-trip preserves unicode and punctuation terms`() {
        val terms = listOf("café", "naïve", "北京", "Ω-signal", "term with spaces")
        val json = ElevenLabsKeytermsParser.toJson(terms)
        assertEquals(terms, ElevenLabsKeytermsParser.fromJson(json))
    }

    @Test
    fun `legacy org-json-style array string still parses`() {
        // A value written by the old org.json toJson (JSONArray(...).toString()).
        assertEquals(listOf("Alpha", "Beta"), ElevenLabsKeytermsParser.fromJson("""["Alpha","Beta"]"""))
    }

    @Test
    fun `malformed json parses defensively to empty list`() {
        assertTrue(ElevenLabsKeytermsParser.fromJson("not-json").isEmpty())
        assertTrue(ElevenLabsKeytermsParser.fromJson("""{"a":1}""").isEmpty())
    }
}
