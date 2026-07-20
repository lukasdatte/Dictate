package net.devemperor.dictate.ai.runner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.ai.AIProviderException.ErrorType
import net.devemperor.dictate.ai.port.AudioDurationReader
import net.devemperor.dictate.ai.port.ProxyConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runner for ElevenLabs Scribe speech-to-text API.
 * Only transcription — no completion support.
 *
 * API: POST https://api.elevenlabs.io/v1/speech-to-text
 * Auth: xi-api-key header (not Bearer token).
 * Models: scribe_v1, scribe_v2 (fixed enum, no model listing endpoint).
 */
class ElevenLabsTranscriptionRunner(
    private val apiKey: String,
    private val proxy: ProxyConfig,
    private val audioDuration: AudioDurationReader
) : TranscriptionRunner {

    /**
     * Builds the okhttp client, wiring the proxy only when one is resolved.
     *
     * Extracted as `internal` (like [buildMultipartBody]) so the proxy path can
     * be unit-tested without a live API call: the "no proxy → no authenticator"
     * vs. "proxy → authenticator installed" branch is where proxy handling
     * regresses silently. See ElevenLabsTranscriptionRunnerTest.
     */
    internal fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)

        val resolvedProxy = proxy.rawProxy()
        if (resolvedProxy != null) {
            builder.proxy(resolvedProxy)
            proxy.installAuthenticator()
        }

        return builder.build()
    }

    private inline fun <R> wrapProviderCall(
        modelName: String? = null,
        block: () -> R
    ): R {
        try {
            return block()
        } catch (e: ElevenLabsApiException) {
            // Parse response body for specific error status (e.g. quota_exceeded on HTTP 401)
            val bodyStatus = try {
                val detail = Json.parseToJsonElement(e.message ?: "").jsonObject["detail"]
                (detail?.jsonObject?.get("status") as? JsonPrimitive)?.contentOrNull
            } catch (_: Exception) { null }

            throw when {
                bodyStatus == "quota_exceeded" -> AIProviderException(ErrorType.RATE_LIMITED, e.message ?: "", e)
                e.statusCode == 401 -> AIProviderException(ErrorType.INVALID_API_KEY, e.message ?: "", e)
                e.statusCode in listOf(402, 403) -> AIProviderException(ErrorType.RATE_LIMITED, e.message ?: "", e)
                e.statusCode == 422 -> AIProviderException(ErrorType.BAD_REQUEST, e.message ?: "", e)
                e.statusCode == 429 -> AIProviderException(ErrorType.RATE_LIMITED, e.message ?: "", e)
                e.statusCode == 404 -> AIProviderException(ErrorType.MODEL_NOT_FOUND, e.message ?: "", e, modelName = modelName)
                e.statusCode == 400 -> AIProviderException(ErrorType.BAD_REQUEST, e.message ?: "", e)
                e.statusCode in 500..599 -> AIProviderException(ErrorType.SERVER_ERROR, e.message ?: "", e)
                else -> AIProviderException(ErrorType.UNKNOWN, e.message ?: "", e)
            }
        } catch (e: IOException) {
            throw AIProviderException(ErrorType.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    override fun transcribe(options: TranscriptionOptions): TranscriptionResult {
        val client = buildClient()

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/speech-to-text")
            .header("xi-api-key", apiKey)
            .post(buildMultipartBody(options))
            .build()

        return wrapProviderCall(modelName = options.model) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    throw ElevenLabsApiException(response.code, errorBody)
                }

                val responseBody = response.body?.string()
                    ?: throw ElevenLabsApiException(500, "Empty response body")

                val json = Json.parseToJsonElement(responseBody).jsonObject
                val text = (json["text"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                val duration = audioDuration.durationSeconds(options.audioFile)

                TranscriptionResult(
                    text = text,
                    audioDurationSeconds = duration,
                    modelName = options.model
                )
            }
        }
    }

    /**
     * Builds the multipart body for the Scribe speech-to-text request.
     *
     * Extracted from [transcribe] so the exact wire format can be unit-tested
     * without a live API call (the request-body encoding is where provider
     * contracts break silently).
     */
    internal fun buildMultipartBody(options: TranscriptionOptions): MultipartBody {
        val fileBody = options.audioFile.asRequestBody("audio/mp4".toMediaType())

        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", options.audioFile.name, fileBody)
            .addFormDataPart("model_id", options.model)

        options.language?.let {
            if (it != "detect") multipartBuilder.addFormDataPart("language_code", it)
        }

        options.temperature?.let {
            multipartBuilder.addFormDataPart("temperature", it.toString())
        }

        if (options.model == "scribe_v2") {
            // `keyterms` is a List[str] on the ElevenLabs API and must be sent as
            // one repeated form-data part per term (keyterms=Alpha, keyterms=Beta,
            // …), the same way the official ElevenLabs SDK serializes it. Encoding
            // the whole list as a single JSON-array string makes the endpoint treat
            // it as one keyterm, which then trips the per-term 50-char limit and is
            // rejected with HTTP 422. See ElevenLabsTranscriptionRunnerTest.
            options.keyterms?.takeIf { it.isNotEmpty() }?.forEach { term ->
                multipartBuilder.addFormDataPart("keyterms", term)
            }
        }

        return multipartBuilder.build()
    }

    private class ElevenLabsApiException(
        val statusCode: Int,
        message: String
    ) : RuntimeException(message)
}
