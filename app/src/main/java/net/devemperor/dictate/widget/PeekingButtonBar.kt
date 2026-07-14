package net.devemperor.dictate.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import net.devemperor.dictate.R

/**
 * A horizontally scrolling button strip that sizes its slots so an
 * overflowing row is **always visibly cut** at the right edge.
 *
 * # The problem it solves
 *
 * The edit-bar used to be a ConstraintLayout chain of `0dp` buttons: the
 * viewport was divided by the button count, so every button added shrank
 * all the others. That degrades silently — at some count the icons simply
 * start colliding, and there is no width at which the row admits that
 * buttons exist off-screen. With the widget / a11y / PC buttons the row
 * reached that point.
 *
 * A scroll container alone does not fix it either: if the visible slots
 * happen to end flush with the viewport the row reads as complete and the
 * user never discovers the rest. Android has no `IntersectionObserver` to
 * detect that after the fact, so [EditBarWidthCalculator] *derives* a slot
 * width that reserves a sliver of the next button whenever the row
 * overflows. See that class for the rules and the trade-offs.
 *
 * # Usage
 *
 * Wrap exactly one [ViewGroup] row (a horizontal `LinearLayout`) whose
 * children are the buttons:
 *
 * ```xml
 * <net.devemperor.dictate.widget.PeekingButtonBar
 *     android:layout_width="0dp" android:layout_height="36dp">
 *     <LinearLayout android:orientation="horizontal"
 *         android:layout_width="wrap_content" android:layout_height="match_parent">
 *         <MaterialButton … />   <!-- width is overwritten at measure time -->
 *     </LinearLayout>
 * </net.devemperor.dictate.widget.PeekingButtonBar>
 * ```
 *
 * Children keep whatever `layout_width` the XML declares as an
 * inflation-time fallback; this class overwrites it on every measure.
 * `GONE` children are excluded from the arithmetic, so a
 * conditionally-hidden button does not leave a hole.
 *
 * **The visual gap between buttons must be an inset, not a margin**
 * (`android:insetLeft`/`android:insetRight` on a `MaterialButton`).
 * Margins would sit *between* slots and break the exact
 * `rowWidth == count * slotWidth` identity the peek arithmetic relies on.
 *
 * @see EditBarWidthCalculator
 */
class PeekingButtonBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    /** Hard floor for a slot — the accessibility touch target. */
    var minSlotWidthPx: Int = resources.getDimensionPixelSize(R.dimen.dictate_editbar_slot_min)

    /** How much of the first cut slot stays visible while overflowing. */
    var minPeekPx: Int = resources.getDimensionPixelSize(R.dimen.dictate_editbar_peek)

    /**
     * The most recent arithmetic result — the assertion surface for tests
     * and a diagnostic when a row looks wrong on a device.
     */
    var lastResult: EditBarWidthCalculator.Result? = null
        private set

    init {
        // The strip is the affordance; scrollbars would fight the peek for
        // the same few pixels at the bottom edge.
        isHorizontalScrollBarEnabled = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Size the slots BEFORE super measures the row, so the row is
        // measured once, at its final width.
        applySlotWidths(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun applySlotWidths(availableWidthPx: Int) {
        val row = getChildAt(0) as? ViewGroup ?: return
        val slots = (0 until row.childCount)
            .map { row.getChildAt(it) }
            .filter { it.visibility != View.GONE }

        val result = EditBarWidthCalculator.compute(
            availableWidthPx = availableWidthPx,
            itemCount = slots.size,
            minSlotWidthPx = minSlotWidthPx,
            minPeekPx = minPeekPx,
        )
        lastResult = result
        // Width 0 == the pre-measure tick (no viewport yet). Writing it would
        // collapse every button; the next pass has a real width.
        if (result.slotWidthPx <= 0) return

        var changed = false
        for (slot in slots) {
            val lp = slot.layoutParams ?: continue
            if (lp.width != result.slotWidthPx) {
                // Mutate the field rather than calling setLayoutParams():
                // that calls requestLayout(), and we are inside a measure
                // pass — Android drops such a request and logs
                // "requestLayout() improperly called during layout".
                lp.width = result.slotWidthPx
                changed = true
            }
        }
        // Changing lp.width behind requestLayout()'s back means the row would
        // happily reuse its cached measurement; force it to re-run so the new
        // widths reach the children.
        if (changed) row.forceLayout()
    }
}
