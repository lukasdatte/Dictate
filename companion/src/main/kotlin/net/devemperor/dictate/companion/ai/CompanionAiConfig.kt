package net.devemperor.dictate.companion.ai

import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.port.AiConfig

/**
 * A **fixed** OpenAI-compatible [AiConfig] with no credential ([apiKey] returns `""`).
 *
 * Production no longer uses this: `CompanionContainer.production` wires [ProfileBackedAiConfig], which
 * resolves provider/model/key/params from the active Block-C profile + the SecretStore (§9). This
 * class is retained only as the **fixed-config test baseline** for the headless pipeline E2E
 * (`DesktopDictationPipelineTest`), where a fake runner is driven and the real credential resolution
 * is deliberately out of scope — the desktop counterpart of Android keeping `AndroidAiConfig` in test
 * sources. Keys are never read from the plaintext settings table (secrets policy); the key home is the
 * SecretStore.
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
