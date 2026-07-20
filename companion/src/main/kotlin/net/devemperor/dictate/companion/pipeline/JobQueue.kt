package net.devemperor.dictate.companion.pipeline

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A serial, FIFO run queue: one job at a time, in submission order (desktop-host.md §5.6,
 * ADR-0009). A second dictation triggered while a pipeline runs *reihen ein* — recording order equals
 * insert order — rather than running in parallel (desktop v1 has a single recording surface).
 *
 * [submit] dedups by [sessionId]: a re-submitted session (a double-dispatched callback) is ignored,
 * so the same take can never run its pipeline twice.
 */
interface JobQueue {

    /** Enqueues [job] under [sessionId]; a no-op if that session is already pending or running. */
    fun submit(sessionId: String, job: () -> Unit)

    /** Stops the worker; in-flight work is allowed to finish but no further jobs run. */
    fun shutdown()
}

/**
 * Production [JobQueue] on a single daemon worker thread. The dedup set tracks a session from submit
 * until its job returns, so both the queue and the in-flight job count toward "already pending".
 */
class SerialJobQueue : JobQueue {

    private data class Entry(val sessionId: String, val job: () -> Unit)

    private val pending = ConcurrentLinkedQueue<Entry>()
    private val known = ConcurrentHashMap.newKeySet<String>()
    private val draining = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "desktop-job-queue").apply { isDaemon = true }
    }

    override fun submit(sessionId: String, job: () -> Unit) {
        if (!known.add(sessionId)) return // already pending or running — dedup (§5.6)
        pending.add(Entry(sessionId, job))
        drain()
    }

    private fun drain() {
        if (!draining.compareAndSet(false, true)) return
        worker.execute {
            try {
                while (true) {
                    val entry = pending.poll() ?: break
                    try {
                        entry.job()
                    } finally {
                        known.remove(entry.sessionId)
                    }
                }
            } finally {
                draining.set(false)
                // A job submitted between the poll-miss and clearing the flag must still run.
                if (pending.isNotEmpty()) drain()
            }
        }
    }

    override fun shutdown() {
        worker.shutdown()
    }
}

/**
 * An inline [JobQueue] that runs each job on the calling thread, in submission order. Lets the headless
 * pipeline E2E drive the whole dictation flow deterministically without a worker thread to await
 * (spec §12).
 */
class InlineJobQueue : JobQueue {
    override fun submit(sessionId: String, job: () -> Unit) = job()
    override fun shutdown() = Unit
}
