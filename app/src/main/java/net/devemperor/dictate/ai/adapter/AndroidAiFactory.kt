package net.devemperor.dictate.ai.adapter

import android.content.Context
import android.content.SharedPreferences
import net.devemperor.dictate.ai.AIOrchestrator
import net.devemperor.dictate.ai.factory.RunnerFactory
import net.devemperor.dictate.ai.prompt.PromptService
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.dao.UsageDao
import net.devemperor.dictate.secrets.AndroidKeystoreSecretStore

/**
 * Single Android wiring point for the `:shared-ai` AI stack: constructs the
 * port adapters and threads them into [AIOrchestrator] / [PromptService].
 *
 * C3 flip (spec §10 / §9): the live read path is the entity-based
 * [ProfileResolver] (active profile + SecretStore) and [ProfilePromptConfig] —
 * flipped together with the settings WRITE paths so reads and writes moved
 * atomically off the migrated prefs. The pref-based [AndroidAiConfig] remains
 * only as the migration's parameter mirror + characterization-test baseline.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §4.5
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §9
 */
object AndroidAiFactory {

    @JvmStatic
    fun androidOrchestrator(context: Context, sp: SharedPreferences, usageDao: UsageDao): AIOrchestrator {
        val config = ProfileResolver(
            sp,
            DictateDatabase.getInstance(context),
            AndroidKeystoreSecretStore.create(context),
        )
        val proxy = SharedPrefsProxyConfig(sp)
        val audioDuration = MediaMetadataAudioDurationReader()
        return AIOrchestrator(config, RoomUsageSink(usageDao), RunnerFactory(config, proxy, audioDuration))
    }

    @JvmStatic
    fun androidPromptService(context: Context, sp: SharedPreferences): PromptService =
        PromptService.create(ProfilePromptConfig(sp, DictateDatabase.getInstance(context)))
}
