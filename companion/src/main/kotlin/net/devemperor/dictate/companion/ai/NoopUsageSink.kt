package net.devemperor.dictate.companion.ai

import net.devemperor.dictate.ai.port.UsageSink

/**
 * A [UsageSink] that discards usage rows — the intentional no-usage double for headless tests.
 *
 * Production persists usage through [SqlDelightUsageSink] (the `usage` table, desktop-host.md §5.4).
 * This sink is the deliberate opposite: `DesktopDictationPipelineTest` and any other headless E2E wire
 * it so a fixture run does not have to assert on (or seed) accounting rows it does not care about. It
 * is a live test collaborator, not dead code — keep it.
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
