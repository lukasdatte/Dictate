package net.devemperor.dictate.state.render.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * The floating-overlay root container — a [LinearLayout] that adds
 * drag-to-reposition on top of clickable button children.
 *
 * # Why a custom ViewGroup (the touch-routing problem)
 *
 * The overlay window must be draggable from anywhere on the card, yet
 * its children (record / pause / trash / close) are ordinary clickable
 * `MaterialButton`s. In Android the `ACTION_DOWN` of a gesture decides
 * the touch target for the **whole** gesture: a clickable Button
 * consumes `ACTION_DOWN` and every following `ACTION_MOVE` is routed
 * straight to that Button — a touch listener on the parent never sees
 * the movement. A plain `setOnTouchListener` on the root can therefore
 * drag only when the finger starts on empty padding; a touch starting
 * on a button could never become a drag.
 *
 * The only mechanism that re-routes an in-flight gesture away from a
 * child that already claimed it is [onInterceptTouchEvent]. This view
 * overrides it: `ACTION_DOWN` passes through (buttons stay clickable),
 * and once the finger has travelled past the drag threshold the
 * [OverlayDragController] signals an intercept — the button receives
 * `ACTION_CANCEL` and the rest of the gesture flows into [onTouchEvent],
 * where the controller moves the window. Result: tap a button → click;
 * drag from anywhere → reposition.
 *
 * # Wiring
 *
 * Inflated as the root of `overlay_5button_layout.xml`. [OverlayBackend]
 * builds the [OverlayDragController] (it owns the window + params + the
 * persistence cascade) and assigns it to [dragController] once per
 * inflate; teardown clears it again. While [dragController] is `null`
 * the view behaves as a plain `LinearLayout` (no drag) — the safe
 * pre-wire / post-teardown default.
 *
 * @see OverlayDragController
 * @see OverlayBackend
 */
class DraggableOverlayLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /**
     * The drag state machine. Set by [OverlayBackend] once per inflate,
     * cleared on teardown. `null` → the view does not drag (plain
     * `LinearLayout` behaviour).
     */
    var dragController: OverlayDragController? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        dragController?.onInterceptTouchEvent(ev) ?: false

    override fun onTouchEvent(ev: MotionEvent): Boolean =
        dragController?.onTouchEvent(ev) ?: false
}
