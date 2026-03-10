package net.devemperor.dictate.ai.runner

import android.content.SharedPreferences
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.errors.BadRequestException
import com.openai.errors.InternalServerException
import com.openai.errors.NotFoundException
import com.openai.errors.RateLimitException
import com.openai.errors.UnauthorizedException
import com.openai.models.audio.AudioResponseFormat
import com.openai.models.audio.transcriptions.TranscriptionCreateParams
import com.openai.models.chat.completions.ChatCompletionCreateParams
import net.devemperor.dictate.DictateUtils
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.ai.AIProviderException.ErrorType
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
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
    private val sp: SharedPreferences,
    private val timeoutSeconds: Long = 120
) : TranscriptionRunner, CompletionRunner {

    private fun buildClient(): OpenAIClient {
        val builder = OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .maxRetries(3)

        if (sp.get(Pref.ProxyEnabled)) {
            val proxyHost = sp.get(Pref.ProxyHost)
            if (DictateUtils.isValidProxy(proxyHost)) {
                DictateUtils.applyProxy(builder, sp)
            }
        }
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
            val audioDuration = DictateUtils.getAudioDuration(options.audioFile)

            TranscriptionResult(
                text = transcription.text().trim(),
                audioDurationSeconds = audioDuration,
                modelName = options.model
            )
        }
    }

    override fun complete(options: CompletionOptions): CompletionResult {
        val client = buildClient()

        val builder = ChatCompletionCreateParams.builder()
            .addUserMessage(options.prompt)
            .model(options.model)

        // Forward dynamic parameters from ParameterRegistry
        options.systemPrompt?.let { if (it.isNotEmpty()) builder.addSystemMessage(it) }
        options.parameters.forEach { (key, value) ->
            when (key) {
                "temperature" -> builder.temperature((value as Number).toDouble())
                "max_completion_tokens" -> builder.maxCompletionTokens((value as Number).toLong())
                "top_p" -> builder.topP((value as Number).toDouble())
                "frequency_penalty" -> builder.frequencyPenalty((value as Number).toDouble())
                "presence_penalty" -> builder.presencePenalty((value as Number).toDouble())
                "reasoning_effort" -> builder.putAdditionalBodyProperty("reasoning_effort", JsonValue.from(value))
            }
        }

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
}
