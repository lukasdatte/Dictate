package net.devemperor.dictate.ai.conversation

import net.devemperor.dictate.database.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for the API-message reconstruction shared by regenerate + continuation. */
class ConversationReconstructorTest {

    @Test
    fun `turn zero is a single user message`() {
        val msgs = ConversationReconstructor.toApiMessages(emptyList(), "first user message")
        assertEquals(1, msgs.size)
        assertEquals(MessageRole.USER, msgs[0].role)
        assertEquals("first user message", msgs[0].content)
    }

    @Test
    fun `prior turns interleave user and assistant, trailing user last`() {
        val prior = listOf(
            ReconstructedTurn("u0", "out0", "msg0"),
            ReconstructedTurn("u1", "out1", null)
        )
        val msgs = ConversationReconstructor.toApiMessages(prior, "u2")
        assertEquals(5, msgs.size)
        assertEquals(MessageRole.USER, msgs[0].role)
        assertEquals("u0", msgs[0].content)
        assertEquals(MessageRole.ASSISTANT, msgs[1].role)
        assertEquals(MessageRole.USER, msgs[2].role)
        assertEquals("u1", msgs[2].content)
        assertEquals(MessageRole.ASSISTANT, msgs[3].role)
        assertEquals(MessageRole.USER, msgs[4].role)
        assertEquals("u2", msgs[4].content)
    }

    @Test
    fun `assistant content is the full message+output json (replay referent)`() {
        val prior = listOf(ReconstructedTurn("u0", "the output", "the explanation"))
        val msgs = ConversationReconstructor.toApiMessages(prior, "next")
        val assistant = msgs[1]
        assertEquals(MessageRole.ASSISTANT, assistant.role)
        val decoded = StructuredResponseCodec.parseLenient(assistant.content)
        assertEquals("the explanation", decoded.message)
        assertEquals("the output", decoded.output)
    }
}
