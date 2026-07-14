package net.devemperor.dictate.widget

/**
 * The pure width arithmetic behind [PeekingButtonBar].
 *
 * # Why this is a separate object
 *
 * Android has no `IntersectionObserver` — "is the row overflowing?" has to
 * be *computed*, not observed. Keeping that computation in a pure object
 * (no `Context`, no `View`) means the interesting part — the peek rules —
 * is covered by plain JVM tests instead of Robolectric inflation tests,
 * the same K-4 reasoning that keeps `LayoutStrings` out of the resolvers.
 *
 * # The rules
 *
 * All slots get the **same** width, so the row never looks ragged.
 *
 *  1. **Everything fits** (`itemCount * minSlot <= available`) → split the
 *     row evenly across all slots and show no peek. This is the pre-existing
 *     ConstraintLayout-chain behaviour (every button `0dp` in a chain), so
 *     the common case looks exactly as it did before.
 *  2. **Overflow** → reserve a sliver of at least `minPeekPx` at the right
 *     edge, then divide what is left across as many whole slots as fit.
 *     The trailing slot is therefore *always* cut mid-button.
 *
 * Rule 2 is the point of the class: a row that happens to end flush with
 * the viewport reads as complete, and the user never discovers the buttons
 * to the right. Reserving the sliver **before** dividing makes "looks
 * exactly fitting" unreachable while overflowing — it is a property of the
 * arithmetic, not a value someone has to keep tuning.
 *
 * # Slots, not buttons
 *
 * A *slot* is the full width a child occupies. The visual gap between
 * buttons is drawn as a MaterialButton inset **inside** the slot rather
 * than as a margin between slots, which keeps the arithmetic exact
 * (`rowWidth == itemCount * slotWidth`) instead of having to thread
 * per-child margins through every branch.
 *
 * # Degenerate viewports
 *
 * [minSlotWidthPx] is a hard floor: on a viewport too narrow for one slot
 * plus a sliver the floor wins and the peek shrinks (possibly to zero).
 * Such a viewport does not exist on real hardware — the narrowest sane
 * phone (320dp) still leaves ~6 fully visible slots — but the arithmetic
 * stays total rather than throwing.
 *
 * @see PeekingButtonBar
 */
object EditBarWidthCalculator {

    /**
     * @property slotWidthPx width every slot must be laid out at.
     * @property fullyVisibleCount how many slots fit without being cut.
     * @property peekPx width of the visible sliver of the first cut slot;
     *   `0` when [overflowing] is false.
     * @property overflowing `true` when the row is wider than the viewport,
     *   i.e. there is something to scroll to.
     */
    data class Result(
        val slotWidthPx: Int,
        val fullyVisibleCount: Int,
        val peekPx: Int,
        val overflowing: Boolean,
    )

    /**
     * @param availableWidthPx the viewport width the slots must live in
     *   (already net of the bar's own padding).
     * @param itemCount number of slots to lay out (GONE children excluded
     *   by the caller).
     * @param minSlotWidthPx hard floor for a slot — the accessibility touch
     *   target. Never undercut, even if that costs the peek.
     * @param minPeekPx how much of the first cut slot must stay visible.
     */
    fun compute(
        availableWidthPx: Int,
        itemCount: Int,
        minSlotWidthPx: Int,
        minPeekPx: Int,
    ): Result {
        if (itemCount <= 0 || availableWidthPx <= 0 || minSlotWidthPx <= 0) {
            return Result(slotWidthPx = 0, fullyVisibleCount = 0, peekPx = 0, overflowing = false)
        }

        // Rule 1 — the row fits at (or above) the floor: fill the viewport
        // evenly. No peek: nothing is hidden, so a flush right edge is the
        // truth rather than a misleading coincidence.
        if (itemCount * minSlotWidthPx <= availableWidthPx) {
            return Result(
                slotWidthPx = availableWidthPx / itemCount,
                fullyVisibleCount = itemCount,
                peekPx = 0,
                overflowing = false,
            )
        }

        // Rule 2 — overflow. Take the sliver off the top, then see how many
        // whole slots the remainder buys at the floor width.
        val fullyVisible = ((availableWidthPx - minPeekPx) / minSlotWidthPx)
            .coerceAtLeast(1)
            // At least one slot must stay cut, otherwise there is no peek to
            // advertise the overflow with.
            .coerceAtMost(itemCount - 1)

        // Integer division floors, so `fullyVisible * slotWidth <= available
        // - minPeekPx` — that is what guarantees `peek >= minPeekPx` below
        // rather than a comparison someone could later "simplify" away.
        val slotWidth = ((availableWidthPx - minPeekPx) / fullyVisible)
            .coerceAtLeast(minSlotWidthPx)

        return Result(
            slotWidthPx = slotWidth,
            fullyVisibleCount = fullyVisible,
            // coerceAtLeast(0) only bites on the degenerate viewport described
            // in the class KDoc, where the floor has already overrun the row.
            peekPx = (availableWidthPx - fullyVisible * slotWidth).coerceAtLeast(0),
            overflowing = true,
        )
    }
}
