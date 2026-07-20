package net.devemperor.dictate.ai.port

/**
 * Sink for post-call usage accounting. Android backs it with the Room UsageDao;
 * the Companion with a SQLDelight table. Called AFTER a successful runner call,
 * on the same background thread as today.
 *
 * Threading parity: the Android adapter MUST preserve today's call semantics —
 * UsageDao.addUsage runs synchronously on the caller's background thread
 * (speechApiThread / rewordingApiThread). The adapter adds no threading.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/shared-ai-extraktion.md §4.2
 */
interface UsageSink {
    fun addUsage(
        modelName: String,
        audioDurationSeconds: Long,
        promptTokens: Long,
        completionTokens: Long,
        providerName: String,
    )
}
