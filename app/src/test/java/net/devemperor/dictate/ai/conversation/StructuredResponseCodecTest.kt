package net.devemperor.dictate.ai.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the `{message, output}` wire codec (ADR-0012). No Android,
 * no JSON library — proves the hand-rolled lenient parser round-trips [encode]
 * and degrades gracefully on non-schema text.
 */
class StructuredResponseCodecTest {

    @Test
    fun `parses a clean schema object`() {
        val r = StructuredResponseCodec.parseLenient(
            """{"message":"tidied it up","output":"Hello world."}"""
        )
        assertEquals("tidied it up", r.message)
        assertEquals("Hello world.", r.output)
    }

    @Test
    fun `null message parses to null`() {
        val r = StructuredResponseCodec.parseLenient("""{"message":null,"output":"x"}""")
        assertNull(r.message)
        assertEquals("x", r.output)
    }

    @Test
    fun `missing message field parses to null`() {
        val r = StructuredResponseCodec.parseLenient("""{"output":"only output"}""")
        assertNull(r.message)
        assertEquals("only output", r.output)
    }

    @Test
    fun `strips json code fences`() {
        val r = StructuredResponseCodec.parseLenient(
            "```json\n{\"message\":\"m\",\"output\":\"o\"}\n```"
        )
        assertEquals("m", r.message)
        assertEquals("o", r.output)
    }

    @Test
    fun `plain prose becomes output with null message`() {
        val r = StructuredResponseCodec.parseLenient("Just some reworded text.")
        assertNull(r.message)
        assertEquals("Just some reworded text.", r.output)
    }

    @Test
    fun `object without output field falls back to whole text`() {
        val raw = """{"foo":"bar"}"""
        val r = StructuredResponseCodec.parseLenient(raw)
        assertNull(r.message)
        assertEquals(raw, r.output)
    }

    @Test
    fun `handles escaped quotes and newlines in output`() {
        val r = StructuredResponseCodec.parseLenient(
            """{"message":"m","output":"line1\nline2 with \"quote\""}"""
        )
        assertEquals("line1\nline2 with \"quote\"", r.output)
    }

    @Test
    fun `braces inside string values do not confuse extraction`() {
        val r = StructuredResponseCodec.parseLenient(
            """{"message":"see {this}","output":"a } b { c"}"""
        )
        assertEquals("see {this}", r.message)
        assertEquals("a } b { c", r.output)
    }

    @Test
    fun `encode then parse round-trips including control chars`() {
        val message = "did \"stuff\"\nwith\ttabs"
        val output = "output with \\ backslash and { brace }"
        val encoded = StructuredResponseCodec.encode(message, output)
        val r = StructuredResponseCodec.parseLenient(encoded)
        assertEquals(message, r.message)
        assertEquals(output, r.output)
    }

    @Test
    fun `encode null message round-trips`() {
        val encoded = StructuredResponseCodec.encode(null, "o")
        val r = StructuredResponseCodec.parseLenient(encoded)
        assertNull(r.message)
        assertEquals("o", r.output)
    }

    @Test
    fun `unicode escapes are decoded`() {
        // Literal backslash-u sequence in the JSON, must decode to 'é'.
        val r = StructuredResponseCodec.parseLenient("{\"output\":\"caf\\u00e9\"}")
        assertEquals("café", r.output)
    }

    @Test
    fun `field names expose the canonical schema keys`() {
        assertEquals("message" to "output", StructuredResponseCodec.fieldNames)
    }

    // ── needsClarification verdict field (ADR-0013) ──

    @Test
    fun `parses needsClarification true`() {
        val r = StructuredResponseCodec.parseLenient(
            """{"message":"m","output":"o","needsClarification":true}"""
        )
        assertTrue(r.needsClarification)
    }

    @Test
    fun `parses needsClarification false`() {
        val r = StructuredResponseCodec.parseLenient(
            """{"message":"m","output":"o","needsClarification":false}"""
        )
        assertFalse(r.needsClarification)
    }

    @Test
    fun `absent needsClarification defaults to false`() {
        val r = StructuredResponseCodec.parseLenient("""{"message":"m","output":"o"}""")
        assertFalse(r.needsClarification)
    }

    @Test
    fun `plain prose has needsClarification false`() {
        val r = StructuredResponseCodec.parseLenient("just text")
        assertFalse(r.needsClarification)
    }

    @Test
    fun `needsClarification key inside a string value is ignored`() {
        // The literal marker appears inside output's string, not as a real key.
        val r = StructuredResponseCodec.parseLenient(
            """{"output":"talk about needsClarification here","message":null}"""
        )
        assertFalse(r.needsClarification)
        assertEquals("talk about needsClarification here", r.output)
    }

    @Test
    fun `encode stays two-field and drops the verdict (replay regression)`() {
        // ADR-0013: a replayed assistant turn is {message, output} only —
        // the verdict is never serialized back to the model.
        val encoded = StructuredResponseCodec.encode(
            StructuredResponse(message = "m", output = "o", needsClarification = true)
        )
        assertFalse(encoded.contains(StructuredResponseCodec.needsClarificationField))
        val r = StructuredResponseCodec.parseLenient(encoded)
        assertEquals("m", r.message)
        assertEquals("o", r.output)
        assertFalse(r.needsClarification)
    }
}
