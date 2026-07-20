package net.devemperor.dictate.ai.adapter

import android.content.SharedPreferences
import net.devemperor.dictate.ai.AIOrchestrator
import net.devemperor.dictate.ai.factory.RunnerFactory
import net.devemperor.dictate.ai.prompt.PromptService
import net.devemperor.dictate.database.dao.UsageDao

/**
 * Single Android wiring point for the `:shared-ai` AI stack: constructs the
 * port adapters (SharedPreferences + Room) and threads them into
 * [AIOrchestrator] / [PromptService]. Replaces the former convenience
 * constructors `AIOrchestrator(sp, usageDao)` and `PromptService.create(sp)`.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §4.5
 */
object AndroidAiFactory {

    @JvmStatic
    fun androidOrchestrator(sp: SharedPreferences, usageDao: UsageDao): AIOrchestrator {
        val config = AndroidAiConfig(sp)
        val proxy = SharedPrefsProxyConfig(sp)
        val audioDuration = MediaMetadataAudioDurationReader()
        return AIOrchestrator(config, RoomUsageSink(usageDao), RunnerFactory(config, proxy, audioDuration))
    }

    @JvmStatic
    fun androidPromptService(sp: SharedPreferences): PromptService =
        PromptService.create(AndroidPromptConfig(sp))
}
