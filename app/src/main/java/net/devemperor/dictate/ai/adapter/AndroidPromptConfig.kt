package net.devemperor.dictate.ai.adapter

import android.content.SharedPreferences
import net.devemperor.dictate.ai.port.PromptConfig
import net.devemperor.dictate.ai.prompt.PromptMode
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get

/**
 * SharedPreferences-backed [PromptConfig] for `PromptService` /
 * `SystemPromptResolver`. Pure delegation to the style/system prompt selection
 * and custom-text prefs (spec §6 A3.5).
 */
class AndroidPromptConfig(private val sp: SharedPreferences) : PromptConfig {
    override fun stylePromptMode(): PromptMode = PromptMode.fromValue(sp.get(Pref.StylePromptSelection))
    override fun stylePromptCustomText(): String = sp.get(Pref.StylePromptCustomText)
    override fun systemPromptMode(): PromptMode = PromptMode.fromValue(sp.get(Pref.SystemPromptSelection))
    override fun systemPromptCustomText(): String = sp.get(Pref.SystemPromptCustomText)
}
