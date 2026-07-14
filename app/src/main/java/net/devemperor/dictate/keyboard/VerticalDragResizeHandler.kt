package net.devemperor.dictate.keyboard

import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * A reusable one-touchpoint vertical resize primitive (ADR-0014 follow-up / Block A).
 *
 * A grab handle (any [View]) reports DOWN/MOVE/UP; this primitive turns the vertical drag
 * into a clamped target height and reports it back through two callbacks:
 *
 * - [onHeightChanged] fires live on every change during the drag (wire it to
 *   `setLayoutParams` + a re-layout so the surface — and, for an IME whose root is
 *   `wrap_content`, the window — grows with the finger).
 * - [onHeightCommitted] fires once on UP/CANCEL with the final height (wire it to a
 *   single persistence write, so one drag is one `SharedPreferences` write, not one per
 *   `MotionEvent`).
 *
 * The primitive owns **no** concrete target view — the caller decides which view's height
 * to change and where to persist it. That keeps it panel-agnostic (history panel today,
 * review panel or any future in-keyboard list tomorrow) and keeps the delta→height math
 * ([resolveHeight]) a pure function, unit-testable without the Android view layer.
 *
 * @param minHeightPx lower clamp (px).
 * @param maxHeightPx upper clamp (px). The caller computes it (e.g. a display-fraction) —
 *   the primitive does not read the display.
 * @param startHeightProvider the target's current height (px), read on DOWN.
 * @param growWhenDraggingDown `true` when the handle sits at the *bottom* of the surface
 *   (dragging down = larger). `false` for a top handle (dragging down = smaller).
 * @param touchSlopPx the drag is only recognised once the finger has moved past this
 *   (from `ViewConfiguration.getScaledTouchSlop()`), so a stray tap does not resize.
 */
class VerticalDragResizeHandler(
    private val minHeightPx: Int,
    private val maxHeightPx: Int,
    private val startHeightProvider: () -> Int,
    private val growWhenDraggingDown: Boolean,
    private val touchSlopPx: Int,
    private val onHeightChanged: (Int) -> Unit,
    private val onHeightCommitted: (Int) -> Unit,
) : View.OnTouchListener {

    private var startRawY = 0f
    private var startHeight = 0
    private var lastHeight = 0
    private var dragging = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startRawY = event.rawY
                startHeight = startHeightProvider().coerceIn(minHeightPx, maxHeightPx)
                lastHeight = startHeight
                dragging = false
                return true // claim the gesture so MOVE/UP arrive here
            }

            MotionEvent.ACTION_MOVE -> {
                val rawDelta = event.rawY - startRawY
                if (!dragging) {
                    if (abs(rawDelta) < touchSlopPx) return true
                    dragging = true
                }
                val newHeight = resolveHeight(startHeight, rawDelta, growWhenDraggingDown, minHeightPx, maxHeightPx)
                if (newHeight != lastHeight) {
                    lastHeight = newHeight
                    onHeightChanged(newHeight)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onHeightCommitted(lastHeight)
                dragging = false
                return true
            }
        }
        return false
    }

    companion object {
        /**
         * Pure delta→height math (no Android types). Given the height at gesture start and
         * the raw vertical drag delta in px (positive = finger moved down), returns the new
         * height clamped to `[min, max]`. Inverted for a top handle via [growWhenDraggingDown].
         */
        fun resolveHeight(
            startHeight: Int,
            rawDeltaY: Float,
            growWhenDraggingDown: Boolean,
            min: Int,
            max: Int,
        ): Int {
            val effective = if (growWhenDraggingDown) rawDeltaY else -rawDeltaY
            return (startHeight + effective).toInt().coerceIn(min, max)
        }
    }
}
