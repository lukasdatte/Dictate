package net.devemperor.dictate.state.render.overlay

import android.content.Context
import android.util.DisplayMetrics
import android.view.View

/**
 * Bidirectional converter between normalised `[0..1]` overlay-position
 * coordinates (persisted on the [net.devemperor.dictate.state.OverlayState]
 * axis) and absolute pixel coordinates (consumed by
 * [android.view.WindowManager.LayoutParams]).
 *
 * # Why an interface?
 *
 * The mapper is shared between [OverlayBackend] (de-normalise on render)
 * and [OverlayDragController] (normalise on `ACTION_UP`). Hoisting the
 * conversion into a single SoT keeps the two call-sites in sync —
 * Spec 3 §11.5.4 + §11.5.5 explicitly call out the drift risk of
 * duplicate math.
 *
 * # Normalisation semantics (Spec 3 §11.5.4)
 *
 * `normX = 0` ⇒ overlay's top-left corner is at `screen.x = 0` (left
 * edge). `normX = 1` ⇒ overlay's top-left corner is at the largest
 * `x` that still leaves the entire view on-screen
 * (`screenW - viewW`). Analogous for `normY`. The fraction is therefore
 * relative to the **free area** (`screen − view`) rather than the full
 * screen — that way the default `1.0f` anchors the overlay flush
 * against the right edge regardless of view width.
 *
 * # View-not-measured short-circuit (Spec 3 §4.7)
 *
 * The first render may run before the inflated overlay's `measure`
 * pass completes — `view.width == 0`. Both directions return `null` in
 * that case; callers postpone the `update()` (Spec 3 §11.5.5 +
 * Issue 3.1.11 / R.19 / R.20).
 *
 * @see OverlayBackend
 * @see OverlayDragController
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md §4.7
 */
interface OverlayPositionMapper {
    /**
     * Convert `[0..1]` × `[0..1]` to absolute pixel coordinates
     * suitable for [android.view.WindowManager.LayoutParams.x] /
     * [android.view.WindowManager.LayoutParams.y].
     *
     * Returns `null` when [view] is not yet measured (`width == 0` and
     * `measuredWidth == 0`).
     */
    fun normalizedToPixels(normX: Float, normY: Float, view: View): Pair<Int, Int>?

    /**
     * Inverse of [normalizedToPixels] — clamps to `[0..1]`.
     *
     * Returns `null` under the same not-measured condition.
     */
    fun pixelsToNormalized(px: Int, py: Int, view: View): Pair<Float, Float>?
}

/**
 * Production implementation backed by
 * [android.util.DisplayMetrics].
 *
 * # Why DisplayMetrics (not `WindowMetrics`)?
 *
 * `WindowMetrics` is API ≥ 30; the project's min SDK is 26 (CLAUDE.md).
 * `DisplayMetrics.widthPixels / heightPixels` returns the display
 * resolution minus system bars on every supported API level — close
 * enough for the overlay-anchor use-case. Multi-display foldables
 * (Spec 3 §11.7) keep the right behaviour because
 * `resources.displayMetrics` reflects the **current** display the
 * Service's context is attached to.
 *
 * @property ctx an Android [Context] — its [Context.getResources]
 *   `.displayMetrics` is the live read source.
 *
 * @see OverlayPositionMapper
 */
class DefaultOverlayPositionMapper(
    private val ctx: Context,
) : OverlayPositionMapper {

    override fun normalizedToPixels(
        normX: Float,
        normY: Float,
        view: View,
    ): Pair<Int, Int>? {
        val viewW = view.effectiveWidth() ?: return null
        val viewH = view.effectiveHeight() ?: return null
        val (screenW, screenH) = displaySize()
        // F-6 (B5): use the SHARED [freeArea] helper with the SAME
        // zero-guard (`coerceAtLeast(1)`) as [pixelsToNormalized].
        // Previously this path floored at `coerceAtLeast(0)` while the
        // inverse floored at `1`, so a screen-filling view broke
        // round-trip identity: `normalizedToPixels(1.0) → px=0` but
        // `pixelsToNormalized(0) → 0.0`, silently rewriting the
        // persisted right-edge anchor (default `1.0f`) to the left
        // edge. With the symmetric denominator the boundary is
        // identity: `normalizedToPixels(1.0) → px=1` ⇒
        // `pixelsToNormalized(1) → 1.0`.
        val maxX = freeArea(screenW, viewW)
        val maxY = freeArea(screenH, viewH)
        val px = (normX.coerceIn(0f, 1f) * maxX).toInt()
        val py = (normY.coerceIn(0f, 1f) * maxY).toInt()
        return px to py
    }

    override fun pixelsToNormalized(
        px: Int,
        py: Int,
        view: View,
    ): Pair<Float, Float>? {
        val viewW = view.effectiveWidth() ?: return null
        val viewH = view.effectiveHeight() ?: return null
        val (screenW, screenH) = displaySize()
        // Clamping the result into `[0..1]` keeps the persisted axis
        // structurally valid even when the system clamped the
        // pre-update params off-screen (Spec 3 §11.5.3).
        val maxX = freeArea(screenW, viewW)
        val maxY = freeArea(screenH, viewH)
        val nx = (px.toFloat() / maxX).coerceIn(0f, 1f)
        val ny = (py.toFloat() / maxY).coerceIn(0f, 1f)
        return nx to ny
    }

    /**
     * The free travel area (`screen − view`) for one axis, with the
     * **single** zero-guard policy shared by both conversion
     * directions (F-6). `coerceAtLeast(1)` keeps the denominator
     * positive in the degenerate zero-free-area case (view exactly the
     * screen size) AND makes the round-trip identity at that boundary —
     * the two call-sites must use the *same* floor or the mapper's
     * "single SoT keeps the call-sites in sync" KDoc contract is
     * violated.
     */
    private fun freeArea(screen: Int, view: Int): Int =
        (screen - view).coerceAtLeast(1)

    private fun displaySize(): Pair<Int, Int> {
        val metrics: DisplayMetrics = ctx.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }
}

/**
 * The effective width of a [View] — the laid-out `width` if known,
 * else the `measuredWidth` from a pending measure pass, else `null`
 * meaning "not yet measurable".
 *
 * Spec 3 §4.7 calls this out as the single source of truth for the
 * width lookup, since duplicate `view.width.takeIf { it > 0 } ?:
 * view.measuredWidth`-style helpers had previously drifted apart.
 */
internal fun View.effectiveWidth(): Int? = when {
    width > 0 -> width
    measuredWidth > 0 -> measuredWidth
    else -> null
}

/**
 * Symmetric to [effectiveWidth] — see KDoc there.
 */
internal fun View.effectiveHeight(): Int? = when {
    height > 0 -> height
    measuredHeight > 0 -> measuredHeight
    else -> null
}
