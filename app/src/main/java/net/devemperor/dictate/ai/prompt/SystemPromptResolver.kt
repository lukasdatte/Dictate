package net.devemperor.dictate.ai.prompt

import android.content.SharedPreferences
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get

/**
 * Resolves the system prompt based on user settings and prompt context.
 * When "Predefined" is selected, returns the context-specific system prompt
 * instead of a generic one — so Rewording, Live and Queued each get
 * tailored instructions.
 */
class SystemPromptResolver(private val sp: SharedPreferences) {

    fun resolve(context: PromptContext): String? {
        val raw = when (PromptMode.fromValue(sp.get(Pref.SystemPromptSelection))) {
            PromptMode.NONE -> ""
            PromptMode.PREDEFINED -> when (context) {
                PromptContext.REWORDING -> PromptTemplates.SYSTEM_PROMPT_REWORDING
                PromptContext.LIVE -> PromptTemplates.SYSTEM_PROMPT_LIVE
                PromptContext.QUEUED -> PromptTemplates.SYSTEM_PROMPT_QUEUED
            }
            PromptMode.CUSTOM -> sp.get(Pref.SystemPromptCustomText)
        }
        return raw.ifEmpty { null }
    }

    companion object {
        @JvmStatic
        fun create(sp: SharedPreferences) = SystemPromptResolver(sp)
    }
}
