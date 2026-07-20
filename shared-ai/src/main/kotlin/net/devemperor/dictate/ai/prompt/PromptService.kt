package net.devemperor.dictate.ai.prompt

import net.devemperor.dictate.ai.port.PromptConfig

/**
 * Builds the user/system prompt pairs for each pipeline context. The prompt
 * selection settings come from the [PromptConfig] port (Android adapter:
 * `AndroidPromptConfig` over SharedPreferences); the predefined punctuation
 * table lives in [PromptTemplates].
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §6 A3.5
 */
class PromptService(
    private val config: PromptConfig,
    private val systemPromptResolver: SystemPromptResolver
) {

    /** Ergebnis: userPrompt + systemPrompt, direkt an AIOrchestrator.complete() uebergebbar. */
    data class PromptPair(
        val userPrompt: String,
        val systemPrompt: String?
    )

    // ── Kontext 1: Whisper Style Prompt ──
    // Kein XML-Builder noetig (Whisper-Parameter, kein Chat-Message)

    fun resolveWhisperStylePrompt(languageCode: String?): String? {
        return when (config.stylePromptMode()) {
            PromptMode.NONE -> null
            PromptMode.PREDEFINED -> PromptTemplates.getPunctuationPromptForLanguage(languageCode)
            PromptMode.CUSTOM -> config.stylePromptCustomText().ifEmpty { null }
        }
    }

    // ── Kontext 3: Rewording (User waehlt Prompt aus Liste) ──

    fun buildRewording(promptInstruction: String?, selectedText: String?): PromptPair {
        val builder = PromptBuilder.create()
            .instruction(promptInstruction ?: "")
        if (!selectedText.isNullOrEmpty()) {
            builder.selectedText(selectedText)
        }
        return PromptPair(builder.build(), systemPromptResolver.resolve(PromptContext.REWORDING))
    }

    // ── Kontext 4: Live/Instant Prompt ──

    fun buildLivePrompt(transcribedText: String): PromptPair {
        val userPrompt = PromptBuilder.create()
            .userRequest(transcribedText)
            .build()
        return PromptPair(userPrompt, systemPromptResolver.resolve(PromptContext.LIVE))
    }

    // ── Kontext 5: Queued Prompt (Ketten-Schritt) ──

    fun buildQueuedPrompt(promptInstruction: String, textToProcess: String?): PromptPair {
        val builder = PromptBuilder.create()
            .instruction(promptInstruction)
        if (!textToProcess.isNullOrEmpty()) {
            builder.section("text-to-process", textToProcess)
        }
        return PromptPair(builder.build(), systemPromptResolver.resolve(PromptContext.QUEUED))
    }

    // Static "[text]" pills are no longer recognised by string format — the pill
    // kind is an explicit PromptType.TEXT column since schema v11. Text pills are
    // inserted pipeline-free by the service (see DictateInputMethodService.insertTextPill).

    companion object {
        @JvmStatic
        fun create(config: PromptConfig) = PromptService(config, SystemPromptResolver.create(config))
    }
}
