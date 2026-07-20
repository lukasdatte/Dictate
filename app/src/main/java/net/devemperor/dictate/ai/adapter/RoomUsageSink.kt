package net.devemperor.dictate.ai.adapter

import net.devemperor.dictate.ai.port.UsageSink
import net.devemperor.dictate.database.dao.UsageDao

/**
 * Room-backed [UsageSink]. Pure delegation to [UsageDao.addUsage] — no added
 * threading, preserving today's synchronous-on-the-caller's-background-thread
 * semantics (spec §4.2).
 */
class RoomUsageSink(private val usageDao: UsageDao) : UsageSink {
    override fun addUsage(
        modelName: String,
        audioDurationSeconds: Long,
        promptTokens: Long,
        completionTokens: Long,
        providerName: String,
    ) = usageDao.addUsage(modelName, audioDurationSeconds, promptTokens, completionTokens, providerName)
}
