package net.devemperor.dictate.ai.testutil

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import net.devemperor.dictate.ai.port.AudioDurationReader
import net.devemperor.dictate.ai.port.ProxyConfig
import java.io.File
import java.net.Proxy

/**
 * No-op [ProxyConfig] for `:shared-ai` unit tests: applies nothing and reports
 * no proxy. Records whether it was consulted so proxy-path tests can assert the
 * runner honoured the "no proxy configured" case.
 */
class FakeProxyConfig(
    private val proxy: Proxy? = null
) : ProxyConfig {
    var installAuthenticatorCalls = 0
        private set

    override fun applyTo(builder: OpenAIOkHttpClient.Builder) = Unit
    override fun applyTo(builder: AnthropicOkHttpClient.Builder) = Unit
    override fun rawProxy(): Proxy? = proxy
    override fun installAuthenticator() { installAuthenticatorCalls++ }
}

/** Fixed-value [AudioDurationReader] for `:shared-ai` unit tests. */
class FakeAudioDurationReader(private val seconds: Long = -1) : AudioDurationReader {
    override fun durationSeconds(file: File): Long = seconds
}
