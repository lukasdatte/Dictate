package net.devemperor.dictate.ai.adapter

import android.content.SharedPreferences
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import net.devemperor.dictate.DictateUtils
import net.devemperor.dictate.ai.port.ProxyConfig
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import java.net.Proxy

/**
 * SharedPreferences-backed [ProxyConfig], delegating to the (pure-JVM) proxy
 * helpers that remain in `DictateUtils`. Every method reproduces today's guard
 * exactly — a no-op unless `ProxyEnabled && isValidProxy(host)`.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §4.3
 */
class SharedPrefsProxyConfig(private val sp: SharedPreferences) : ProxyConfig {

    override fun applyTo(builder: OpenAIOkHttpClient.Builder) {
        if (isProxyActive()) DictateUtils.applyProxy(builder, sp)
    }

    override fun applyTo(builder: AnthropicOkHttpClient.Builder) {
        if (isProxyActive()) DictateUtils.applyProxyToAnthropic(builder, sp)
    }

    override fun rawProxy(): Proxy? = if (isProxyActive()) DictateUtils.createProxy(sp) else null

    override fun installAuthenticator() = DictateUtils.applyProxyAuthenticator(sp)

    private fun isProxyActive(): Boolean =
        sp.get(Pref.ProxyEnabled) && DictateUtils.isValidProxy(sp.get(Pref.ProxyHost))
}
