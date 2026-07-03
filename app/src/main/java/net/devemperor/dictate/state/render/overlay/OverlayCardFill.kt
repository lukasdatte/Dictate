package net.devemperor.dictate.state.render.overlay

import androidx.core.graphics.ColorUtils

/**
 * Single source of truth for the floating-overlay card's **fill colour**
 * as a function of the user's `Pref.WidgetOpacity` percentage.
 *
 * # Why this exists (2026-07-03 opacity-consistency fix)
 *
 * `Pref.WidgetOpacity` used to be applied by lowering the card fill's
 * *alpha channel* (`colorSurface` at `opacity * 255 / 100`). A
 * translucent fill composites against **whatever is behind the overlay
 * window**, and that backdrop is not constant across the two ViewModes
 * that share the card:
 *
 *  - **WIDGET** — the soft keyboard is visible; the card floats over the
 *    *opaque* keyboard background
 *    (`activity_dictate_keyboard_view.xml` →
 *    `@color/dictate_keyboard_background_light`).
 *  - **HOVER** — the keyboard is hidden; the card floats directly over
 *    *arbitrary host-app content*.
 *
 * Same alpha, different backdrop ⇒ the composited pixels differ, so the
 * widget looked more opaque over the keyboard than over a host app. The
 * user reported "the opacity is not identical between the widget mode
 * and the other mode — it should always be the same."
 *
 * # The fix: pre-compose to an opaque colour
 *
 * Instead of shipping a translucent fill and letting the GPU composite
 * it against an unknown backdrop, we **pre-blend** the surface colour
 * over a fixed opaque base *at build time* and paint the resulting
 * **fully-opaque** colour. Because the painted colour no longer has an
 * alpha channel, the on-screen result is byte-identical regardless of
 * what sits behind the window — WIDGET and HOVER now match exactly.
 *
 * The anchor is the **effective keyboard background colour**
 * (`dictate_keyboard_background_{light,dark}`, selected by the same
 * shared [net.devemperor.dictate.state.render.effectiveNight] rule the
 * keyboard surface uses at `DictateInputMethodService`'s theme site).
 * That reproduces, in every mode, exactly what the translucent card
 * used to look like in WIDGET mode floating over the keyboard — the
 * appearance the opacity slider was tuned against. At `opacity = 100`
 * the fill is the plain surface colour; as opacity drops it fades
 * toward the keyboard backdrop, so the card still reads progressively
 * "lighter / more washed-out" without ever being backdrop-dependent.
 *
 * **Trade-off (deliberate):** true see-through of host-app content in
 * HOVER mode is sacrificed. The user's explicit priority is "the
 * opacity must always be identical between modes", which a real alpha
 * channel cannot deliver (the backdrop genuinely differs per mode);
 * pre-composition is the only deterministic answer.
 *
 * # SSoT / testability
 *
 * Pure integer maths — no Android View. `OverlayBackend` resolves the
 * live `colorSurface` (theme-dependent) and the opaque base, then calls
 * [effectiveFill]. Tests assert the policy in isolation; the backend
 * test asserts the wired result on the real drawable.
 *
 * @see net.devemperor.dictate.state.render.overlay.OverlayBackend.applyBackgroundOpacity
 * @see docs/research/2026-07-02 - overlay-widget-transparency.md §7 (2026-07-03 opacity-consistency entry)
 */
object OverlayCardFill {

    /** Settings SeekBar floor (`fragment_preferences.xml`). */
    const val MIN_OPACITY_PERCENT: Int = 20

    /** Settings SeekBar ceiling — fully opaque surface. */
    const val MAX_OPACITY_PERCENT: Int = 100

    /**
     * The **opaque** colour to paint into the card fill for the given
     * [opacityPercent].
     *
     * @param surfaceColor the theme's `?attr/colorSurface` (opaque) — the
     *   card colour at 100 % opacity.
     * @param baseColor the opaque backdrop base the surface is blended
     *   toward as opacity drops. Production passes the effective
     *   keyboard background colour
     *   (`dictate_keyboard_background_{light,dark}` for the night mode
     *   the overlay was inflated with) so the result reproduces the
     *   familiar widget-over-keyboard appearance; the parameter is kept
     *   explicit so a future design can swap the anchor in one place.
     * @param opacityPercent user pref (clamped defensively to
     *   [MIN_OPACITY_PERCENT]..[MAX_OPACITY_PERCENT]; the SeekBar already
     *   enforces the range, but the SP value is writable via backup
     *   restore / adb).
     * @return a fully-opaque ARGB colour (alpha 0xFF) — never translucent,
     *   so the on-screen result is independent of the window backdrop.
     */
    fun effectiveFill(surfaceColor: Int, baseColor: Int, opacityPercent: Int): Int {
        val clamped = opacityPercent.coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)
        // blend ratio 1.0 = full surface (opaque), lower = fade toward base.
        val ratio = clamped / 100f
        val blended = ColorUtils.blendARGB(baseColor, surfaceColor, ratio)
        // Force opaque: blendARGB preserves alpha, and both inputs are
        // opaque, but guard against a translucent input leaking through.
        return blended or 0xFF000000.toInt()
    }
}
