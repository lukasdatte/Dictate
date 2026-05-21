package net.devemperor.dictate.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.RecordingState

/**
 * Post-cutover hotfix #3+#4 — drive the IME's recording-animation
 * side-channel (`ImeViewBackend.onTimerTick` + `onAmplitude`, plus
 * `QwertzRecordingController.onTimerTick` + `onAmplitude`) from the
 * orchestrator's `state.recording` transitions.
 *
 * # The cutover gap
 *
 * Pre-cutover the legacy [RecordingManager] ran a 100ms Handler-loop
 * during recording that called `RecordingCallback.onTimerTick(elapsedMs)`
 * and `onAmplitudeUpdate(maxAmplitude)`. The IME's
 * `RecordingStateController.Callback` then forwarded these into the UI
 * controllers. The render-path cutover preserved the *forward* code
 * paths but two upstream links broke:
 *
 *   1. `RecordingStateController.startRecording()` is never called on
 *      the new path (the orchestrator's [RecordingHardwareAdapter] now
 *      owns MediaRecorder directly, without polling). So the legacy
 *      timer loop never starts → no `onTimerTick` callback fires.
 *   2. The orchestrator-side [RecordingTimerAdapter] and
 *      [AmplitudeStreamAdapter] are deliberate no-ops per their own
 *      KDocs (`"Phase-1 silent operation"` / `"placeholder Phase-1
 *      adapter"`) — waiting for "B5 LayoutCatalog" wiring that never
 *      happened.
 *   3. Even if the legacy callback fired, the IME-side wiring at
 *      `DictateInputMethodService.onTimerTick/onAmplitudeUpdate` only
 *      forwards to `qwertzRecordingController`, not to
 *      `imeViewBackend.onTimerTick / onAmplitude`.
 *
 * Visible consequence on device: no timer text on the record button
 * during Active, no waveform / amplitude bars in the recording
 * animation.
 *
 * # What this observer does
 *
 * Subscribes to `state.recording` and runs a 100ms Handler tick on
 * `Dispatchers.Main` while the recording is Active or Paused. Each
 * tick:
 *
 *   - computes `elapsedMs = SystemClock.elapsedRealtime() - startedAtMs`
 *     (resets per sessionId so a fresh recording restarts at 0);
 *   - invokes [onTimerTick] with the elapsed value;
 *   - polls [amplitudePoller] (typically wired to
 *     `LocalBinder.pollRecordingMaxAmplitude`) for the current peak
 *     amplitude and invokes [onAmplitude] if non-null.
 *
 * Both callbacks fire on the main thread (same contract as the legacy
 * `RecordingCallback`). When `state.recording` transitions to Idle the
 * tick stops and the callback chain falls quiet.
 *
 * # Pause semantics
 *
 * **The timer freezes on `Paused` and resumes from the frozen value
 * on `Active`** — matching the pre-cutover `RecordingManager.pause()`/
 * `resume()` semantics (which stopped the Handler-loop on pause, keeping
 * `elapsedTimeMs` static; resume restarted the loop from the same
 * counter). An earlier KDoc claimed the opposite (continuous wall-clock
 * across pauses); that claim was wrong and produced a visible bug — the
 * timer continued advancing visually while the recorder was actually
 * paused.
 *
 * Mechanics:
 *
 *  - [accumulatedElapsedMs] holds the elapsed-ms from previous Active
 *    intervals of the current session. Zero on a fresh session.
 *  - When `Active` is entered with the **same** sessionId (Paused →
 *    Active resume), [startedAtMs] is re-stamped to the current wall
 *    clock; [accumulatedElapsedMs] is preserved.
 *  - When `Active` is entered with a **new** sessionId, both fields
 *    reset.
 *  - When `Paused` is entered, the current interval's elapsed is folded
 *    into [accumulatedElapsedMs], [startedAtMs] is invalidated, the
 *    Handler-loop stops, and a single final `onTimerTick(accumulated)`
 *    is emitted so the UI sits on the frozen value.
 *  - Each tick reports `accumulatedElapsedMs + (now - startedAtMs)`.
 *
 * `RecordingState.Paused` carries no `accumulatedElapsedMs` payload —
 * the accumulator lives here, in the renderer-side observer, because
 * it is a UI-cache concern (single subscriber, no domain-side
 * invariant). If a future caller needs the same accumulation outside
 * this observer the field should be promoted to the FSM and exposed
 * via `Effect.PauseTimer` / `Effect.ResumeTimer` (already dispatched
 * by [net.devemperor.dictate.state.modules.RecordingModule], today
 * consumed only by the still-silent [RecordingTimerAdapter]).
 *
 * # Lifecycle
 *
 * Use [start] / [stop] in symmetry with the IME's
 * `onCreateInputView` / `onDestroyInputView` (or
 * `onServiceConnected` / `onUnbind`). Idempotent — duplicate calls are
 * no-ops.
 *
 * @see InfoBarRenderer — sibling observer pattern for the
 *   `state.overlay.onboardingPending` axis.
 */
class RecordingActivityTickerObserver(
    private val state: StateFlow<DictateUiState>,
    private val onTimerTick: (Long) -> Unit,
    private val onAmplitude: (Int) -> Unit,
    private val amplitudePoller: () -> Int?,
) {

    private var scope: CoroutineScope? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startedAtMs: Long = -1L
    private var accumulatedElapsedMs: Long = 0L
    private var currentSessionId: String? = null
    private var ticking: Boolean = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking || startedAtMs < 0L) return
            val elapsed = accumulatedElapsedMs + (SystemClock.elapsedRealtime() - startedAtMs)
            onTimerTick(elapsed)
            val amp = amplitudePoller()
            if (amp != null) onAmplitude(amp)
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    /**
     * Begin observing. Idempotent — a second call while already running
     * is a no-op.
     */
    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            state
                .map { it.recording }
                .distinctUntilChanged()
                .collect { rs -> handleRecordingStateChange(rs) }
        }
    }

    /**
     * Stop observing and the tick. Idempotent — safe to call on an
     * already-stopped observer.
     */
    fun stop() {
        scope?.cancel()
        scope = null
        stopTicker()
    }

    private fun handleRecordingStateChange(rs: RecordingState) {
        when (rs) {
            is RecordingState.Idle -> stopTicker()
            is RecordingState.Active -> startOrContinueTicker(rs.sessionId)
            is RecordingState.Paused -> freezeTicker(rs.sessionId)
            is RecordingState.Preparing -> {
                // Preparing — wait for Active. Don't start the tick yet
                // (no audio has actually started recording).
            }
        }
    }

    private fun startOrContinueTicker(sessionId: String) {
        if (currentSessionId != sessionId) {
            // New recording session — reset both the timer anchor AND
            // the accumulated counter so the timer restarts at 0.
            startedAtMs = SystemClock.elapsedRealtime()
            accumulatedElapsedMs = 0L
            currentSessionId = sessionId
        } else if (startedAtMs < 0L) {
            // Resume from Paused (same sessionId, but the anchor was
            // invalidated by `freezeTicker`). Re-stamp the anchor so the
            // tick continues; `accumulatedElapsedMs` carries the value
            // from the previous Active interval.
            startedAtMs = SystemClock.elapsedRealtime()
        }
        if (!ticking) {
            ticking = true
            handler.post(tickRunnable)
        }
    }

    private fun freezeTicker(sessionId: String) {
        if (currentSessionId != sessionId) {
            // Defensive: a Paused-with-a-new-sessionId would be a
            // protocol violation (FSM should always have an Active
            // first). Behave as if we're entering a fresh session and
            // immediately freeze at 0.
            currentSessionId = sessionId
            accumulatedElapsedMs = 0L
            startedAtMs = -1L
        } else if (startedAtMs >= 0L) {
            // Fold the current Active interval into the accumulator,
            // then null the anchor so a subsequent tick (or an extra
            // Paused emission) cannot keep advancing the counter.
            accumulatedElapsedMs += SystemClock.elapsedRealtime() - startedAtMs
            startedAtMs = -1L
        }
        ticking = false
        handler.removeCallbacks(tickRunnable)
        // Emit one final tick on the frozen value so the UI sits on the
        // exact stopped time (no last-tick race where the rendered
        // number is one TICK_INTERVAL_MS short of the freeze point).
        onTimerTick(accumulatedElapsedMs)
    }

    private fun stopTicker() {
        ticking = false
        handler.removeCallbacks(tickRunnable)
        startedAtMs = -1L
        accumulatedElapsedMs = 0L
        currentSessionId = null
    }

    private companion object {
        const val TICK_INTERVAL_MS: Long = 100L
    }
}
