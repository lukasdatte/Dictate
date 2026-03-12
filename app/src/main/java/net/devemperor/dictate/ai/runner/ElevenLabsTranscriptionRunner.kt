package net.devemperor.dictate.ai.runner

import android.content.SharedPreferences
import net.devemperor.dictate.DictateUtils
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.ai.AIProviderException.ErrorType
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
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
    private val sp: SharedPreferences
) : TranscriptionRunner {

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)

        if (sp.get(Pref.ProxyEnabled)) {
            val proxyHost = sp.get(Pref.ProxyHost)
            if (DictateUtils.isValidProxy(proxyHost)) {
                val proxy = DictateUtils.createProxy(sp)
                if (proxy != null) {
                    builder.proxy(proxy)
                    DictateUtils.applyProxyAuthenticator(sp)
                }
            }
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
            throw when (e.statusCode) {
                401 -> AIProviderException(ErrorType.INVALID_API_KEY, e.message ?: "", e)
                429 -> AIProviderException(ErrorType.RATE_LIMITED, e.message ?: "", e)
                404 -> AIProviderException(ErrorType.MODEL_NOT_FOUND, e.message ?: "", e, modelName = modelName)
                400 -> AIProviderException(ErrorType.BAD_REQUEST, e.message ?: "", e)
                in 500..599 -> AIProviderException(ErrorType.SERVER_ERROR, e.message ?: "", e)
                else -> AIProviderException(ErrorType.UNKNOWN, e.message ?: "", e)
            }
        } catch (e: IOException) {
            throw AIProviderException(ErrorType.NETWORK_ERROR, e.message ?: "", e)
        }
    }

    override fun transcribe(options: TranscriptionOptions): TranscriptionResult {
        val client = buildClient()

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

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/speech-to-text")
            .header("xi-api-key", apiKey)
            .post(multipartBuilder.build())
            .build()

        return wrapProviderCall(modelName = options.model) {
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                throw ElevenLabsApiException(response.code, errorBody)
            }

            val responseBody = response.body?.string()
                ?: throw ElevenLabsApiException(500, "Empty response body")

            val json = JSONObject(responseBody)
            val text = json.optString("text", "").trim()
            val audioDuration = DictateUtils.getAudioDuration(options.audioFile)

            TranscriptionResult(
                text = text,
                audioDurationSeconds = audioDuration,
                modelName = options.model
            )
        }
    }

    private class ElevenLabsApiException(
        val statusCode: Int,
        message: String
    ) : RuntimeException(message)
}
