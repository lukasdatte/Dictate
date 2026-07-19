package net.devemperor.dictate.ai.runner

import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.conversation.StructuredResponseCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM guards for the conversation runner policy (ADR-0012): which
 * providers fall back to text on a rejected structured request, and that the
 * fallback instruction names the schema fields.
 *
 * The SDK-call bodies of `converse()` are exercised end-to-end through the
 * `open RunnerFactory` fake seam at the pipeline layer (conv-6); here we lock
 * the policy that decides the fallback branch.
 */
class StructuredOutputSupportTest {

    @Test
    fun `heterogeneous OpenAI-compatible endpoints allow text fallback`() {
        // CUSTOM / OpenRouter / Groq front many models, not all of which
        // support response_format=json_schema; a 400 there is a capability gap,
        // not a real error, so the runner degrades to text (G2-7).
        assertTrue(AIProvider.CUSTOM.allowsStructuredOutputTextFallback)
        assertTrue(AIProvider.OPENROUTER.allowsStructuredOutputTextFallback)
        assertTrue(AIProvider.GROQ.allowsStructuredOutputTextFallback)
        // First-party single-catalog providers treat a 400 as a real error.
        assertFalse(AIProvider.OPENAI.allowsStructuredOutputTextFallback)
        assertFalse(AIProvider.ANTHROPIC.allowsStructuredOutputTextFallback)
        assertFalse(AIProvider.ELEVENLABS.allowsStructuredOutputTextFallback)
    }

    @Test
    fun `fallback instruction names both schema fields`() {
        val (message, output) = StructuredResponseCodec.fieldNames
        val instruction = StructuredResponseCodec.fallbackInstruction()
        assertTrue(instruction.contains(message))
        assertTrue(instruction.contains(output))
    }

    @Test
    fun `fallback instruction output round-trips through the codec`() {
        // A model that obeys the fallback instruction produces schema JSON.
        val obeyed = StructuredResponseCodec.encode("m", "o")
        val parsed = StructuredResponseCodec.parseLenient(obeyed)
        assertEquals("m", parsed.message)
        assertEquals("o", parsed.output)
    }
}
