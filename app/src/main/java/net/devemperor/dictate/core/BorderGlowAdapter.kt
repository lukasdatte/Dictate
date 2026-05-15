package net.devemperor.dictate.core

import net.devemperor.dictate.state.BorderGlowSubsystem
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production [BorderGlowSubsystem] — placeholder Phase-1 adapter.
 *
 * **C8 — subsystem-adapter migration:** today the keyboard-border glow
 * animation is driven by [RecordingUiController] (see
 * `applyActiveState` / `applyIdleState`) reading
 * [RecordingState] transitions from [RecordingStateController].
 *
 * The orchestrator-side equivalent (a state-derived Boolean that
 * `dictateKeyboardView`'s border drawable observes) belongs to B5
 * (LayoutCatalog + RecordingAnimationController, Spec 2 §4.x).
 * For C8 this adapter is the interface seam — modules call
 * `start()` / `stop()` from `runEffect` but no animation is actually
 * mutated yet. The internal flag is observable for tests.
 *
 * Same rationale as [AmplitudeStreamAdapter] for keeping the adapter
 * present-but-silent: clean logcat, testable wiring, and a stable
 * binding contract for B5 to fill in.
 *
 * @see net.devemperor.dictate.state.BorderGlowSubsystem
 */
class BorderGlowAdapter : BorderGlowSubsystem {

    private val running = AtomicBoolean(false)

    /** Test-visible flag — `true` between [start] and [stop]. */
    val isRunning: Boolean
        get() = running.get()

    override fun start() {
        running.set(true)
    }

    override fun stop() {
        running.set(false)
    }
}
