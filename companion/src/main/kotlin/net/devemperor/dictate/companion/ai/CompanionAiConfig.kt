package net.devemperor.dictate.companion.ai

import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.port.AiConfig

/**
 * The **transitional** [AiConfig] the desktop pipeline runs against in D1/D2 (desktop-host.md §5.1
 * NOTE): a fixed OpenAI-compatible default, no key yet.
 *
 * D3 replaces this with the resolved Block-C profile (`ProfileResolver` → `AiConfig`,
 * entitaetenmodell-android.md §9): provider, model, per-provider key from the SecretStore, and the
 * ambiguity/prompt configuration. Until then a desktop take can be *driven end to end with a fake
 * runner* (the headless E2E, §12) — a **real** provider call only succeeds once D3 wires a key, at
 * which point [apiKey] returns something non-empty. Returning `""` here is the honest state: the
 * pipeline is complete, the credentials are not.
 *
 * Keys are deliberately **not** read from the plaintext settings table (secrets policy) — the key
 * home is the SecretStore, wired in D3.
 */
class CompanionAiConfig : AiConfig {

    override fun provider(function: AIFunction): AIProvider = AIProvider.OPENAI

    override fun modelName(function: AIFunction): String = when (function) {
        AIFunction.TRANSCRIPTION -> DEFAULT_TRANSCRIPTION_MODEL
        AIFunction.COMPLETION -> DEFAULT_COMPLETION_MODEL
    }

    /** No credential until D3 wires the SecretStore-backed profile key. */
    override fun apiKey(function: AIFunction): String = ""

    override fun baseUrl(function: AIFunction): String = provider(function).defaultBaseUrl

    override fun completionParameters(provider: AIProvider, model: String): Map<String, Any> = emptyMap()

    override fun elevenLabsKeyterms(): List<String>? = null

    companion object {
        const val DEFAULT_TRANSCRIPTION_MODEL = "whisper-1"
        const val DEFAULT_COMPLETION_MODEL = "gpt-4o-mini"
    }
}
