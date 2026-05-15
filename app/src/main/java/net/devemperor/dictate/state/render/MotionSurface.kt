package net.devemperor.dictate.state.render

import androidx.constraintlayout.motion.widget.MotionLayout

/**
 * The minimal `MotionLayout` API that [ImeViewBackend] needs.
 *
 * # Why an abstraction?
 *
 * `MotionLayout` is a final-ish Android view class — it cannot be
 * instantiated under plain JVM unit tests without Robolectric (K-4
 * violation). The backend's render path issues exactly two kinds of
 * scene commands (`jumpToState` for the first-render / animations-off
 * case, and `transitionToState` for the animated case), so wrapping
 * those two methods behind an interface keeps the backend testable
 * **and** the production binding trivially thin.
 *
 * # Two implementations
 *
 * - [RealMotionSurface] — production wrapper around a real
 *   `MotionLayout`. Constructed by the IME service in C15.
 * - Hand-rolled `FakeMotionSurface` in tests — records the requested
 *   transitions so [ImeViewBackend] tests can assert on first-render +
 *   transition behaviour without spinning up the Android view system.
 *
 * # Why not a function-pair (no interface)?
 *
 * A two-method interface is more SOLID than two separate function-type
 * parameters: the two operations co-evolve (`jumpToState` is the
 * "skip-animation" partner of `transitionToState`), so keeping them
 * grouped under one type matches the cohesion already present in
 * `MotionLayout`. It also lets us add a future Phase-2 query (e.g.
 * "what's the current state-id?") without breaking signatures.
 *
 * @see net.devemperor.dictate.state.render.ImeViewBackend
 */
interface MotionSurface {
    /**
     * Snap to [stateId] without an animation. Used on the first render
     * after view inflation (Spec 2 §6 R.14 `firstRender`-flag) and when
     * the user has animations disabled.
     */
    fun jumpToState(stateId: Int)

    /**
     * Animate to [stateId] using whichever transition is declared in the
     * MotionScene for `(currentState, stateId)`. If no transition is
     * declared the surface behaves the same as [jumpToState].
     */
    fun transitionToState(stateId: Int)
}

/**
 * Production [MotionSurface] backed by a real `MotionLayout`.
 *
 * Trivially-thin wrapper; the indirection exists only so unit tests
 * can substitute a hand-rolled fake.
 */
class RealMotionSurface(private val motionLayout: MotionLayout) : MotionSurface {
    override fun jumpToState(stateId: Int) {
        motionLayout.jumpToState(stateId)
    }

    override fun transitionToState(stateId: Int) {
        motionLayout.transitionToState(stateId)
    }
}
