package net.devemperor.dictate.core

import java.util.concurrent.CancellationException

/**
 * Cooperative cancellation token for pipeline jobs.
 *
 * The [PipelineOrchestrator] checks [throwIfCancelled] at defined checkpoints:
 * - Before each pipeline step
 * - After each API call returns
 *
 * [Thread.interrupt] remains as a last-resort fallback for blocking I/O
 * (e.g. OkHttp calls that don't return). The interrupt is sent AFTER
 * [cancel] sets the flag, not INSTEAD of it.
 */
class CancellationToken {
    @Volatile
    var isCancelled = false
        private set

    fun cancel() {
        isCancelled = true
    }

    fun throwIfCancelled() {
        if (isCancelled) throw CancellationException("Job cancelled via CancellationToken")
    }
}
