package net.devemperor.dictate.ai.factory

import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.port.AiConfig
import net.devemperor.dictate.ai.port.AudioDurationReader
import net.devemperor.dictate.ai.port.ProxyConfig
import net.devemperor.dictate.ai.runner.AnthropicCompletionRunner
import net.devemperor.dictate.ai.runner.CompletionRunner
import net.devemperor.dictate.ai.runner.ElevenLabsTranscriptionRunner
import net.devemperor.dictate.ai.runner.OpenAICompatibleRunner
import net.devemperor.dictate.ai.runner.TranscriptionRunner

/**
 * Builds the runner for the active provider from the resolved [AiConfig]. The
 * provider/model/key/baseUrl selection that used to read SharedPreferences here
 * now lives behind [AiConfig] (Android adapter: `AndroidAiConfig`); this factory
 * only wires the selected values into a runner plus the [ProxyConfig] /
 * [AudioDurationReader] ports.
 *
 * `open` (class + the four public entry points) is a production-owned test
 * seam (K-1 convention — no mocking framework): tests subclass this factory
 * to install fake runners / fixed provider+model, which makes the whole
 * AIOrchestrator → PipelineOrchestrator chain drivable without network. See
 * PipelineOrchestratorRegenerationTest.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §4.5, §6 A3.3
 */
open class RunnerFactory(
    private val config: AiConfig,
    private val proxy: ProxyConfig,
    private val audioDuration: AudioDurationReader
) {

    open fun createTranscriptionRunner(): TranscriptionRunner {
        val provider = getProvider(AIFunction.TRANSCRIPTION)
        require(provider.supportsTranscription) {
            "${provider.displayName} does not support transcription"
        }
        return if (provider == AIProvider.ELEVENLABS) {
            ElevenLabsTranscriptionRunner(
                apiKey = config.apiKey(AIFunction.TRANSCRIPTION),
                proxy = proxy,
                audioDuration = audioDuration
            )
        } else {
            createOpenAICompatibleRunner(provider, AIFunction.TRANSCRIPTION)
        }
    }

    open fun createCompletionRunner(): CompletionRunner {
        val provider = getProvider(AIFunction.COMPLETION)
        return if (provider == AIProvider.ANTHROPIC) {
            AnthropicCompletionRunner(
                apiKey = config.apiKey(AIFunction.COMPLETION),
                proxy = proxy
            )
        } else {
            createOpenAICompatibleRunner(provider, AIFunction.COMPLETION)
        }
    }

    open fun getProvider(function: AIFunction): AIProvider = config.provider(function)

    open fun getModelName(function: AIFunction): String = config.modelName(function)

    private fun createOpenAICompatibleRunner(
        provider: AIProvider, function: AIFunction
    ): OpenAICompatibleRunner {
        return OpenAICompatibleRunner(
            provider = provider,
            apiKey = config.apiKey(function),
            baseUrl = config.baseUrl(function),
            proxy = proxy,
            audioDuration = audioDuration
        )
    }
}
