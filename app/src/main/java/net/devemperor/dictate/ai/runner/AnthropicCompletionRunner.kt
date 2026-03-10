package net.devemperor.dictate.ai.runner

import android.content.SharedPreferences
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import net.devemperor.dictate.DictateUtils
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.ai.AIProviderException.ErrorType
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

        // Forward dynamic Anthropic parameters
        // Runtime validation: temperature XOR top_p (Anthropic API throws 400 if both set)
        val hasTemperature = options.parameters.containsKey("temperature")
        // If both temperature and top_p are set (UI should prevent this via mutuallyExclusiveWith
        // in ParameterRegistry), temperature takes priority and top_p is ignored.
        options.parameters.forEach { (key, value) ->
            when (key) {
                "temperature" -> paramsBuilder.temperature((value as Number).toDouble())
                "top_p" -> if (!hasTemperature) paramsBuilder.topP((value as Number).toDouble())
                "top_k" -> paramsBuilder.topK((value as Number).toLong())
                "max_tokens" -> {} // Already handled above
            }
        }

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
}
