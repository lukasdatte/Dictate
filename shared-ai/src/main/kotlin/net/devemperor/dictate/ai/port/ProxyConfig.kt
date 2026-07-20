package net.devemperor.dictate.ai.port

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import java.net.Proxy

/**
 * Applies the user's proxy configuration to an SDK/okhttp client. Android backs
 * it with DictateUtils (SharedPreferences-driven); the Companion with its own
 * settings. A no-op when no valid proxy is configured — reproduces today's
 * `if (ProxyEnabled && isValidProxy(host))` guard.
 *
 * Note: the openai/anthropic builder types come from the SDKs, which are
 * `:shared-ai` dependencies — allowed here (unlike in `:shared`).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §4.3
 */
interface ProxyConfig {
    fun applyTo(builder: OpenAIOkHttpClient.Builder)
    fun applyTo(builder: AnthropicOkHttpClient.Builder)

    /**
     * For the raw-okhttp ElevenLabs runner: the resolved Proxy or null, plus
     * installing the process-wide Authenticator (today's createProxy +
     * applyProxyAuthenticator pair).
     */
    fun rawProxy(): Proxy?
    fun installAuthenticator()
}
