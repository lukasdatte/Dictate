package net.devemperor.dictate.companion.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import net.devemperor.dictate.ai.port.ProxyConfig
import java.net.Proxy

/**
 * The companion has no proxy setting in v1, so this [ProxyConfig] is a no-op — the direct-connection
 * path that the Android adapter takes when `ProxyEnabled` is false (shared-ai-extraktion.md §4.3).
 *
 * The SDK builder types are visible here because `:shared-ai` re-exports the OpenAI/Anthropic SDKs via
 * `api` (build.gradle), so no extra companion dependency is needed. A companion proxy setting, if ever
 * wanted, replaces this object without touching the runners.
 */
object CompanionProxyConfig : ProxyConfig {
    override fun applyTo(builder: OpenAIOkHttpClient.Builder) = Unit
    override fun applyTo(builder: AnthropicOkHttpClient.Builder) = Unit
    override fun rawProxy(): Proxy? = null
    override fun installAuthenticator() = Unit
}
