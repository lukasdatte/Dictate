package net.devemperor.dictate.core

import net.devemperor.dictate.state.AmplitudeStreamSubsystem
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production [AmplitudeStreamSubsystem] — placeholder Phase-1 adapter.
 *
 * **C8 — subsystem-adapter migration:** today the live recording UI
 * (pulse animation, amplitude bars) is driven by
 * [RecordingManager]'s `RecordingCallback.onAmplitudeUpdate`, which
 * polls `MediaRecorder.maxAmplitude` every 100 ms and forwards to
 * [RecordingUiController] via [AmplitudeProcessor].
 *
 * The orchestrator-side equivalent (a `Flow<Int>` of amplitude samples
 * for state-derived rendering) belongs to a later block (B5 LayoutCatalog
 * + RecordingAnimationController). For C8 this adapter is the
 * **interface seam** — modules call `start()` / `stop()` from `runEffect`
 * but no sampling thread is created yet. The internal flag is observable
 * for tests.
 *
 * **Why have it at all if it's a no-op today?** Three reasons:
 *
 *  1. Removes the C7 stub's WARN-log noise from production logcat once
 *     the orchestrator starts emitting `StartAmplitudeStream` effects.
 *  2. Makes the C8 module-side wiring testable end-to-end against a
 *     real (non-stub) adapter — the C7 stub was deliberately log-only
 *     to flag "B3 fills this".
 *  3. Lays the binding contract so B5 can swap the no-op body for a
 *     real sampling thread + Flow emission without re-touching
 *     `DictatePipelineService.onCreate`.
 *
 * @see net.devemperor.dictate.state.AmplitudeStreamSubsystem
 * @see net.devemperor.dictate.core.AmplitudeProcessor
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §4.7 §15.x
 */
class AmplitudeStreamAdapter : AmplitudeStreamSubsystem {

    private val running = AtomicBoolean(false)

    /**
     * Test-visible flag for assertions. `true` between [start] and the
     * next [stop]; idempotent on duplicate calls.
     */
    val isRunning: Boolean
        get() = running.get()

    override fun start() {
        running.set(true)
    }

    override fun stop() {
        running.set(false)
    }
}
