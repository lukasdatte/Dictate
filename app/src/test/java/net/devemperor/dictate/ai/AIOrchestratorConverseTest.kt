package net.devemperor.dictate.ai

import android.content.SharedPreferences
import net.devemperor.dictate.ai.adapter.AndroidAiConfig
import net.devemperor.dictate.ai.adapter.MediaMetadataAudioDurationReader
import net.devemperor.dictate.ai.adapter.RoomUsageSink
import net.devemperor.dictate.ai.adapter.SharedPrefsProxyConfig
import net.devemperor.dictate.ai.conversation.ConversationMessage
import net.devemperor.dictate.ai.factory.RunnerFactory
import net.devemperor.dictate.ai.runner.CompletionOptions
import net.devemperor.dictate.ai.runner.CompletionResult
import net.devemperor.dictate.ai.runner.CompletionRunner
import net.devemperor.dictate.ai.runner.ConversationRequest
import net.devemperor.dictate.ai.runner.ConversationResult
import net.devemperor.dictate.ai.runner.TranscriptionRunner
import net.devemperor.dictate.database.dao.UsageDao
import net.devemperor.dictate.database.entity.MessageRole
import net.devemperor.dictate.database.entity.ResponseFormatKind
import net.devemperor.dictate.database.entity.UsageEntity
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [AIOrchestrator.converse] passes the structured result through and
 * tracks usage exactly like [AIOrchestrator.complete] (ADR-0012). The runner is
 * faked via the `open RunnerFactory` seam (K-1).
 */
@RunWith(RobolectricTestRunner::class)
class AIOrchestratorConverseTest {

    private val sp: SharedPreferences = FakeSharedPreferences()

    private class CapturingUsageDao : UsageDao {
        val calls = mutableListOf<List<Any>>()
        override fun getAll(): List<UsageEntity> = emptyList()
        override fun getByModelName(modelName: String): UsageEntity? = null
        override fun upsert(entity: UsageEntity) = Unit
        override fun addUsage(modelName: String, audioTime: Long, inputTokens: Long, outputTokens: Long, provider: String) {
            calls += listOf(modelName, audioTime, inputTokens, outputTokens, provider)
        }
        override fun deleteAll() = Unit
        override fun getTotalAudioTime(): Long? = 0
    }

    private class FakeFactory(
        sp: SharedPreferences,
        val onConverse: (ConversationRequest) -> ConversationResult
    ) : RunnerFactory(AndroidAiConfig(sp), SharedPrefsProxyConfig(sp), MediaMetadataAudioDurationReader()) {
        var lastRequest: ConversationRequest? = null
        override fun getProvider(function: AIFunction): AIProvider = AIProvider.ANTHROPIC
        override fun getModelName(function: AIFunction): String = "claude-test"
        override fun createCompletionRunner(): CompletionRunner = object : CompletionRunner {
            override fun complete(options: CompletionOptions): CompletionResult =
                throw UnsupportedOperationException()
            override fun converse(request: ConversationRequest): ConversationResult {
                lastRequest = request
                return onConverse(request)
            }
        }
        override fun createTranscriptionRunner(): TranscriptionRunner =
            throw UnsupportedOperationException()
    }

    @Test
    fun `converse passes result through and tracks usage`() {
        val usage = CapturingUsageDao()
        val factory = FakeFactory(sp) {
            ConversationResult(
                message = "did it",
                output = "the text",
                promptTokens = 11,
                completionTokens = 22,
                modelName = "claude-test",
                responseFormat = ResponseFormatKind.TOOL_USE
            )
        }
        val orchestrator = AIOrchestrator(AndroidAiConfig(sp), RoomUsageSink(usage), factory)

        val result = orchestrator.converse(
            listOf(ConversationMessage(MessageRole.USER, "hello")),
            systemPrompt = "sys"
        )

        assertEquals("did it", result.message)
        assertEquals("the text", result.output)
        assertEquals(ResponseFormatKind.TOOL_USE, result.responseFormat)
        // usage: (model, audioTime=0, in, out, provider)
        assertEquals(1, usage.calls.size)
        assertEquals(listOf<Any>("claude-test", 0L, 11L, 22L, "ANTHROPIC"), usage.calls[0])
        // request carried the system prompt + messages
        assertEquals("sys", factory.lastRequest?.systemPrompt)
        assertEquals(1, factory.lastRequest?.messages?.size)
    }

    @Test(expected = AIProviderException::class)
    fun `converse re-wraps provider exception with provider`() {
        val factory = FakeFactory(sp) {
            throw AIProviderException(AIProviderException.ErrorType.RATE_LIMITED, "slow down")
        }
        val orchestrator = AIOrchestrator(AndroidAiConfig(sp), RoomUsageSink(CapturingUsageDao()), factory)
        orchestrator.converse(listOf(ConversationMessage(MessageRole.USER, "x")), null)
    }
}
