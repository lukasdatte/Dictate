package net.devemperor.dictate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-C / B-C behaviour test for the backspace long-press accelerated
 * delete cascade (Plan §7.1, fix-wave G2).
 *
 * # Why a unit test on [BackspaceDeleteSpeedCurve], not a Robolectric
 * Handler/Looper test
 *
 * The cascade has two layers:
 *
 * 1. **Wiring** — that `onBackspaceLongClicked()` is invoked from the
 *    IME affordance lambda's `BACKSPACE && isLongPress` branch, and
 *    that the long-click resolver in `ImeViewBackend` includes
 *    BACKSPACE. Both are already locked structurally in
 *    [net.devemperor.dictate.core.CutoverArchitectureInvariantTest]
 *    (`backspaceLongPressAffordanceWiredInImeLambda` +
 *    `imeViewBackendLongClickBranchIncludesBackspace`).
 *
 * 2. **Curve correctness** — the threshold-based 50→25→10→5 ms
 *    transitions. The legacy implementation inlined the thresholds
 *    in a `Handler.postDelayed` loop inside `onBackspaceLongClicked()`,
 *    which is impossible to JVM-test without ShadowLooper /
 *    Robolectric. Fix-wave G2 lifts the curve into the pure-Kotlin
 *    [BackspaceDeleteSpeedCurve] helper; this test pins its
 *    behaviour. The IME's Handler-loop is now a thin caller — the
 *    invariant above guarantees it stays wired.
 *
 * The combination (structural locker + behaviour test on the helper)
 * matches Plan §7.1's intent: catch regressions like "someone
 * simplifies the loop to a fixed 50 ms" exactly, without coupling the
 * test to the Handler-loop mechanics.
 *
 * # Curve under test
 *
 * ```
 *  elapsed  ≤ 1500 ms  → 50 ms (initial)
 *  > 1500 ms           → 25 ms (vibrate)
 *  > 3000 ms           → 10 ms (vibrate)
 *  > 5000 ms           →  5 ms (vibrate)
 * ```
 *
 * @see net.devemperor.dictate.core.BackspaceDeleteSpeedCurve
 * @see net.devemperor.dictate.core.DictateInputMethodService.onBackspaceLongClicked
 */
class BackspaceLongPressIntegrationTest {

    // ─── Threshold checkpoints (Plan §7.1 AC-C wording) ────────────────

    @Test
    fun `initial delay is 50 ms before the first threshold`() {
        // Below the 1.5 s threshold the curve stays at the initial delay.
        val t0 = BackspaceDeleteSpeedCurve.nextDelay(elapsedMs = 0L, currentDelayMs = 50)
        val tMid = BackspaceDeleteSpeedCurve.nextDelay(elapsedMs = 1000L, currentDelayMs = 50)
        val tBoundary = BackspaceDeleteSpeedCurve.nextDelay(elapsedMs = 1500L, currentDelayMs = 50)
        assertEquals(50, t0.nextDelayMs)
        assertEquals(50, tMid.nextDelayMs)
        assertEquals(
            "1500 ms is the strict `>` boundary — still initial delay.",
            50,
            tBoundary.nextDelayMs,
        )
        assertFalse("No advance before crossing the threshold.", t0.advanced)
        assertFalse(tMid.advanced)
        assertFalse(tBoundary.advanced)
    }

    @Test
    fun `delay flips 50 to 25 at the 1_5 s checkpoint`() {
        // First threshold: > 1500 ms.
        val step = BackspaceDeleteSpeedCurve.nextDelay(elapsedMs = 1501L, currentDelayMs = 50)
        assertEquals(25, step.nextDelayMs)
        assertTrue(
            "Crossing the 1.5 s threshold advances the curve — caller " +
                "uses this to gate the haptic.",
            step.advanced,
        )
    }

    @Test
    fun `delay flips 25 to 10 at the 3 s checkpoint`() {
        // Second threshold: > 3000 ms, gated on currentDelay == 25.
        val step = BackspaceDeleteSpeedCurve.nextDelay(elapsedMs = 3001L, currentDelayMs = 25)
        assertEquals(10, step.nextDelayMs)
        assertTrue(step.advanced)
    }

    @Test
    fun `delay flips 10 to 5 at the 5 s checkpoint`() {
        // Third threshold: > 5000 ms, gated on currentDelay == 10.
        val step = BackspaceDeleteSpeedCurve.nextDelay(elapsedMs = 5001L, currentDelayMs = 10)
        assertEquals(5, step.nextDelayMs)
        assertTrue(step.advanced)
    }

    @Test
    fun `delay stays at 5 ms after the final threshold`() {
        // Past the last threshold there is no further step — the
        // current delay sticks.
        val step = BackspaceDeleteSpeedCurve.nextDelay(elapsedMs = 10_000L, currentDelayMs = 5)
        assertEquals(5, step.nextDelayMs)
        assertFalse("No advance once at the fastest delay.", step.advanced)
    }

    // ─── Curve invariants (regression guards) ──────────────────────────

    @Test
    fun `curve is monotone non-increasing`() {
        // Walk the curve step-by-step from t=0 to t=6 s and assert the
        // delay never increases. This catches a future edit that
        // re-orders the if-ladder and accidentally introduces a
        // backwards step.
        var delay = 50
        var previousDelay = delay
        var t = 0L
        while (t <= 6000L) {
            val step = BackspaceDeleteSpeedCurve.nextDelay(t, delay)
            assertTrue(
                "Curve must be monotone non-increasing: t=$t prev=$previousDelay next=${step.nextDelayMs}",
                step.nextDelayMs <= previousDelay,
            )
            previousDelay = step.nextDelayMs
            delay = step.nextDelayMs
            t += 100L
        }
        assertEquals(
            "After 6 s of cascading the delay reaches the fastest step.",
            5,
            delay,
        )
    }

    @Test
    fun `steps are one-shot - a stale current delay does not retrigger`() {
        // The `&& currentDelayMs == X` guards make each threshold one-shot.
        // If the caller's currentDelay has already been past the first
        // threshold (== 25), a tick at t=1600 ms must NOT roll back to 50
        // and must NOT advance to 10 (that step requires the > 3 s
        // threshold).
        val step = BackspaceDeleteSpeedCurve.nextDelay(elapsedMs = 1600L, currentDelayMs = 25)
        assertEquals(25, step.nextDelayMs)
        assertFalse(step.advanced)
    }

    @Test
    fun `crossing multiple thresholds in one tick only advances by one step`() {
        // A pathological caller (e.g. heavy GC pause) might land a tick
        // where `elapsed` jumps from < 1500 ms straight to 5500 ms. The
        // curve still advances *one* step per call — 50 → 25 — because
        // each threshold's guard names the previous delay. The next tick
        // then advances 25 → 10, etc.
        //
        // This matches the legacy behaviour: the original if/else-if/
        // else-if ladder is structured the same way, and the
        // Handler-loop calls back into the curve on every tick.
        val first = BackspaceDeleteSpeedCurve.nextDelay(elapsedMs = 5500L, currentDelayMs = 50)
        assertEquals(
            "From the initial 50 ms delay the curve steps to 25 ms, " +
                "not 5 ms — each threshold is gated by the current delay.",
            25,
            first.nextDelayMs,
        )
        assertTrue(first.advanced)

        // Caller now passes the new delay; second tick at the same
        // elapsed advances 25 → 10.
        val second = BackspaceDeleteSpeedCurve.nextDelay(elapsedMs = 5500L, currentDelayMs = first.nextDelayMs)
        assertEquals(10, second.nextDelayMs)
        assertTrue(second.advanced)

        // Third tick: 10 → 5.
        val third = BackspaceDeleteSpeedCurve.nextDelay(elapsedMs = 5500L, currentDelayMs = second.nextDelayMs)
        assertEquals(5, third.nextDelayMs)
        assertTrue(third.advanced)

        // Fourth tick: 5 → 5 (terminal).
        val fourth = BackspaceDeleteSpeedCurve.nextDelay(elapsedMs = 5500L, currentDelayMs = third.nextDelayMs)
        assertEquals(5, fourth.nextDelayMs)
        assertFalse(fourth.advanced)
    }

    @Test
    fun `published constants match legacy thresholds and delays`() {
        // Regression guard against an accidental tweak of the constants.
        // The legacy code hardcoded 50 / 25 / 10 / 5 with thresholds at
        // 1500 / 3000 / 5000 ms — anyone changing these is changing
        // user-facing behaviour and must update this test deliberately.
        assertEquals(50, BackspaceDeleteSpeedCurve.INITIAL_DELAY_MS)
        assertEquals(25, BackspaceDeleteSpeedCurve.FAST_DELAY_MS)
        assertEquals(10, BackspaceDeleteSpeedCurve.FASTER_DELAY_MS)
        assertEquals(5, BackspaceDeleteSpeedCurve.FASTEST_DELAY_MS)
        assertEquals(1500L, BackspaceDeleteSpeedCurve.FAST_THRESHOLD_MS)
        assertEquals(3000L, BackspaceDeleteSpeedCurve.FASTER_THRESHOLD_MS)
        assertEquals(5000L, BackspaceDeleteSpeedCurve.FASTEST_THRESHOLD_MS)
    }
}
