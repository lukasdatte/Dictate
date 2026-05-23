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
    // Mirrors of the persisted state. Mutations sync back to the
    // companion-object fields (which survive Observer recreation on
    // IME-View rotation / theme change) — see the IME-rotation fix
    // KDoc at the top of the class.
    private var startedAtMs: Long = persistedStartedAtMs
    private var accumulatedElapsedMs: Long = persistedAccumulatedElapsedMs
    private var currentSessionId: String? = persistedSessionId
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
     *
     * **Lifecycle-vs-state distinction**: this method tears down the
     * Observer's own coroutine + handler resources. It does NOT clear
     * the persisted companion-state — that wipe only happens when the
     * recording itself reaches [RecordingState.Idle] (see
     * [stopTicker]). Rotation tears down the Observer mid-recording
     * without changing the state, so leaving the companion intact lets
     * the next constructor restore the timer correctly. Clearing the
     * companion here was a regression in the original B-rotation fix
     * (2026-05-22 commit 7c305aa) — fixed in the same-day follow-up
     * once the on-device test showed the timer still reset.
     */
    fun stop() {
        scope?.cancel()
        scope = null
        // Tear down the local tick loop without touching the companion-
        // persisted state (so a subsequent Observer construction can
        // restore from it).
        ticking = false
        handler.removeCallbacks(tickRunnable)
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
            // Recovery-surfaced interrupted recording (2026-05-22):
            // freeze the ticker at the known segment-sum duration so
            // the user sees the true elapsed time (their "0:08").
            is RecordingState.Interrupted -> freezeTickerAt(rs.sessionId, rs.elapsedMs)
        }
    }

    private fun startOrContinueTicker(sessionId: String) {
        if (currentSessionId != sessionId) {
            // Two cases produce this branch:
            //
            //   (a) Truly new recording session — reset anchor AND
            //       accumulator so the timer restarts at 0.
            //   (b) **IME-View rotation while a recording is in
            //       flight** — this observer was just instantiated
            //       in onCreateInputView(); `currentSessionId` is
            //       whatever survived from the persisted-companion
            //       restore. If that restore matched the live
            //       session, we'd hit the `else if` branch instead.
            //       But on a *cold* observer with no prior persist
            //       (e.g. service-process-restart while recording
            //       was active in a survived FGS) we land here with
            //       persistedStartedAtMs == -1L, which makes case (b)
            //       indistinguishable from case (a). Cold observer
            //       == fresh timer is the correct fallback (timer
            //       resync from "now" is the least confusing UX vs.
            //       random pre-restart elapsed time).
            startedAtMs = SystemClock.elapsedRealtime()
            accumulatedElapsedMs = 0L
            currentSessionId = sessionId
        } else if (startedAtMs < 0L) {
            // Resume from Paused (same sessionId, but the anchor was
            // invalidated by `freezeTicker`). Re-stamp the anchor so the
            // tick continues; `accumulatedElapsedMs` carries the value
            // from the previous Active interval.
            //
            // **Rotation case**: when persistedStartedAtMs was -1L
            // (e.g. last Paused → freezeTicker) but persistedSessionId
            // matches the live recording, we land here and correctly
            // re-stamp the anchor — equivalent to a Pause → Resume.
            startedAtMs = SystemClock.elapsedRealtime()
        }
        // **Rotation case (same sessionId, startedAtMs ≥ 0L)**: the
        // companion restore lined up perfectly with the live state.
        // No mutation needed — the new Observer instance is already
        // in sync with the recording in progress, and the tick will
        // resume from `accumulatedElapsedMs + (now - startedAtMs)`.
        syncToCompanion()
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
        syncToCompanion()
        // Emit one final tick on the frozen value so the UI sits on the
        // exact stopped time (no last-tick race where the rendered
        // number is one TICK_INTERVAL_MS short of the freeze point).
        onTimerTick(accumulatedElapsedMs)
    }

    /**
     * Freeze the ticker at a known elapsed value — used when a
     * recovery-surfaced [RecordingState.Interrupted] recording appears
     * (2026-05-22). Unlike [freezeTicker] (which folds the *live*
     * Active interval into the accumulator), this seeds the accumulator
     * directly with [elapsedMs] — the sum of the already-recorded
     * segments' durations — so the UI shows the recording's true
     * progress (the user's "0:08") even though no Active interval ever
     * ran in this process.
     *
     * The [sessionId] is the interrupted session. Because
     * `StartRecordingContinuation` reuses that same id, the subsequent
     * `Interrupted → Preparing → Active` transition lands in
     * [startOrContinueTicker]'s "resume from Paused" branch (same
     * sessionId, `startedAtMs < 0`) — the seeded accumulator carries
     * forward and the timer continues from [elapsedMs] instead of
     * restarting at zero.
     */
    private fun freezeTickerAt(sessionId: String, elapsedMs: Long) {
        currentSessionId = sessionId
        accumulatedElapsedMs = elapsedMs
        startedAtMs = -1L
        ticking = false
        handler.removeCallbacks(tickRunnable)
        syncToCompanion()
        // Emit one tick on the seeded value so the UI sits on 0:08.
        onTimerTick(elapsedMs)
    }

    private fun stopTicker() {
        ticking = false
        handler.removeCallbacks(tickRunnable)
        startedAtMs = -1L
        accumulatedElapsedMs = 0L
        currentSessionId = null
        // Recording reached Idle — clear the persisted companion so
        // the NEXT recording session starts fresh. This is the only
        // path that clears the companion; [stop] (Observer lifecycle
        // boundary) does NOT clear it so a rotation-driven re-attach
        // can restore the state.
        clearPersistedState()
    }

    /**
     * Sync the live instance fields back into the companion-object
     * "persistent" slots. Called after every mutation so a subsequent
     * Observer recreation (rotation, theme change, IME-View tear-down)
     * can restore the timer state in its constructor.
     */
    private fun syncToCompanion() {
        persistedStartedAtMs = startedAtMs
        persistedAccumulatedElapsedMs = accumulatedElapsedMs
        persistedSessionId = currentSessionId
    }

    companion object {
        const val TICK_INTERVAL_MS: Long = 100L

        // ── Cross-Observer-instance persistence (rotation survival) ──
        //
        // The IME re-instantiates [RecordingActivityTickerObserver] on
        // every `onCreateInputView`. Without these fields the new
        // observer's `currentSessionId` is null and any live
        // `state.recording=Active(...)` emission appears as a "fresh
        // session", resetting the visible timer to 0:00 mid-recording
        // (verified on-device 2026-05-22 — rotation while recording).
        //
        // The persisted state is cleared exactly when the recording
        // reaches Idle (see [stopTicker]); Observer-lifecycle stops
        // (rotation tear-down, IME unbind) do NOT clear it so the
        // companion-restore can re-anchor on the next observer
        // construction.
        //
        // Threading: writes happen on the main thread (the Observer's
        // dispatch is `Dispatchers.Main` + the `handler` is a main-
        // looper handler). Volatile keeps cross-thread visibility for
        // any defensive reader (none today, but cheap insurance).
        @Volatile private var persistedStartedAtMs: Long = -1L
        @Volatile private var persistedAccumulatedElapsedMs: Long = 0L
        @Volatile private var persistedSessionId: String? = null

        /**
         * Reset the persisted timer-state to its initial values.
         * Called by [stopTicker] when the recording reaches `Idle`
         * — at that point the next recording is a genuinely new
         * session and the timer must start at 0.
         *
         * **Test seam:** exposed `internal` so unit tests can wipe
         * the companion between tests (state survives across test
         * cases otherwise, which is a real test-isolation foot-gun).
         */
        @JvmStatic
        internal fun clearPersistedState() {
            persistedStartedAtMs = -1L
            persistedAccumulatedElapsedMs = 0L
            persistedSessionId = null
        }
    }
}
