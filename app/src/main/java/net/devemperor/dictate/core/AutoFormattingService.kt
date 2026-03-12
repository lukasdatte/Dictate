package net.devemperor.dictate.core

import android.content.SharedPreferences
import android.text.TextUtils
import android.util.Log
import net.devemperor.dictate.ai.AIOrchestrator
import net.devemperor.dictate.ai.prompt.PromptBuilder
import net.devemperor.dictate.ai.prompt.PromptTemplates
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get

class AutoFormattingService(
    private val sp: SharedPreferences,
    private val aiOrchestrator: AIOrchestrator
) {
    fun isEnabled(): Boolean =
        sp.get(Pref.AutoFormattingEnabled) && sp.get(Pref.RewordingEnabled)

    fun formatIfEnabled(transcript: String, languageHint: String?): FormatResult {
        if (TextUtils.isEmpty(transcript)
            || !sp.get(Pref.AutoFormattingEnabled)
            || !sp.get(Pref.RewordingEnabled)) {
            return FormatResult(transcript, null, null) // disabled
        }

        return try {
            val systemPrompt = PromptBuilder.create()
                .instruction(PromptTemplates.AUTO_FORMATTING_SYSTEM)
                .rules(PromptTemplates.AUTO_FORMATTING_RULES)
                .examples(PromptTemplates.AUTO_FORMATTING_EXAMPLES)
                .build()

            val userPrompt = PromptBuilder.create()
                .languageHint(languageHint)
                .transcript(transcript)
                .build()

            val result = aiOrchestrator.complete(userPrompt, systemPrompt)
            FormatResult(result.text.trim().ifEmpty { transcript }, result, null) // success
        } catch (e: Exception) {
            Log.w("AutoFormattingService", "Auto-formatting failed", e)
            FormatResult(transcript, null, e) // failed — error passed for step creation
        }
    }

    companion object {
        @JvmStatic
        fun create(sp: SharedPreferences, orchestrator: AIOrchestrator) =
            AutoFormattingService(sp, orchestrator)
    }
}
