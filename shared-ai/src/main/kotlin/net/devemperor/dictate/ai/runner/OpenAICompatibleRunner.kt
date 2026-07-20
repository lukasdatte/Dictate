package net.devemperor.dictate.ai.runner

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.errors.BadRequestException
import com.openai.errors.InternalServerException
import com.openai.errors.NotFoundException
import com.openai.errors.RateLimitException
import com.openai.errors.UnauthorizedException
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.audio.AudioResponseFormat
import com.openai.models.audio.transcriptions.TranscriptionCreateParams
import com.openai.models.chat.completions.ChatCompletionCreateParams
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.ai.AIProviderException.ErrorType
import net.devemperor.dictate.ai.conversation.StructuredResponseCodec
import net.devemperor.dictate.ai.port.AudioDurationReader
import net.devemperor.dictate.ai.port.ProxyConfig
import net.devemperor.dictate.database.entity.MessageRole
import net.devemperor.dictate.database.entity.ResponseFormatKind
import java.io.IOException
import java.time.Duration

/**
 * Runner for all OpenAI-API-compatible providers.
 * Covers OpenAI, Groq, OpenRouter, and Custom (differ only in base URL and API key).
 *
 * Retry: SDK-internal auto-retry via .maxRetries(3) – exponential backoff for 408, 429, 5xx.
 * Exceptions: Typed SDK exceptions are mapped to AIProviderException.ErrorType.
 */
class OpenAICompatibleRunner(
    private val provider: AIProvider,
    private val apiKey: String,
    private val baseUrl: String,
    private val proxy: ProxyConfig,
    private val audioDuration: AudioDurationReader,
    private val timeoutSeconds: Long = 120
) : TranscriptionRunner, CompletionRunner {

    private fun buildClient(): OpenAIClient {
        val builder = OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .maxRetries(3)

        proxy.applyTo(builder)
        return builder.build()
    }

    /**
     * DRY: Central exception mapping for all OpenAI-compatible API calls.
     * Avoids 3x identical 12-line try/catch blocks.
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

    override fun transcribe(options: TranscriptionOptions): TranscriptionResult {
        val client = buildClient()

        val paramsBuilder = TranscriptionCreateParams.builder()
            .file(options.audioFile.toPath())
            .model(options.model)
            .responseFormat(AudioResponseFormat.JSON)

        options.language?.let { if (it != "detect") paramsBuilder.language(it) }
        options.stylePrompt?.let { if (it.isNotEmpty()) paramsBuilder.prompt(it) }

        return wrapProviderCall(modelName = options.model) {
            val transcription = client.audio().transcriptions()
                .create(paramsBuilder.build()).asTranscription()
            val duration = audioDuration.durationSeconds(options.audioFile)

            TranscriptionResult(
                text = transcription.text().trim(),
                audioDurationSeconds = duration,
                modelName = options.model
            )
        }
    }

    override fun complete(options: CompletionOptions): CompletionResult {
        val client = buildClient()

        val builder = ChatCompletionCreateParams.builder()
            .addUserMessage(options.prompt)
            .model(options.model)

        options.systemPrompt?.let { if (it.isNotEmpty()) builder.addSystemMessage(it) }
        applyParameters(builder, options.parameters)

        return wrapProviderCall(modelName = options.model) {
            val chatCompletion = client.chat().completions().create(builder.build())

            val usage = chatCompletion.usage().orElse(null)
            val promptTokens = usage?.promptTokens() ?: 0L
            val completionTokens = usage?.completionTokens() ?: 0L

            CompletionResult(
                text = chatCompletion.choices()[0].message().content().orElse(""),
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                modelName = options.model
            )
        }
    }

    override fun converse(request: ConversationRequest): ConversationResult {
        val client = buildClient()

        // First attempt: native structured output via response_format=json_schema.
        try {
            val params = buildChatParams(request, withSchema = true, appendFallbackHint = false)
            return wrapProviderCall(modelName = request.model) {
                val chat = client.chat().completions().create(params)
                toConversationResult(chat, request.model, ResponseFormatKind.JSON_SCHEMA)
            }
        } catch (e: AIProviderException) {
            // Only heterogeneous endpoints (CUSTOM / OpenRouter) may reject
            // response_format; for first-party providers a 400 is a real error.
            if (e.errorType != ErrorType.BAD_REQUEST || !provider.allowsStructuredOutputTextFallback) {
                throw e
            }
        }

        // Fallback: plain text + schema instruction, lenient-parsed.
        val fallbackParams = buildChatParams(request, withSchema = false, appendFallbackHint = true)
        return wrapProviderCall(modelName = request.model) {
            val chat = client.chat().completions().create(fallbackParams)
            toConversationResult(chat, request.model, ResponseFormatKind.TEXT_FALLBACK)
        }
    }

    private fun buildChatParams(
        request: ConversationRequest,
        withSchema: Boolean,
        appendFallbackHint: Boolean
    ): ChatCompletionCreateParams {
        val builder = ChatCompletionCreateParams.builder().model(request.model)

        var system = request.systemPrompt?.takeIf { it.isNotEmpty() }
        if (appendFallbackHint) {
            val hint = StructuredResponseCodec.fallbackInstruction()
            system = if (system == null) hint else "$system\n\n$hint"
        }
        system?.let { builder.addSystemMessage(it) }

        for (message in request.messages) {
            when (message.role) {
                MessageRole.USER -> builder.addUserMessage(message.content)
                MessageRole.ASSISTANT -> builder.addAssistantMessage(message.content)
                MessageRole.SYSTEM -> builder.addSystemMessage(message.content)
            }
        }

        applyParameters(builder, request.parameters)
        if (withSchema) builder.responseFormat(structuredResponseFormat())
        return builder.build()
    }

    private fun toConversationResult(
        chat: com.openai.models.chat.completions.ChatCompletion,
        model: String,
        format: ResponseFormatKind
    ): ConversationResult {
        val usage = chat.usage().orElse(null)
        // G2-2: reject a response the model cut off at max_tokens before parsing
        // — the lenient parser would otherwise return the raw, half-written JSON
        // as the output and insert it verbatim.
        StructuredOutputGuards.requireNotTruncated(
            chat.choices().firstOrNull()?.finishReason() ==
                com.openai.models.chat.completions.ChatCompletion.Choice.FinishReason.LENGTH,
            provider
        )
        val content = chat.choices()[0].message().content().orElse("")
        val parsed = StructuredResponseCodec.parseLenient(content)
        return ConversationResult(
            message = parsed.message,
            output = parsed.output,
            promptTokens = usage?.promptTokens() ?: 0L,
            completionTokens = usage?.completionTokens() ?: 0L,
            modelName = model,
            responseFormat = format,
            needsClarification = parsed.needsClarification
        )
    }

    /** Shared parameter mapping — used by both [complete] and [converse]. */
    private fun applyParameters(
        builder: ChatCompletionCreateParams.Builder,
        parameters: Map<String, Any>
    ) {
        parameters.forEach { (key, value) ->
            when (key) {
                "temperature" -> builder.temperature((value as Number).toDouble())
                "max_completion_tokens" -> builder.maxCompletionTokens((value as Number).toLong())
                "top_p" -> builder.topP((value as Number).toDouble())
                "frequency_penalty" -> builder.frequencyPenalty((value as Number).toDouble())
                "presence_penalty" -> builder.presencePenalty((value as Number).toDouble())
                "reasoning_effort" -> builder.putAdditionalBodyProperty("reasoning_effort", JsonValue.from(value))
            }
        }
    }

    /** The fixed `{message, output, needsClarification}` json_schema response format (ADR-0012/0013). */
    private fun structuredResponseFormat(): ResponseFormatJsonSchema {
        val (messageField, outputField) = StructuredResponseCodec.fieldNames
        val clarifyField = StructuredResponseCodec.needsClarificationField
        val schema = ResponseFormatJsonSchema.JsonSchema.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty(
                "properties",
                JsonValue.from(
                    mapOf(
                        messageField to mapOf("type" to "string"),
                        outputField to mapOf("type" to "string"),
                        clarifyField to mapOf("type" to "boolean")
                    )
                )
            )
            .putAdditionalProperty("required", JsonValue.from(listOf(messageField, outputField, clarifyField)))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()
        val jsonSchema = ResponseFormatJsonSchema.JsonSchema.builder()
            .name("post_processing_result")
            .strict(true)
            .schema(schema)
            .build()
        return ResponseFormatJsonSchema.builder().jsonSchema(jsonSchema).build()
    }
}
