package net.devemperor.dictate.state.render.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.max

/**
 * Touch-listener + state machine that turns finger drags on the
 * floating-overlay root view into [WindowManager.LayoutParams] updates,
 * and translates the final pixel position into a normalised
 * `[0..1]` coordinate persisted via [onPositionPersist].
 *
 * # Why a separate class (SRP)
 *
 * Spec 3 §4.6 explicitly carves drag out of [OverlayBackend] — the
 * backend's responsibility is the render-loop, not touch routing.
 * Keeping the drag machine in its own type lets a future
 * `SnappingOverlayDragController` decorator add snap-to-edge without
 * touching either the backend or the mapper (OCP, Spec 3 §11.5.7).
 *
 * # Click-vs-drag differentiation (Spec 3 §11.5.2)
 *
 * The controller's `OnTouchListener` returns `false` until the user's
 * finger has travelled more than the [dragThresholdPx] from the
 * `ACTION_DOWN` position. Beneath the threshold the touch propagates
 * to the underlying child views (the five overlay buttons) — they
 * receive `ACTION_DOWN` → `ACTION_UP` and fire `OnClickListener`.
 * Above the threshold the controller takes over: returns `true`,
 * stops button-clicks from firing for the rest of the gesture, and
 * issues continuous [OverlayWindow.update] calls per `ACTION_MOVE`.
 *
 * # Threshold — accessibility-aware
 *
 * `dragThresholdPx = max(8dp, scaledTouchSlop * 1.5)` per Spec 3
 * §4.6. The `scaledTouchSlop` already incorporates the user's
 * Accessibility-touch settings; multiplying by `1.5` keeps long-press
 * gestures unambiguous (intentional drag vs accidental drift on a
 * long-press).
 *
 * # Persistence cascade (Spec 3 §11.5.4)
 *
 * On `ACTION_UP` / `ACTION_CANCEL` (only when [dragging] is true), the
 * pixel position is converted to normalised `[0..1]` via
 * [OverlayPositionMapper.pixelsToNormalized] and pushed through
 * [onPositionPersist]. Production wiring routes that callback to
 * `Action.OverlayAction.UpdateOverlayPosition`, which lands in the
 * reducer + `PersistOverlayPosition` effect — single dispatch (F-8).
 *
 * # Mid-drag detach safety net (Spec 3 §4.6 Issue 3.1.5 / R.18)
 *
 * If [detach] runs while [dragging] is true — e.g. a mode-transition
 * tears down the overlay while the user's finger is still on the
 * window — the controller persists the current params position before
 * releasing the listener. Without this hook the un-emitted drag would
 * disappear from the state axis.
 *
 * # Why not `View.setOnTouchListener` in the backend?
 *
 * The backend owns the root view but **not** the touch-routing policy
 * — that policy interacts with `WindowManager.update`, drag thresholds,
 * and the persistence cascade in a single coherent state machine.
 * Splitting it out lets unit tests inject a recording mapper +
 * recording persister (K-1, hand-rolled fakes).
 *
 * @property ctx context — used to read [ViewConfiguration.getScaledTouchSlop]
 *   and density at construction time.
 * @property view the inflated overlay root (the `WRAP_CONTENT` window
 *   anchor — Spec 3 §4.3).
 * @property window [OverlayWindow] wrapper used for `update` mid-drag.
 * @property paramsHolder callback returning the **mutable**
 *   [WindowManager.LayoutParams] held by the backend so the controller
 *   writes `x`/`y` directly (re-allocating per `ACTION_MOVE` would
 *   defeat the wrapper's idempotency).
 * @property positionMapper see [OverlayPositionMapper] — the only
 *   pixel↔normalised conversion site.
 * @property onPositionPersist invoked on drag-end (Spec 3 §11.5.4)
 *   with the final normalised position. Receives only the coordinates;
 *   the controller does **not** know which orientation it's in
 *   (orientation discrimination lives in the backend, which has the
 *   `Configuration` reference — SRP).
 *
 * @see OverlayBackend
 * @see OverlayPositionMapper
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md §4.6 + §11.5
 */
/**
 * Factory for [OverlayDragController].
 *
 * Hoisted as an interface so JVM tests can inject a fake that records
 * every persist call without going through a real `MotionEvent`
 * stream. Production wires [DefaultOverlayDragControllerFactory].
 */
interface OverlayDragControllerFactory {
    /**
     * Build a controller wired to [view] + [window]. The
     * [paramsHolder] lambda MUST return the same
     * [WindowManager.LayoutParams] instance the backend holds — see
     * [OverlayDragController]'s `paramsHolder` KDoc.
     */
    fun create(
        view: View,
        window: OverlayWindow,
        paramsHolder: () -> WindowManager.LayoutParams?,
        positionMapper: OverlayPositionMapper,
        onPositionPersist: (Float, Float) -> Unit,
    ): OverlayDragController
}

/**
 * Production [OverlayDragControllerFactory] — captures the
 * [Context] needed by the controller's threshold calculation.
 *
 * @property ctx context bound at Service-creation time; survives the
 *   factory's whole lifetime alongside the backend it serves.
 */
class DefaultOverlayDragControllerFactory(
    private val ctx: Context,
) : OverlayDragControllerFactory {
    override fun create(
        view: View,
        window: OverlayWindow,
        paramsHolder: () -> WindowManager.LayoutParams?,
        positionMapper: OverlayPositionMapper,
        onPositionPersist: (Float, Float) -> Unit,
    ): OverlayDragController = OverlayDragController(
        ctx = ctx,
        view = view,
        window = window,
        paramsHolder = paramsHolder,
        positionMapper = positionMapper,
        onPositionPersist = onPositionPersist,
    )
}

class OverlayDragController(
    ctx: Context,
    private val view: View,
    private val window: OverlayWindow,
    private val paramsHolder: () -> WindowManager.LayoutParams?,
    private val positionMapper: OverlayPositionMapper,
    private val onPositionPersist: (normX: Float, normY: Float) -> Unit,
) {

    /**
     * Move distance (raw pixels) at which a touch promotes from a
     * potential click to a confirmed drag.
     *
     * @see ViewConfiguration.getScaledTouchSlop
     */
    private val dragThresholdPx: Int = run {
        val baseDp = (8 * ctx.resources.displayMetrics.density).toInt()
        val scaledSlop = (ViewConfiguration.get(ctx).scaledTouchSlop * 1.5f).toInt()
        max(baseDp, scaledSlop)
    }

    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var initialParamsX: Int = 0
    private var initialParamsY: Int = 0
    private var dragging: Boolean = false

    /**
     * `true` when the controller is actively dispatching `update()`
     * calls. Exposed so the backend can short-circuit its
     * state-driven `applyPosition` while a user drag is in flight
     * (Spec 3 §4.6 Issue 3.1.5 — otherwise a same-tick render with
     * stale normalised coords would yank the overlay back).
     */
    fun isDragging(): Boolean = dragging

    private val touchListener = View.OnTouchListener { _, event ->
        val params = paramsHolder() ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                initialParamsX = params.x
                initialParamsY = params.y
                dragging = false
                // Return `false` so the inflated button children still
                // receive `ACTION_DOWN` for ripple feedback (Spec 3
                // §11.5.1).
                false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > dragThresholdPx) {
                    dragging = true
                }
                if (dragging) {
                    params.x = initialParamsX + dx.toInt()
                    params.y = initialParamsY + dy.toInt()
                    window.update(view, params)
                    true
                } else {
                    false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    persistCurrentPosition(params)
                    dragging = false
                    // Returning `true` suppresses the button-click
                    // that would otherwise fire on a confirmed drag
                    // (Spec 3 §11.5.2 third row).
                    true
                } else {
                    // Tap — pass through to the buttons' click
                    // listeners.
                    false
                }
            }

            else -> false
        }
    }

    /**
     * Bind the touch listener to the overlay root. Idempotent — a
     * second call replaces the prior listener (Android
     * `setOnTouchListener` semantics).
     */
    fun attach() {
        view.setOnTouchListener(touchListener)
    }

    /**
     * Release the touch listener. If a drag is in flight, the final
     * pixel position is persisted before the listener is detached so
     * no drag silently disappears (Spec 3 §4.6 / R.18).
     */
    fun detach() {
        if (dragging) {
            paramsHolder()?.let { params -> persistCurrentPosition(params) }
            dragging = false
        }
        view.setOnTouchListener(null)
    }

    private fun persistCurrentPosition(params: WindowManager.LayoutParams) {
        positionMapper.pixelsToNormalized(params.x, params.y, view)
            ?.let { (nx, ny) -> onPositionPersist(nx, ny) }
    }
}
