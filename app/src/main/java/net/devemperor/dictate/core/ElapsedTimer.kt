package net.devemperor.dictate.core

import android.os.Handler
import android.os.SystemClock

/**
 * A fire-and-forget elapsed timer that ticks on the given [Handler].
 *
 * Created via [start] factory — the instance is running from the moment of creation.
 * [stop] is idempotent and returns the total elapsed time in milliseconds.
 * After [stop], the instance should be discarded.
 *
 * Uses [Handler.postDelayed] with a token so [stop] only removes its own callbacks.
 */
class ElapsedTimer private constructor(
    private val handler: Handler,
    private val onTick: (elapsedMs: Long) -> Unit,
    private val intervalMs: Long
) {
    private val token = Any()
    private val startTime = SystemClock.elapsedRealtime()
    private var stopped = false

    companion object {
        /**
         * Creates and immediately starts a new timer.
         *
         * @param handler the Handler to post tick callbacks on (typically main thread)
         * @param intervalMs tick interval in milliseconds (default 100ms)
         * @param onTick called on each tick with elapsed milliseconds since start
         * @return a running [ElapsedTimer] — call [stop] when done
         */
        fun start(
            handler: Handler,
            intervalMs: Long = 100L,
            onTick: (elapsedMs: Long) -> Unit
        ): ElapsedTimer {
            return ElapsedTimer(handler, onTick, intervalMs).also { it.scheduleNext() }
        }
    }

    val isRunning: Boolean get() = !stopped

    /**
     * Stops the timer and removes pending callbacks.
     * Idempotent — safe to call multiple times.
     *
     * @return total elapsed time in milliseconds
     */
    fun stop(): Long {
        if (stopped) return SystemClock.elapsedRealtime() - startTime
        stopped = true
        handler.removeCallbacksAndMessages(token)
        return SystemClock.elapsedRealtime() - startTime
    }

    private fun scheduleNext() {
        if (stopped) return
        handler.postDelayed({
            if (!stopped) {
                onTick(SystemClock.elapsedRealtime() - startTime)
                scheduleNext()
            }
        }, token, intervalMs)
    }
}

/** Formats elapsed milliseconds as compact duration string, e.g. "4.2s". */
fun formatElapsedCompact(ms: Long): String =
    String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
