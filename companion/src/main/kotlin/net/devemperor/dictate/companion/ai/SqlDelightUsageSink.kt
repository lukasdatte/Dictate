package net.devemperor.dictate.companion.ai

import net.devemperor.dictate.ai.port.UsageSink
import net.devemperor.dictate.companion.db.DictateCompanionDb

/**
 * SQLDelight-backed [UsageSink] — the desktop twin of the Android `RoomUsageSink`.
 *
 * Pure delegation to the generated `addUsage` increment-upsert, no added threading: it runs
 * synchronously on the caller's background thread, exactly as the port contract requires (the same
 * semantics `RoomUsageSink` preserves on Android). Written after every successful desktop
 * transcription/completion by [net.devemperor.dictate.ai.AIOrchestrator]; the `usage` table it feeds
 * is a companion-only accounting table (desktop-host.md §5.4), not part of the Room parity set.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §4.2
 */
class SqlDelightUsageSink(database: DictateCompanionDb) : UsageSink {

    private val queries = database.companionQueries

    override fun addUsage(
        modelName: String,
        audioDurationSeconds: Long,
        promptTokens: Long,
        completionTokens: Long,
        providerName: String,
    ) {
        queries.addUsage(modelName, audioDurationSeconds, promptTokens, completionTokens, providerName)
    }
}
