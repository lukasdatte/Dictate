package net.devemperor.dictate.keyboard

import android.view.MotionEvent
import android.view.View

/**
 * Reusable touch handler for cursor movement via horizontal swipe.
 *
 * Extracted from the space button touch logic in DictateInputMethodService.
 * Differentiates between tap (space input) and horizontal swipe (cursor movement).
 *
 * Usage:
 * ```
 * val handler = CursorSwipeTouchHandler(
 *     swipeThresholdPx = 30f,
 *     onTap = { commitText(" ", 1) },
 *     onCursorMove = { direction -> commitText("", direction) }
 * )
 * spaceButton.setOnTouchListener(handler)
 * ```
 *
 * @param swipeThresholdPx minimum horizontal distance in pixels to trigger a cursor move
 * @param onTap called when the user taps without swiping
 * @param onCursorMove called with +1 (right) or -1 (left) for each swipe step
 * @param onSwipeStateChanged optional callback when swipe mode is entered/exited (e.g. to show arrows)
 * @param consumeTouchEvents true (default) to consume events and guarantee full gesture delivery;
 *   set to false when an outer listener (e.g. existing space button) handles the touch flow
 */
class CursorSwipeTouchHandler(
    private val swipeThresholdPx: Float = DEFAULT_SWIPE_THRESHOLD,
    private val onTap: () -> Unit,
    private val onCursorMove: (direction: Int) -> Unit,
    private val onSwipeStateChanged: ((isSwiping: Boolean) -> Unit)? = null,
    private val consumeTouchEvents: Boolean = true
) : View.OnTouchListener {

    private var startX = 0f
    private var hasSwiped = false

    companion object {
        /** Default swipe threshold in pixels (matches existing space button logic). */
        const val DEFAULT_SWIPE_THRESHOLD = 30f
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hasSwiped = false
                startX = event.x
                onSwipeStateChanged?.invoke(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - startX
                if (deltaX > swipeThresholdPx) {
                    onCursorMove(1)
                    startX = event.x
                    hasSwiped = true
                } else if (-deltaX > swipeThresholdPx) {
                    onCursorMove(-1)
                    startX = event.x
                    hasSwiped = true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!hasSwiped) {
                    onTap()
                }
                onSwipeStateChanged?.invoke(false)
            }

            MotionEvent.ACTION_CANCEL -> {
                onSwipeStateChanged?.invoke(false)
            }
        }
        return consumeTouchEvents
    }
}
