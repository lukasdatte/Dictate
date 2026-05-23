package net.devemperor.dictate.state.render.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.max

/**
 * Factory for [OverlayDragController].
 *
 * Hoisted as an interface so JVM tests can inject a fake that records
 * every persist call without going through a real `MotionEvent`
 * stream. Production wires [DefaultOverlayDragControllerFactory].
 *
 * F-10 (B5): the 79-line controller-responsibility KDoc that used to
 * sit here was relocated to immediately precede `class
 * OverlayDragController(` — per Kotlin doc-attachment it documented
 * *this interface*, not the controller (IDE quick-doc showed the
 * controller's `@property` tags on the factory, and nothing on the
 * class). The relocated block also carries the F-7 orientation-snapshot
 * contract update so the SRP narrative stays coherent.
 */
interface OverlayDragControllerFactory {
    /**
     * Build a controller wired to [view] + [window]. The
     * [paramsHolder] lambda MUST return the same
     * [WindowManager.LayoutParams] instance the backend holds — see
     * [OverlayDragController]'s `paramsHolder` KDoc.
     *
     * [orientationProvider] supplies the "is portrait" snapshot the
     * controller captures **once** at `ACTION_DOWN` (F-7) and threads
     * back through [onPositionPersist] so the persisted normalised
     * value and its pref-bucket come from the same configuration
     * snapshot.
     */
    fun create(
        view: View,
        window: OverlayWindow,
        paramsHolder: () -> WindowManager.LayoutParams?,
        positionMapper: OverlayPositionMapper,
        orientationProvider: () -> Boolean,
        onPositionPersist: (portrait: Boolean, normX: Float, normY: Float) -> Unit,
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
        orientationProvider: () -> Boolean,
        onPositionPersist: (portrait: Boolean, normX: Float, normY: Float) -> Unit,
    ): OverlayDragController = OverlayDragController(
        ctx = ctx,
        view = view,
        window = window,
        paramsHolder = paramsHolder,
        positionMapper = positionMapper,
        orientationProvider = orientationProvider,
        onPositionPersist = onPositionPersist,
    )
}

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
 * The controller is driven by [DraggableOverlayLayout]'s
 * `onInterceptTouchEvent` / `onTouchEvent` overrides. [onInterceptTouchEvent]
 * does **not** intercept `ACTION_DOWN`, so the button children still
 * receive it and stay clickable. Beneath [dragThresholdPx] the gesture
 * keeps flowing to the buttons — they get `ACTION_DOWN` → `ACTION_UP`
 * and fire `OnClickListener`. The first `ACTION_MOVE` past the
 * threshold makes [onInterceptTouchEvent] return `true`: the ViewGroup
 * steals the gesture (the button receives `ACTION_CANCEL`, its click is
 * cancelled) and every following event flows into [onTouchEvent], which
 * issues continuous [OverlayWindow.update] calls per `ACTION_MOVE`.
 *
 * Intercepting — rather than a parent `OnTouchListener` — is mandatory:
 * once a clickable child consumes `ACTION_DOWN` it becomes the touch
 * target for the whole gesture and a listener on the parent never sees
 * the moves. `onInterceptTouchEvent` is the only hook that re-routes a
 * gesture a child already claimed.
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
 * # Orientation snapshot (F-7, B5 — SRP boundary update)
 *
 * The controller captures the "is portrait" bucket **once** at
 * `ACTION_DOWN` via [orientationProvider] and threads it back through
 * [onPositionPersist] together with the normalised coords. **This
 * changes the earlier SRP boundary** (which asserted "the controller
 * does not know which orientation it's in — orientation discrimination
 * lives in the backend"). Rationale: the backend used to read
 * `isPortraitOrientation()` *independently* in its `onPositionPersist`
 * lambda, a second `Configuration` read split from the
 * `pixelsToNormalized` geometry read. A config-change landing between
 * the two reads computed the normalised value against the old geometry
 * but persisted it into the *new* orientation's bucket → corrupted
 * position in the wrong bucket (also affects the R.18 mid-drag-detach
 * path via `teardownOverlay()`). Capturing the orientation at gesture
 * start and passing it through makes the bucket and the geometry that
 * produced the value come from the **same configuration snapshot**.
 * The backend still *owns* the orientation source (its
 * `isPortraitOrientation()` is the single SoT, injected here as
 * [orientationProvider]); the controller only captures and forwards
 * the snapshot — SRP is preserved, the boundary just moved the
 * *timing* of the read into the gesture.
 *
 * # Mid-drag detach safety net (Spec 3 §4.6 Issue 3.1.5 / R.18)
 *
 * If [detach] runs while [dragging] is true — e.g. a mode-transition
 * tears down the overlay while the user's finger is still on the
 * window — the controller persists the current params position before
 * releasing the listener (using the orientation snapshot captured at
 * `ACTION_DOWN`, F-7). Without this hook the un-emitted drag would
 * disappear from the state axis.
 *
 * # Why a separate class (not drag logic inlined in the view/backend)?
 *
 * Neither [OverlayBackend] nor [DraggableOverlayLayout] owns the
 * touch-routing *policy* — it interacts with `WindowManager.update`,
 * drag thresholds, and the persistence cascade in a single coherent
 * state machine. Keeping it here lets unit tests drive
 * [onInterceptTouchEvent] / [onTouchEvent] directly with injected
 * recording fakes (K-1); the view stays a thin override shell.
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
 * @property orientationProvider supplies the backend's
 *   `isPortraitOrientation()` (the single orientation SoT). Read
 *   **once** at `ACTION_DOWN` and cached for the whole gesture (F-7) so
 *   the persisted value's bucket matches the geometry that produced it.
 * @property onPositionPersist invoked on drag-end (Spec 3 §11.5.4)
 *   with the orientation snapshot captured at `ACTION_DOWN` and the
 *   final normalised position.
 *
 * @see OverlayBackend
 * @see OverlayPositionMapper
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md §4.6 + §11.5
 */
class OverlayDragController(
    ctx: Context,
    private val view: View,
    private val window: OverlayWindow,
    private val paramsHolder: () -> WindowManager.LayoutParams?,
    private val positionMapper: OverlayPositionMapper,
    private val orientationProvider: () -> Boolean,
    private val onPositionPersist: (portrait: Boolean, normX: Float, normY: Float) -> Unit,
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
     * Orientation ("is portrait") snapshot captured at `ACTION_DOWN`
     * and used for the whole gesture (F-7). Read once so the persisted
     * value's pref-bucket and the geometry that produced the
     * normalised value come from the *same* configuration — a
     * config-change mid-drag no longer splits the read between
     * `pixelsToNormalized` and the bucket selection.
     */
    private var gestureOrientationPortrait: Boolean = true

    /**
     * `true` when the controller is actively dispatching `update()`
     * calls. Exposed so the backend can short-circuit its
     * state-driven `applyPosition` while a user drag is in flight
     * (Spec 3 §4.6 Issue 3.1.5 — otherwise a same-tick render with
     * stale normalised coords would yank the overlay back).
     */
    fun isDragging(): Boolean = dragging

    /**
     * `onInterceptTouchEvent` hook for the [DraggableOverlayLayout] root.
     *
     * `ACTION_DOWN` is recorded but **not** intercepted (`false`) so the
     * button children still receive it and stay clickable. Each
     * `ACTION_MOVE` is measured against [dragThresholdPx]; the first
     * move past the threshold flips [dragging] on and returns `true` —
     * the ViewGroup then steals the gesture (the button receives
     * `ACTION_CANCEL`) and the remainder flows into [onTouchEvent].
     */
    fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        val params = paramsHolder() ?: return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                recordGestureStart(event, params)
                false
            }
            MotionEvent.ACTION_MOVE -> updateDragging(event)
            else -> false
        }
    }

    /**
     * `onTouchEvent` hook for the [DraggableOverlayLayout] root.
     *
     * Reached either (a) after [onInterceptTouchEvent] stole the gesture
     * — every following move/up arrives here — or (b) directly, when the
     * `ACTION_DOWN` landed on an empty (non-button) region of the root
     * and no child claimed it. Case (b) is why `ACTION_DOWN` records the
     * gesture start and returns `true` (claiming the gesture) and why
     * `ACTION_MOVE` re-checks the threshold: on the empty-region path
     * `onInterceptTouchEvent` is not consulted for the moves.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        val params = paramsHolder() ?: return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                recordGestureStart(event, params)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                updateDragging(event)
                if (dragging) {
                    params.x = initialParamsX + (event.rawX - initialTouchX).toInt()
                    params.y = initialParamsY + (event.rawY - initialTouchY).toInt()
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
                    true
                } else {
                    false
                }
            }

            else -> false
        }
    }

    /**
     * Capture the gesture's start coordinates, the window's start
     * position, and the orientation snapshot (F-7 — read ONCE here so
     * the persisted value's pref-bucket and the geometry that produced
     * it come from the same configuration). Idempotent for the single
     * `ACTION_DOWN` of a gesture — safe to call from both
     * [onInterceptTouchEvent] and [onTouchEvent].
     */
    private fun recordGestureStart(event: MotionEvent, params: WindowManager.LayoutParams) {
        initialTouchX = event.rawX
        initialTouchY = event.rawY
        initialParamsX = params.x
        initialParamsY = params.y
        gestureOrientationPortrait = orientationProvider()
        dragging = false
    }

    /**
     * Promote a potential click to a confirmed drag once the finger has
     * travelled past [dragThresholdPx]. Returns `true` on the single
     * tick where [dragging] flips on — [onInterceptTouchEvent] uses that
     * as the "steal the gesture now" signal.
     */
    private fun updateDragging(event: MotionEvent): Boolean {
        if (dragging) return false
        val dx = event.rawX - initialTouchX
        val dy = event.rawY - initialTouchY
        if (hypot(dx.toDouble(), dy.toDouble()) > dragThresholdPx) {
            dragging = true
            return true
        }
        return false
    }

    /**
     * Flush an in-flight drag at overlay teardown. If a drag is active
     * (a mode-transition tears the overlay down while the finger is
     * still on the window), the final pixel position is persisted so no
     * drag silently disappears (Spec 3 §4.6 / R.18). Unbinding this
     * controller from [DraggableOverlayLayout.dragController] is done
     * separately by [OverlayBackend.teardownOverlay].
     */
    fun detach() {
        if (dragging) {
            // F-12 invariant — drag-params stability: this relies on
            // the backend's `teardownOverlay()` calling
            // `dragController.detach()` **before** it swaps/clears
            // `currentParams`. `initialParamsX/Y` (captured at
            // `ACTION_DOWN`) and `paramsHolder()` here must refer to the
            // SAME `WindowManager.LayoutParams` instance; if teardown
            // re-inflated a fresh params object first, this mid-drag
            // persist would read post-swap coordinates. The ordering is
            // enforced in `OverlayBackend.teardownOverlay()` — do not
            // reorder it without revisiting this dependency.
            paramsHolder()?.let { params -> persistCurrentPosition(params) }
            dragging = false
        }
    }

    private fun persistCurrentPosition(params: WindowManager.LayoutParams) {
        positionMapper.pixelsToNormalized(params.x, params.y, view)
            ?.let { (nx, ny) ->
                // F-7: use the orientation captured at ACTION_DOWN, not
                // a fresh read — so the bucket matches the geometry.
                onPositionPersist(gestureOrientationPortrait, nx, ny)
            }
    }
}
