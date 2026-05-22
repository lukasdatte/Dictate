package net.devemperor.dictate.state.render.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager

/**
 * Builds the [WindowManager.LayoutParams] used by the floating overlay
 * window.
 *
 * # Why a factory (not a `LayoutParams` builder in the backend)?
 *
 * The flag combination is the highest-friction part of the overlay
 * config — every flag has a non-obvious effect on touch-routing,
 * focus, and the lock-screen / status-bar interaction. Isolating the
 * builder in its own type keeps the truth-table together with its
 * justification (Spec 3 §4.3 + §4.4 + §4.5) and lets unit tests assert
 * each flag deliberately.
 *
 * # Flag table (Spec 3 §4.4 — every choice is documented)
 *
 * | Flag                          | Set? | Why                                                                          |
 * |-------------------------------|------|------------------------------------------------------------------------------|
 * | `FLAG_NOT_FOCUSABLE`          | YES  | Overlay must not steal keyboard focus — soft-IME continues to receive input. |
 * | `FLAG_NOT_TOUCH_MODAL`        | YES  | Touches outside the buttons pass through to the app underneath.              |
 * | `FLAG_LAYOUT_IN_SCREEN`       | YES  | Position anchor is the display edge, not the underlying decor frame.         |
 * | `FLAG_HARDWARE_ACCELERATED`   | YES  | Material elevation + ripple need HW layers to render cleanly.                |
 * | `FLAG_KEEP_SCREEN_ON`         | NO   | PipelineService holds the wake-lock; don't double-hold here.                 |
 * | `FLAG_SHOW_WHEN_LOCKED`       | NO   | Lock-screen overlay would be useless — no app to type into.                  |
 * | `FLAG_LAYOUT_NO_LIMITS`       | NO   | We want system bound-clamping (notch, status-bar).                           |
 * | `FLAG_DIM_BEHIND`             | NO   | Not modal — nothing to dim.                                                  |
 *
 * # Format
 *
 * [PixelFormat.TRANSLUCENT] honours the rounded-corner alpha mask in
 * [net.devemperor.dictate.R.drawable.overlay_background].
 *
 * # Window type
 *
 * - API ≥ 26: [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY].
 * - API < 26: deprecated [WindowManager.LayoutParams.TYPE_PHONE] —
 *   sufficient for our use-case (Spec 3 §4.3 fallback rationale).
 *
 * @see net.devemperor.dictate.state.render.overlay.OverlayBackend
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md §4.3 + §4.4 + §4.5
 */
interface OverlayLayoutParamsFactory {
    /**
     * Build a fresh [WindowManager.LayoutParams] instance. Each call
     * returns a new object so the caller can mutate `x`/`y`/`gravity`
     * without leaking state across attach cycles.
     */
    fun create(): WindowManager.LayoutParams
}

/**
 * Production [OverlayLayoutParamsFactory].
 *
 * @property ctx Android context — used to convert the fixed overlay
 *   width ([OVERLAY_WIDTH_DP]) from dp to px. Held privately to keep
 *   the factory `Context`-bound for the lifetime of the IME / Service
 *   that built it.
 */
class DefaultOverlayLayoutParamsFactory(
    private val ctx: Context,
) : OverlayLayoutParamsFactory {

    override fun create(): WindowManager.LayoutParams {
        val type: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags: Int = (
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )

        // 2026-05-22 — the WindowManager.LayoutParams.width is the
        // authoritative window width; a WRAP_CONTENT here makes the
        // WindowManager re-measure the card by content, so the
        // `overlay_root` XML `layout_width="156dp"` is silently ignored
        // (the card grew to ~4 buttons wide when the record-btn label
        // was long). Pin the window width to OVERLAY_WIDTH_DP so the
        // overlay is a stable 3-buttons-wide card. Height stays
        // WRAP_CONTENT (the 2-row layout's natural height is correct).
        val widthPx = (OVERLAY_WIDTH_DP * ctx.resources.displayMetrics.density)
            .toInt()

        return WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Anchor coordinates from the TOP|START corner — the drag
            // logic (added in C18) computes pixel offsets from that
            // origin, so the initial frame must match (R.19).
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // No slide-in / fade animation; the overlay appears
            // immediately when attached.
            windowAnimations = 0
        }
    }

    private companion object {
        /**
         * Fixed overlay window width in dp. Matches the 3-button card:
         * 3 × 48dp icon buttons + 2 × 6dp container padding = 156dp.
         * Kept in sync with `overlay_5button_layout.xml`'s
         * `overlay_root` `layout_width` (the XML value is the
         * layout-preview hint; this constant is the authoritative
         * runtime width — see the `create()` comment).
         */
        const val OVERLAY_WIDTH_DP = 156
    }
}
