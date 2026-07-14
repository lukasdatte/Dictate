package net.devemperor.dictate.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [EditBarWidthCalculator].
 *
 * Plain JVM — no Robolectric. That is the whole reason the arithmetic
 * lives in a pure object: the peek rules are the interesting part and they
 * deserve fast, exhaustive coverage rather than an inflation test.
 *
 * The load-bearing case is [`overflow never looks exactly fitting`] — it
 * pins the property the class exists for.
 */
class EditBarWidthCalculatorTest {

    // Realistic values: 52dp slot (44dp touch target + a 4dp inset per side),
    // 12dp peek, at mdpi where 1dp == 1px.
    private val minSlot = 52
    private val minPeek = 12

    private fun compute(available: Int, count: Int) =
        EditBarWidthCalculator.compute(available, count, minSlot, minPeek)

    // ── Rule 1: everything fits ──────────────────────────────────────────

    @Test
    fun `row that fits is split evenly and reports no overflow`() {
        val r = compute(available = 1200, count = 12)
        assertEquals(100, r.slotWidthPx)
        assertEquals(12, r.fullyVisibleCount)
        assertEquals(0, r.peekPx)
        assertFalse(r.overflowing)
    }

    @Test
    fun `exactly fitting at the floor is NOT treated as overflow`() {
        // 12 * 52 == 624. Nothing is hidden, so a flush right edge is honest
        // — this must not trigger the peek (which would pointlessly hide a
        // button that fits).
        val r = compute(available = 624, count = 12)
        assertFalse(r.overflowing)
        assertEquals(12, r.fullyVisibleCount)
        assertEquals(0, r.peekPx)
    }

    @Test
    fun `one pixel below the floor total flips into overflow`() {
        val r = compute(available = 623, count = 12)
        assertTrue(r.overflowing)
        assertTrue("some slot must be cut", r.fullyVisibleCount < 12)
    }

    // ── Rule 2: overflow always advertises itself ────────────────────────

    @Test
    fun `overflow keeps at least the minimum peek visible`() {
        val r = compute(available = 400, count = 14)
        assertTrue(r.overflowing)
        assertTrue("peek ${r.peekPx} must be >= $minPeek", r.peekPx >= minPeek)
        assertTrue("slot ${r.slotWidthPx} must respect the floor", r.slotWidthPx >= minSlot)
    }

    @Test
    fun `overflow never looks exactly fitting`() {
        // The property the class exists for. Sweep every viewport width a
        // real device could produce: whenever the row overflows, the visible
        // slots must NOT end flush with the viewport — there must always be
        // a sliver of the next one to advertise the scroll.
        for (available in 200..2000) {
            for (count in intArrayOf(12, 13, 14, 15)) {
                val r = compute(available, count)
                if (!r.overflowing) continue
                val consumed = r.fullyVisibleCount * r.slotWidthPx
                assertTrue(
                    "available=$available count=$count: row ended flush — " +
                        "the user cannot tell there is more to the right",
                    consumed < available,
                )
                assertTrue(
                    "available=$available count=$count: peek ${r.peekPx} < $minPeek",
                    r.peekPx >= minPeek,
                )
            }
        }
    }

    @Test
    fun `overflow never undercuts the touch-target floor`() {
        for (available in 200..2000) {
            for (count in intArrayOf(12, 13, 14, 15)) {
                val r = compute(available, count)
                assertTrue(
                    "available=$available count=$count: slot ${r.slotWidthPx} < $minSlot",
                    r.slotWidthPx >= minSlot,
                )
            }
        }
    }

    @Test
    fun `overflow always leaves at least one slot cut`() {
        val r = compute(available = 700, count = 14)
        assertTrue(r.overflowing)
        assertTrue(r.fullyVisibleCount <= 13)
    }

    // ── The user-facing minimum: >= 3 core buttons reachable ─────────────

    @Test
    fun `narrowest realistic phone still shows at least three full buttons`() {
        // 320dp is the narrowest screen Android ships; minus the bar's 16dp
        // start padding that is 304px at mdpi. Even with 15 buttons the row
        // must keep the leading three fully tappable without scrolling.
        val r = compute(available = 304, count = 15)
        assertTrue(
            "only ${r.fullyVisibleCount} full buttons on a 320dp screen",
            r.fullyVisibleCount >= 3,
        )
    }

    // ── Degenerate inputs stay total (no throw, no negative) ─────────────

    @Test
    fun `zero items yields an empty result`() {
        val r = compute(available = 500, count = 0)
        assertEquals(0, r.slotWidthPx)
        assertFalse(r.overflowing)
    }

    @Test
    fun `zero width yields an empty result (pre-measure tick)`() {
        // Reached on the first measure pass before the bar has a width.
        val r = compute(available = 0, count = 12)
        assertEquals(0, r.slotWidthPx)
        assertFalse(r.overflowing)
    }

    @Test
    fun `single item wider than the viewport falls back to the floor`() {
        val r = compute(available = 40, count = 12)
        assertEquals("the touch-target floor wins over the peek", minSlot, r.slotWidthPx)
        assertTrue("peek must never go negative", r.peekPx >= 0)
    }
}
