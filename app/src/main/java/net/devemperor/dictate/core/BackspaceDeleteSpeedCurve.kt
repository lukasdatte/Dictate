package net.devemperor.dictate.core

/**
 * Pure-Kotlin speed curve for the backspace long-press accelerated
 * delete cascade (B-C, plan §5.5 Variante B).
 *
 * The legacy cascade in `DictateInputMethodService.onBackspaceLongClicked()`
 * thresholds the per-character delete delay against the time elapsed
 * since the long-press started:
 *
 * ```
 *  elapsed  ≤ 1500 ms → 50 ms (initial)
 *  > 1500 ms          → 25 ms
 *  > 3000 ms          → 10 ms
 *  > 5000 ms          →  5 ms
 * ```
 *
 * Lifting this curve into a pure function lets a JVM-only test pin the
 * exact thresholds + step sizes without spinning a Handler/Looper. The
 * `Handler.postDelayed` loop, the `vibrate()` side-effect, and the
 * `isDeleting` cancellation gate stay in the IME service (they have no
 * regression-risk a unit test could shake out — they are either wired
 * or they are not, and the architecture-invariant tests already lock
 * the wiring).
 *
 * The function is **monotone non-increasing** by design: callers pass
 * their `currentDeleteDelay` and only step *down* when the next
 * threshold has been crossed. The IME caller relies on this to gate
 * the `vibrate()` haptic — feedback fires exactly when the delay
 * actually changes ([StepTransition.advanced]).
 *
 * @see net.devemperor.dictate.core.DictateInputMethodService#onBackspaceLongClicked()
 */
object BackspaceDeleteSpeedCurve {

    /** Initial per-character delete delay (ms) — first ≤ 1.5 s of hold. */
    const val INITIAL_DELAY_MS: Int = 50

    /** Delay (ms) after the first acceleration step at > 1.5 s. */
    const val FAST_DELAY_MS: Int = 25

    /** Delay (ms) after the second acceleration step at > 3.0 s. */
    const val FASTER_DELAY_MS: Int = 10

    /** Delay (ms) after the final acceleration step at > 5.0 s. */
    const val FASTEST_DELAY_MS: Int = 5

    /** Threshold (ms) between [INITIAL_DELAY_MS] and [FAST_DELAY_MS]. */
    const val FAST_THRESHOLD_MS: Long = 1500L

    /** Threshold (ms) between [FAST_DELAY_MS] and [FASTER_DELAY_MS]. */
    const val FASTER_THRESHOLD_MS: Long = 3000L

    /** Threshold (ms) between [FASTER_DELAY_MS] and [FASTEST_DELAY_MS]. */
    const val FASTEST_THRESHOLD_MS: Long = 5000L

    /**
     * Outcome of one cascade tick. [nextDelayMs] is what the caller
     * should pass to its next `Handler.postDelayed` call; [advanced] is
     * true iff this tick crossed a threshold (i.e. the delay actually
     * changed) — the IME uses it to gate the `vibrate()` haptic.
     */
    data class StepTransition(val nextDelayMs: Int, val advanced: Boolean)

    /**
     * Compute the next delete delay for one cascade tick.
     *
     * Mirrors the legacy if/else-if/else-if ladder exactly, including
     * the `&& currentDelay == X` clauses — those clauses make each
     * threshold one-shot: an off-by-one tick that lands in the wrong
     * `elapsed` band cannot retro-trigger an earlier step.
     *
     * @param elapsedMs millis since long-press start.
     * @param currentDelayMs the delay currently in use (caller-owned).
     */
    fun nextDelay(elapsedMs: Long, currentDelayMs: Int): StepTransition {
        if (elapsedMs > FASTEST_THRESHOLD_MS && currentDelayMs == FASTER_DELAY_MS) {
            return StepTransition(FASTEST_DELAY_MS, advanced = true)
        }
        if (elapsedMs > FASTER_THRESHOLD_MS && currentDelayMs == FAST_DELAY_MS) {
            return StepTransition(FASTER_DELAY_MS, advanced = true)
        }
        if (elapsedMs > FAST_THRESHOLD_MS && currentDelayMs == INITIAL_DELAY_MS) {
            return StepTransition(FAST_DELAY_MS, advanced = true)
        }
        return StepTransition(currentDelayMs, advanced = false)
    }
}
