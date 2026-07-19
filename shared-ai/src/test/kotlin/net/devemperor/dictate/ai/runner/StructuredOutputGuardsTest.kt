package net.devemperor.dictate.ai.runner

import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.AIProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure-JVM tests for the structured-output runner guards (G2-2). */
class StructuredOutputGuardsTest {

    @Test
    fun `requireNotTruncated throws a server error when truncated`() {
        val e = assertThrows(AIProviderException::class.java) {
            StructuredOutputGuards.requireNotTruncated(truncated = true, provider = AIProvider.OPENAI)
        }
        assertEquals(AIProviderException.ErrorType.SERVER_ERROR, e.errorType)
        assertEquals(AIProvider.OPENAI, e.provider)
    }

    @Test
    fun `requireNotTruncated is a no-op when not truncated`() {
        StructuredOutputGuards.requireNotTruncated(truncated = false, provider = AIProvider.ANTHROPIC)
    }
}
