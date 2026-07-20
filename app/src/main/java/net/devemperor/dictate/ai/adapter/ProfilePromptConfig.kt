package net.devemperor.dictate.ai.adapter

import android.content.SharedPreferences
import net.devemperor.dictate.ai.port.PromptConfig
import net.devemperor.dictate.ai.prompt.PromptMode
import net.devemperor.dictate.config.ActiveProfile
import net.devemperor.dictate.config.ConfigWireMapping.toPromptMode
import net.devemperor.dictate.database.DictateDatabase

/**
 * Entity-backed [PromptConfig]: style/system prompt selection now lives on the ACTIVE profile
 * (spec §4.7), not in prefs — the C3 successor of the pref-based [AndroidPromptConfig].
 *
 * Fallback (no active profile, §9.3 spirit): PREDEFINED + empty custom text — the same behaviour
 * the pref path produced on a fresh install (int pref default 1 → [PromptMode.PREDEFINED]).
 */
class ProfilePromptConfig(
    private val sp: SharedPreferences,
    private val db: DictateDatabase,
) : PromptConfig {

    override fun stylePromptMode(): PromptMode =
        ActiveProfile.get(sp, db)?.stylePromptMode?.toPromptMode() ?: PromptMode.PREDEFINED

    override fun stylePromptCustomText(): String =
        ActiveProfile.get(sp, db)?.stylePromptCustomText.orEmpty()

    override fun systemPromptMode(): PromptMode =
        ActiveProfile.get(sp, db)?.systemPromptMode?.toPromptMode() ?: PromptMode.PREDEFINED

    override fun systemPromptCustomText(): String =
        ActiveProfile.get(sp, db)?.systemPromptCustomText.orEmpty()
}
