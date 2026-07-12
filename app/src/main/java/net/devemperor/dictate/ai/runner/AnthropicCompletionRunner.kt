package net.devemperor.dictate.ai.runner

import android.content.SharedPreferences
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.RateLimitException
import com.anthropic.core.JsonValue
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceTool
import com.anthropic.models.messages.ToolUnion
import net.devemperor.dictate.DictateUtils
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.ai.AIProviderException.ErrorType
import net.devemperor.dictate.ai.conversation.StructuredResponseCodec
import net.devemperor.dictate.database.entity.MessageRole
import net.devemperor.dictate.database.entity.ResponseFormatKind
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import java.io.IOException

/**
 * Runner for Anthropic Claude API.
 * Only completion - no transcription support.
 *
 * Retry: SDK-internal auto-retry via .maxRetries(3).
 * Exceptions: Typed SDK exceptions (identical hierarchy as OpenAI SDK)
 *   -> com.anthropic.errors.{UnauthorizedException, RateLimitException, NotFoundException, ...}
 */
class AnthropicCompletionRunner(
    private val apiKey: String,
    private val sp: SharedPreferences
) : CompletionRunner {

    private fun buildClient(): AnthropicClient {
        val builder = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .maxRetries(3)

        // Proxy support: uses DictateUtils.applyProxyToAnthropic()
        if (sp.get(Pref.ProxyEnabled)) {
            val proxyHost = sp.get(Pref.ProxyHost)
            if (DictateUtils.isValidProxy(proxyHost)) {
                DictateUtils.applyProxyToAnthropic(builder, sp)
            }
        }

        return builder.build()
    }

    /**
     * Exception mapping for Anthropic API calls.
     * Although exception class names are identical to OpenAI SDK,
     * they live in DIFFERENT packages: com.openai.errors.* vs com.anthropic.errors.*.
     * JVM type system distinguishes them, so a shared function is not possible.
     */
    private inline fun <R> wrapProviderCall(
        modelName: String? = null,
        block: () -> R
    ): R {
        try {
            return block()
        } catch (e: UnauthorizedException) {
            throw AIProviderException(ErrorType.INVALID_API_KEY, e.message ?: "", e)
        } catch (e: RateLimitException) {
            throw AIProviderException(ErrorType.RATE_LIMITED, e.message ?: "", e)
        } catch (e: NotFoundException) {
            throw AIProviderException(ErrorType.MODEL_NOT_FOUND, e.message ?: "", e, modelName = modelName)
        } catch (e: BadRequestException) {
            throw AIProviderException(ErrorType.BAD_REQUEST, e.message ?: "", e)
        } catch (e: InternalServerException) {
            throw AIProviderException(ErrorType.SERVER_ERROR, e.message ?: "", e)
        } catch (e: IOException) {
            throw AIProviderException(ErrorType.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    override fun complete(options: CompletionOptions): CompletionResult {
        val client = buildClient()

        // max_tokens is required for Anthropic - default 4096 if not set
        val maxTokens = (options.parameters["max_tokens"] as? Number)?.toLong() ?: 4096

        val paramsBuilder = MessageCreateParams.builder()
            .model(options.model)
            .maxTokens(maxTokens)
            .addUserMessage(options.prompt)

        options.systemPrompt?.let { if (it.isNotEmpty()) paramsBuilder.system(it) }
        applyParameters(paramsBuilder, options.parameters)

        return wrapProviderCall(modelName = options.model) {
            val message = client.messages().create(paramsBuilder.build())

            val text = message.content()
                .filter { it.isText() }
                .joinToString("") { it.asText().text() }

            CompletionResult(
                text = text,
                promptTokens = message.usage().inputTokens(),
                completionTokens = message.usage().outputTokens(),
                modelName = options.model
            )
        }
    }

    override fun converse(request: ConversationRequest): ConversationResult {
        val client = buildClient()

        val maxTokens = (request.parameters["max_tokens"] as? Number)?.toLong() ?: 4096
        val paramsBuilder = MessageCreateParams.builder()
            .model(request.model)
            .maxTokens(maxTokens)
            .addTool(ToolUnion.ofTool(structuredTool()))
            .toolChoice(ToolChoiceTool.builder().name(TOOL_NAME).build())

        request.systemPrompt?.let { if (it.isNotEmpty()) paramsBuilder.system(it) }

        // History assistant turns are replayed as plain text (the serialized
        // {message, output}); we never replay tool_use blocks, so tool_choice
        // forcing the tool on THIS generation needs no matching tool_result
        // (ADR-0012 decision 3).
        for (message in request.messages) {
            when (message.role) {
                MessageRole.USER -> paramsBuilder.addUserMessage(message.content)
                MessageRole.ASSISTANT -> paramsBuilder.addAssistantMessage(message.content)
                MessageRole.SYSTEM -> paramsBuilder.system(message.content)
            }
        }

        applyParameters(paramsBuilder, request.parameters)

        return wrapProviderCall(modelName = request.model) {
            val message = client.messages().create(paramsBuilder.build())

            val toolUse = message.content().firstOrNull { it.isToolUse() }?.asToolUse()
            val parsed = if (toolUse != null) {
                val input = toolUse._input().convert(Map::class.java) as? Map<*, *>
                val (messageField, outputField) = StructuredResponseCodec.fieldNames
                val clarifyField = StructuredResponseCodec.needsClarificationField
                net.devemperor.dictate.ai.conversation.StructuredResponse(
                    message = input?.get(messageField)?.toString(),
                    output = input?.get(outputField)?.toString().orEmpty(),
                    needsClarification = input?.get(clarifyField) == true ||
                        input?.get(clarifyField)?.toString() == "true"
                )
            } else {
                // Defensive: no tool block returned — parse concatenated text.
                val text = message.content()
                    .filter { it.isText() }
                    .joinToString("") { it.asText().text() }
                StructuredResponseCodec.parseLenient(text)
            }

            ConversationResult(
                message = parsed.message,
                output = parsed.output,
                promptTokens = message.usage().inputTokens(),
                completionTokens = message.usage().outputTokens(),
                modelName = request.model,
                responseFormat = if (toolUse != null) ResponseFormatKind.TOOL_USE else ResponseFormatKind.TEXT_FALLBACK,
                needsClarification = parsed.needsClarification
            )
        }
    }

    /**
     * Shared parameter mapping — used by both [complete] and [converse].
     * Runtime validation: temperature XOR top_p (Anthropic rejects both). If
     * both are present (UI prevents this via ParameterRegistry) temperature
     * wins and top_p is dropped. `max_tokens` is applied by the caller.
     */
    private fun applyParameters(
        builder: MessageCreateParams.Builder,
        parameters: Map<String, Any>
    ) {
        val hasTemperature = parameters.containsKey("temperature")
        parameters.forEach { (key, value) ->
            when (key) {
                "temperature" -> builder.temperature((value as Number).toDouble())
                "top_p" -> if (!hasTemperature) builder.topP((value as Number).toDouble())
                "top_k" -> builder.topK((value as Number).toLong())
                "max_tokens" -> {} // handled by the caller
            }
        }
    }

    /** The forced `{message, output, needsClarification}` tool (ADR-0012/0013). */
    private fun structuredTool(): Tool {
        val (messageField, outputField) = StructuredResponseCodec.fieldNames
        val clarifyField = StructuredResponseCodec.needsClarificationField
        val properties = Tool.InputSchema.Properties.builder()
            .putAdditionalProperty(messageField, JsonValue.from(mapOf("type" to "string")))
            .putAdditionalProperty(outputField, JsonValue.from(mapOf("type" to "string")))
            .putAdditionalProperty(clarifyField, JsonValue.from(mapOf("type" to "boolean")))
            .build()
        val schema = Tool.InputSchema.builder()
            .type(JsonValue.from("object"))
            .properties(properties)
            .required(listOf(messageField, outputField, clarifyField))
            .build()
        return Tool.builder()
            .name(TOOL_NAME)
            .description("Return the post-processing result as structured fields.")
            .inputSchema(schema)
            .build()
    }

    private companion object {
        const val TOOL_NAME = "emit_result"
    }
}
