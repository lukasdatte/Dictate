package net.devemperor.dictate.ai

import net.devemperor.dictate.ai.ElevenLabsKeytermsParser.MAX_TERMS
import net.devemperor.dictate.ai.ElevenLabsKeytermsParser.MAX_TERM_LENGTH
import net.devemperor.dictate.ai.ElevenLabsKeytermsParser.MAX_TERM_WORDS
import org.junit.Assert.*
import org.junit.Test

class ElevenLabsKeytermsParserTest {

    @Test
    fun `empty input returns empty result`() {
        val result = ElevenLabsKeytermsParser.parse("")
        assertTrue(result.terms.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `blank input returns empty result`() {
        val result = ElevenLabsKeytermsParser.parse("   \n  \n  ")
        assertTrue(result.terms.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `comma separated terms`() {
        val result = ElevenLabsKeytermsParser.parse("ElevenLabs, Dictate, DevEmperor")
        assertEquals(listOf("ElevenLabs", "Dictate", "DevEmperor"), result.terms)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `newline separated terms`() {
        val result = ElevenLabsKeytermsParser.parse("ElevenLabs\nDictate\nDevEmperor")
        assertEquals(listOf("ElevenLabs", "Dictate", "DevEmperor"), result.terms)
    }

    @Test
    fun `mixed comma and newline`() {
        val result = ElevenLabsKeytermsParser.parse("Alpha, Beta\nGamma\nDelta, Epsilon")
        assertEquals(listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon"), result.terms)
    }

    @Test
    fun `windows CRLF line endings`() {
        val result = ElevenLabsKeytermsParser.parse("Alpha\r\nBeta\r\nGamma")
        assertEquals(listOf("Alpha", "Beta", "Gamma"), result.terms)
    }

    @Test
    fun `comment lines are filtered and counted`() {
        val result = ElevenLabsKeytermsParser.parse("ElevenLabs\n# This is a comment\nDictate")
        assertEquals(listOf("ElevenLabs", "Dictate"), result.terms)
        assertEquals(1, result.commentCount)
    }

    @Test
    fun `comment-only input returns empty with comment count`() {
        val result = ElevenLabsKeytermsParser.parse("# comment 1\n# comment 2")
        assertTrue(result.terms.isEmpty())
        assertEquals(2, result.commentCount)
    }

    @Test
    fun `no comments yields zero commentCount`() {
        val result = ElevenLabsKeytermsParser.parse("Alpha, Beta")
        assertEquals(0, result.commentCount)
    }

    @Test
    fun `trailing commas and empty lines are ignored`() {
        val result = ElevenLabsKeytermsParser.parse("Alpha,,Beta,\n\nGamma,")
        assertEquals(listOf("Alpha", "Beta", "Gamma"), result.terms)
    }

    @Test
    fun `terms are trimmed`() {
        val result = ElevenLabsKeytermsParser.parse("  Alpha  ,  Beta  \n  Gamma  ")
        assertEquals(listOf("Alpha", "Beta", "Gamma"), result.terms)
    }

    @Test
    fun `duplicate terms are deduplicated`() {
        val result = ElevenLabsKeytermsParser.parse("Alpha, Beta, Alpha, Gamma, Beta")
        assertEquals(listOf("Alpha", "Beta", "Gamma"), result.terms)
    }

    @Test
    fun `term exceeding max length produces TOO_LONG error`() {
        val longTerm = "A".repeat(MAX_TERM_LENGTH + 1)
        val result = ElevenLabsKeytermsParser.parse("Valid, $longTerm, AlsoValid")
        assertEquals(listOf("Valid", "AlsoValid"), result.terms)
        assertEquals(1, result.errors.size)
        assertEquals(longTerm, result.errors[0].term)
        assertEquals(ElevenLabsKeytermsParser.ErrorReason.TOO_LONG, result.errors[0].reason)
    }

    @Test
    fun `term with exactly max length is valid`() {
        val termAtLimit = "A".repeat(MAX_TERM_LENGTH)
        val result = ElevenLabsKeytermsParser.parse(termAtLimit)
        assertEquals(listOf(termAtLimit), result.terms)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `term exceeding max words produces TOO_MANY_WORDS error`() {
        val tooManyWords = (1..MAX_TERM_WORDS + 1).joinToString(" ") { "word$it" }
        val result = ElevenLabsKeytermsParser.parse("Valid, $tooManyWords")
        assertEquals(listOf("Valid"), result.terms)
        assertEquals(1, result.errors.size)
        assertEquals(ElevenLabsKeytermsParser.ErrorReason.TOO_MANY_WORDS, result.errors[0].reason)
    }

    @Test
    fun `term with exactly max words is valid`() {
        val atLimit = (1..MAX_TERM_WORDS).joinToString(" ") { "word$it" }
        val result = ElevenLabsKeytermsParser.parse(atLimit)
        assertEquals(listOf(atLimit), result.terms)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `more than max terms produces TOO_MANY_TERMS errors`() {
        val overflow = 5
        val input = (1..MAX_TERMS + overflow).joinToString(",") { "Term$it" }
        val result = ElevenLabsKeytermsParser.parse(input)
        assertEquals(MAX_TERMS, result.terms.size)
        assertEquals(overflow, result.errors.size)
        assertTrue(result.errors.all { it.reason == ElevenLabsKeytermsParser.ErrorReason.TOO_MANY_TERMS })
    }

    // toJson/fromJson tests require org.json.JSONArray which is an Android framework class
    // not available in local JVM unit tests without returnDefaultValues or Robolectric.
    // JSON roundtrip is trivial (JSONArray constructor + toString) and covered by integration tests.
}
