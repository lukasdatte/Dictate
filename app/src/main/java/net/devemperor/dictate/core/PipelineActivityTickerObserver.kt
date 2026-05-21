package net.devemperor.dictate.core

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.PipelineUiState

/**
 * Thin scheduler abstraction over the production `Handler.postDelayed`
 * / `removeCallbacks` pair.
 *
 * Modelled on [PauseTimeoutScheduler] for the same reason: the
 * `Handler` class methods are `final` so a Kotlin/Java test cannot
 * subclass and override them, and `Handler` instantiation requires a
 * Looper (K-4 forbids touching Android in unit tests). Tests pass an
 * in-memory fake that records scheduled `Runnable`s and lets the test
 * drive them deterministically.
 */
interface TickerScheduler {
    fun postDelayed(r: Runnable, delayMs: Long)
    fun removeCallbacks(r: Runnable)
}

/** Production [TickerScheduler] backed by a real [Handler]. */
internal class HandlerTickerScheduler(private val handler: Handler) : TickerScheduler {
    override fun postDelayed(r: Runnable, delayMs: Long) {
        handler.postDelayed(r, delayMs)
    }
    override fun removeCallbacks(r: Runnable) {
        handler.removeCallbacks(r)
    }
}

/**
 * Per-second pipeline-timer ticker — dispatches
 * [Action.PipelineAction.TickPipelineTimer] every
 * [TICK_INTERVAL_MS] ms while the pipeline is in
 * [PipelineUiState.Running] so the record-button label's `M:SS`
 * timer visibly advances between step boundaries.
 *
 * # The B-D-3 gap
 *
 * Pre-fix, `state.pipeline.Running.elapsedMs` was only re-stamped by
 * the [net.devemperor.dictate.state.modules.PipelineModule] reducer
 * arms for `StepStarted` / `StepCompleted` / `StepFailed` — step
 * boundaries only. A long step (transcription of 30 s audio = several
 * seconds, reword step = several seconds) meant the timer froze for
 * the whole step duration. The user saw a static "1/2 0:01" while
 * waiting for the next step boundary.
 *
 * # What this observer does
 *
 * Sibling to [RecordingActivityTickerObserver] but on the pipeline
 * axis instead of recording. Subscribes to `state.pipeline` and
 * runs a 1000 ms `Handler.postDelayed` loop while the phase is
 * `Running`. Each tick dispatches
 * [Action.PipelineAction.TickPipelineTimer]; the
 * [net.devemperor.dictate.state.modules.PipelineModule] reducer
 * re-stamps `elapsedMs = ctx.now - startedAtMs`. The label
 * resolver (`resolveRecordButtonTextPipeline`) reads the updated
 * `elapsedMs` on the next render and the button label advances by
 * one second.
 *
 * # Why 1000 ms (not 100 ms like Recording)?
 *
 * The pipeline timer is rendered as `M:SS` (second granularity) —
 * a 100 ms tick would be 10 reducer dispatches per second with
 * zero visible delta on 9 out of 10 ticks. 1000 ms = exactly one
 * dispatch per visible-second-change. Plan §9.4 OQ-4 decision.
 *
 * # Lifecycle + race safety
 *
 * - [start] / [stop] symmetric to the IME's `onCreateInputView` /
 *   `onDestroy`. Idempotent — duplicate calls are no-ops.
 * - The collector uses `distinctUntilChanged` on the pipeline phase
 *   class so a single Running entry starts exactly one Handler
 *   loop; Running→Running transitions (e.g. step boundaries that
 *   restamp `elapsedMs`) do not re-trigger.
 * - When the phase leaves `Running` the Handler loop stops; a
 *   late-arriving `TickPipelineTimer` from a race window is
 *   absorbed by the reducer's `else -> null` arm (idempotent
 *   action contract).
 *
 * @see RecordingActivityTickerObserver — sibling per-100ms ticker on
 *   `state.recording`.
 * @see net.devemperor.dictate.state.Action.PipelineAction.TickPipelineTimer
 * @see docs/plans/2026-05-21 - dictate-pipeline-render-and-state-unification/dictate-pipeline-render-and-state-unification.md §5.2
 */
class PipelineActivityTickerObserver @JvmOverloads constructor(
    private val state: StateFlow<DictateUiState>,
    private val onTick: () -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val scheduler: TickerScheduler =
        HandlerTickerScheduler(Handler(Looper.getMainLooper())),
) {

    private var scope: CoroutineScope? = null
    private var ticking: Boolean = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking) return
            onTick()
            scheduler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    /** Begin observing. Idempotent — a second call while running is a no-op. */
    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + mainDispatcher)
        scope = s
        s.launch {
            state
                .map { it.pipeline::class }
                .distinctUntilChanged()
                .collect { _ -> handlePipelinePhaseChange(state.value.pipeline) }
        }
    }

    /** Stop observing. Idempotent. */
    fun stop() {
        scope?.cancel()
        scope = null
        stopTicker()
    }

    private fun handlePipelinePhaseChange(phase: PipelineUiState) {
        when (phase) {
            is PipelineUiState.Running -> startTicker()
            else -> stopTicker()
        }
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        // First tick after one interval so the very-first frame of
        // Running shows the StartPipeline-stamped `elapsedMs` (0)
        // for ~1 s before the first restamp — feels natural.
        scheduler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
    }

    private fun stopTicker() {
        ticking = false
        scheduler.removeCallbacks(tickRunnable)
    }

    private companion object {
        /** Plan §9.4 OQ-4 decision: 1 s (second-granular `M:SS` label). */
        const val TICK_INTERVAL_MS: Long = 1000L
    }
}
