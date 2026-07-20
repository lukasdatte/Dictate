package net.devemperor.dictate.companion.ai

import net.devemperor.dictate.ai.port.UsageSink

/**
 * A transitional [UsageSink] that discards usage rows (desktop-host.md §5.4).
 *
 * The spec's persistent `usage` table + `SqlDelightUsageSink` is deferred to D3: adding a table is a
 * schema change that needs its own `.sqm` migration + `databases/N.db` snapshot (verifyMigrations),
 * and D3 already opens a migration for the Companion entity tables (D5.b, `3.sqm`). Folding the
 * `usage` table into that migration avoids a second migration number in D1b and a snapshot churn on a
 * table nothing reads yet — D1b's acceptance (headless dictation persistence, §2 crit. 5) does not
 * cover usage accounting. See the D1b chunk report for the delegated follow-up.
 */
object NoopUsageSink : UsageSink {
    override fun addUsage(
        modelName: String,
        audioDurationSeconds: Long,
        promptTokens: Long,
        completionTokens: Long,
        providerName: String,
    ) = Unit
}
