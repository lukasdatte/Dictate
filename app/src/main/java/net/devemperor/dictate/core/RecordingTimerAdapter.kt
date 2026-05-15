package net.devemperor.dictate.core

import android.os.Handler
import android.os.Looper
import net.devemperor.dictate.state.RecordingTimerSubsystem

/**
 * Production [RecordingTimerSubsystem] — a monotonic recording-duration
 * counter driven by an Android [Handler] on the main looper.
 *
 * **C8 — subsystem-adapter migration:** today the recording-duration UI
 * is driven by [RecordingManager]'s internal timer (the
 * `RecordingCallback.onTimerTick` callback feeds
 * [RecordingUiController]). This adapter is the orchestrator-side
 * equivalent that future UI work (B5+ LayoutCatalog) will subscribe to
 * via `state.recording`-derived render predicates.
 *
 * **Why a separate adapter (not the same handler as [RecordingManager]'s
 * timer)?** Two reasons:
 *
 *  1. Encapsulation — the [RecordingHardwareSubsystem] interface does
 *     not include timer methods. Splitting timer/amplitude/border-glow
 *     into their own subsystems matches Spec 1 §4.7 and lets each be
 *     swapped (e.g. for a deterministic test clock) independently.
 *  2. Threading parity — the orchestrator's dispatch loop runs on
 *     `Dispatchers.Main.immediate`. The handler here is bound to the
 *     same looper, so timer ticks land in the same execution context as
 *     the dispatch that started them.
 *
 * **Phase-1 silent operation:** in C8 the IME's [RecordingManager] still
 * drives the user-visible timer text via its own callback. This adapter
 * runs **in parallel** — the orchestrator's [RecordingState.Active]
 * subscribers will see timer ticks via state-derived rendering once
 * future blocks route them. For C8 we keep the adapter functional but
 * silent (no UI mutation) to avoid two competing timer paths during the
 * migration window.
 *
 * @see net.devemperor.dictate.state.RecordingTimerSubsystem
 */
class RecordingTimerAdapter : RecordingTimerSubsystem {

    private val handler: Handler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    @Volatile
    var elapsedMs: Long = 0L
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    override fun start() {
        if (isRunning) return
        elapsedMs = 0L
        scheduleTick()
        isRunning = true
    }

    override fun pause() {
        if (!isRunning) return
        cancelTick()
        isRunning = false
    }

    override fun resume() {
        if (isRunning) return
        scheduleTick()
        isRunning = true
    }

    override fun reset() {
        cancelTick()
        elapsedMs = 0L
        isRunning = false
    }

    private fun scheduleTick() {
        val r = Runnable {
            elapsedMs += TICK_INTERVAL_MS
            scheduleTick()
        }
        tickRunnable = r
        handler.postDelayed(r, TICK_INTERVAL_MS)
    }

    private fun cancelTick() {
        tickRunnable?.let { handler.removeCallbacks(it) }
        tickRunnable = null
    }

    private companion object {
        const val TICK_INTERVAL_MS: Long = 100L
    }
}
