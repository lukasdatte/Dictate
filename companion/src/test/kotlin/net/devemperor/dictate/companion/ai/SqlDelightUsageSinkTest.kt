package net.devemperor.dictate.companion.ai

import net.devemperor.dictate.companion.data.CompanionDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The increment-upsert contract of [SqlDelightUsageSink] (desktop-host.md §5.4), the desktop twin of
 * the Android `RoomUsageSink` → `UsageDao.addUsage`. Guards the finding that all desktop AI usage was
 * being discarded by `NoopUsageSink`: a real sink now persists it, and a second call for the same
 * model must accumulate the counters (not overwrite) while leaving the provider untouched.
 */
class SqlDelightUsageSinkTest {

    private val database = CompanionDatabase.inMemory()
    private val sink = SqlDelightUsageSink(database)
    private val queries = database.companionQueries

    @Test
    fun addUsage_insertsARowOnFirstCall() {
        sink.addUsage("whisper-1", audioDurationSeconds = 12, promptTokens = 0, completionTokens = 0, providerName = "OPENAI")

        val row = queries.usageByModel("whisper-1").executeAsOne()
        assertEquals(12L, row.audio_time)
        assertEquals(0L, row.input_tokens)
        assertEquals("OPENAI", row.model_provider)
    }

    @Test
    fun addUsage_incrementsCountersForTheSameModel() {
        sink.addUsage("gpt-4o-mini", audioDurationSeconds = 0, promptTokens = 10, completionTokens = 20, providerName = "OPENAI")
        sink.addUsage("gpt-4o-mini", audioDurationSeconds = 0, promptTokens = 5, completionTokens = 7, providerName = "OPENAI")

        val row = queries.usageByModel("gpt-4o-mini").executeAsOne()
        assertEquals("counters accumulate, not overwrite", 15L, row.input_tokens)
        assertEquals(27L, row.output_tokens)
    }

    @Test
    fun addUsage_leavesModelProviderUntouchedOnConflict() {
        sink.addUsage("m", audioDurationSeconds = 0, promptTokens = 1, completionTokens = 1, providerName = "OPENAI")
        // A second call with a different provider name must NOT rewrite model_provider (it is absent
        // from the DO UPDATE list) — parity with UsageDao.addUsage.
        sink.addUsage("m", audioDurationSeconds = 0, promptTokens = 1, completionTokens = 1, providerName = "GROQ")

        val row = queries.usageByModel("m").executeAsOne()
        assertEquals("OPENAI", row.model_provider)
        assertEquals(2L, row.input_tokens)
    }
}
