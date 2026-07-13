package net.devemperor.dictate.shared.transport

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The real transport, on blocking OkHttp — the house pattern of `ElevenLabsTranscriptionRunner`.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015); OkHttp runs on both.
 *
 * **No proxy support**, deliberately and unlike the AI runners: the PC sits in the tailnet, an
 * HTTP proxy in front of it makes no sense and would only be a misconfiguration trap (ADR-0017).
 */
class OkHttpDispatchTransport(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) : DispatchTransport {

    /** Trailing slashes are stripped so `baseUrl + Endpoints.DISPATCH` never yields `//v1/…`. */
    private val baseUrl: String = baseUrl.trimEnd('/')

    override fun post(path: String, body: String, headers: Map<String, String>): HttpResponseLite {
        val request = Request.Builder()
            .url(baseUrl + path)
            .post(body.toRequestBody(JSON))
            .applyHeaders(headers)
            .build()
        return client.newCall(request).execute().use { response ->
            // `string()` reads the body to its end — a connection that dies mid-body throws
            // IOException here, which the client classifies as Unreachable. It must never be
            // possible to hand a truncated body upwards and have it parse as a delivery.
            HttpResponseLite(status = response.code, body = response.body?.string().orEmpty())
        }
    }

    override fun get(path: String, headers: Map<String, String>): HttpResponseLite {
        val request = Request.Builder()
            .url(baseUrl + path)
            .get()
            .applyHeaders(headers)
            .build()
        return client.newCall(request).execute().use { response ->
            HttpResponseLite(status = response.code, body = response.body?.string().orEmpty())
        }
    }

    private fun Request.Builder.applyHeaders(headers: Map<String, String>): Request.Builder =
        apply { headers.forEach { (name, value) -> header(name, value) } }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // Deliberately short. A dispatch sits on the critical path of finishing a dictation:
            // an unreachable PC must fall into the pending-part fallback FAST, not hold the
            // keyboard for 30 seconds while the user waits for their text.
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            // Retrying is the user's call, via the history row's send button — an automatic retry
            // would double the time we hold the text hostage before offering it as a pending part.
            .retryOnConnectionFailure(false)
            .build()
    }
}
