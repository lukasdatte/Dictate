package net.devemperor.dictate.ai.prompt

import android.content.SharedPreferences
import net.devemperor.dictate.DictateUtils
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get

class PromptService(
    private val sp: SharedPreferences,
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
        return when (PromptMode.fromValue(sp.get(Pref.StylePromptSelection))) {
            PromptMode.NONE -> null
            PromptMode.PREDEFINED -> DictateUtils.getPunctuationPromptForLanguage(languageCode)
            PromptMode.CUSTOM -> sp.get(Pref.StylePromptCustomText).ifEmpty { null }
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

    // ── Kontext 6: Static Response [text] ──

    fun isStaticResponse(prompt: String?): Boolean =
        prompt != null && prompt.startsWith("[") && prompt.endsWith("]")

    fun extractStaticResponse(prompt: String): String =
        prompt.substring(1, prompt.length - 1)

    companion object {
        @JvmStatic
        fun create(sp: SharedPreferences) = PromptService(sp, SystemPromptResolver(sp))
    }
}
