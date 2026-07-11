package net.devemperor.dictate.state.render.overlay

import net.devemperor.dictate.core.BackspaceDeleteSpeedCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [OverlayDeleteRepeatController] (P5).
 *
 * No Robolectric: the `Handler`/`Looper` is faked via [FakeScheduler] and
 * the clock is a mutable var, so the exact tick cadence and every stop
 * path (release / drag-cancel / teardown) are pinned deterministically.
 */
class OverlayDeleteRepeatControllerTest {

    private val clock = MutableClock()
    private val scheduler = FakeScheduler()
    private var deletes = 0
    private var advances = 0

    private fun newController(longPressTimeoutMs: Long = 500L) =
        OverlayDeleteRepeatController(
            scheduler = scheduler,
            clock = { clock.now },
            longPressTimeoutMs = longPressTimeoutMs,
            onDelete = { deletes++ },
            onAdvance = { advances++ },
        )

    @Test
    fun `onPress arms the long-press timer but deletes nothing yet`() {
        val c = newController(longPressTimeoutMs = 500L)

        c.onPress()

        assertFalse("still PENDING, not repeating", c.isRepeating())
        assertEquals("no delete before the long-press fires", 0, deletes)
        assertEquals("exactly the long-press timer is scheduled", 1, scheduler.pending.size)
        assertEquals(500L, scheduler.pending.first().delayMs)
    }

    @Test
    fun `long-press timeout starts the repeat and deletes once at the initial delay`() {
        val c = newController()
        c.onPress()

        scheduler.fireNext() // the long-press timer → beginRepeat + first tick

        assertTrue(c.isRepeating())
        assertEquals("first tick deletes one character", 1, deletes)
        // beginRepeat's tick schedules the next tick at the INITIAL delay.
        assertEquals(1, scheduler.pending.size)
        assertEquals(
            BackspaceDeleteSpeedCurve.INITIAL_DELAY_MS.toLong(),
            scheduler.pending.first().delayMs,
        )
    }

    @Test
    fun `repeat cadence accelerates 50 to 25 to 10 to 5 across the thresholds`() {
        // The acceleration applies to the tick scheduled AFTER the
        // threshold is crossed (same semantics as the IME's Handler loop:
        // `currentDeleteDelay` feeds the *next* postDelayed) — so each
        // assertion checks the delay of the newly-scheduled pending tick.
        fun nextPendingDelay(): Long = scheduler.pending.first().delayMs

        val c = newController()
        c.onPress()
        clock.now = 0L
        scheduler.fireNext() // beginRepeat: delete #1, schedules @50

        // Tick #2 still inside the first band (<=1500) → next stays at 50.
        clock.now = 1000L
        scheduler.fireNext()
        assertEquals(50L, nextPendingDelay())
        // Tick #3 past 1500 → next steps to 25 (one advance).
        clock.now = 1600L
        scheduler.fireNext()
        assertEquals(25L, nextPendingDelay())
        // Tick #4 past 3000 → next steps to 10.
        clock.now = 3100L
        scheduler.fireNext()
        assertEquals(10L, nextPendingDelay())
        // Tick #5 past 5000 → next steps to 5.
        clock.now = 5100L
        scheduler.fireNext()
        assertEquals(5L, nextPendingDelay())

        assertEquals("one delete per tick", 5, deletes)
        assertEquals("three acceleration steps (50→25→10→5)", 3, advances)
    }

    @Test
    fun `release before the long-press is a short tap - returns false and stops`() {
        val c = newController()
        c.onPress()

        val wasRepeating = c.onRelease()

        assertFalse("PENDING release is a short tap", wasRepeating)
        assertEquals("short tap deletes nothing itself (the click does)", 0, deletes)
        assertTrue("timer cancelled", scheduler.pending.isEmpty())
        assertEquals(1, scheduler.cancelCount)
    }

    @Test
    fun `release while repeating returns true and stops the loop`() {
        val c = newController()
        c.onPress()
        scheduler.fireNext() // start repeating (delete #1)

        val wasRepeating = c.onRelease()

        assertTrue("release during a repeat reports it ran", wasRepeating)
        assertFalse(c.isRepeating())
        assertTrue("no further ticks scheduled", scheduler.pending.isEmpty())
    }

    @Test
    fun `a stale tick that fires after release is a no-op (phase guard)`() {
        val c = newController()
        c.onPress()
        scheduler.fireNext() // repeating, next tick scheduled
        // Capture the still-pending tick, then release before it fires.
        val staleTick = scheduler.pending.first().action
        val deletesBefore = deletes
        c.onRelease()

        staleTick() // defence-in-depth: must not delete

        assertEquals("stale tick after stop must not delete", deletesBefore, deletes)
    }

    @Test
    fun `cancel (drag steal) while pending stops without any delete`() {
        val c = newController()
        c.onPress()

        c.cancel()

        assertFalse(c.isRepeating())
        assertEquals(0, deletes)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun `cancel (teardown) while repeating stops the loop`() {
        val c = newController()
        c.onPress()
        scheduler.fireNext() // repeating

        c.cancel()

        assertFalse(c.isRepeating())
        assertTrue("no scheduled tick survives teardown", scheduler.pending.isEmpty())
    }

    @Test
    fun `a second onPress while pending does not start a second loop`() {
        val c = newController()
        c.onPress()
        c.onPress()

        assertEquals("only one long-press timer armed", 1, scheduler.pending.size)
    }

    // ─── Fakes ────────────────────────────────────────────────────────

    private class MutableClock(var now: Long = 0L)

    private data class Scheduled(val delayMs: Long, val action: () -> Unit)

    /**
     * Deterministic [RepeatScheduler] fake. [postDelayed] records the
     * `(delay, action)`; [fireNext] pops and runs the oldest pending
     * action, returning its scheduled delay so the test can assert the
     * cadence. [cancelAll] clears the queue and bumps [cancelCount].
     */
    private class FakeScheduler : RepeatScheduler {
        val pending = ArrayDeque<Scheduled>()
        var cancelCount = 0

        override fun postDelayed(delayMs: Long, action: () -> Unit) {
            pending.addLast(Scheduled(delayMs, action))
        }

        override fun cancelAll() {
            cancelCount++
            pending.clear()
        }

        /** Run the oldest pending action; returns its scheduled delay. */
        fun fireNext(): Long {
            val next = pending.removeFirst()
            next.action()
            return next.delayMs
        }
    }
}
