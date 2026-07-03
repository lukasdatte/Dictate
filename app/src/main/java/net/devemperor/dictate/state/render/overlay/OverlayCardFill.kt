package net.devemperor.dictate.state.render.overlay

import androidx.core.graphics.ColorUtils

/**
 * Single source of truth for the floating-overlay's **translucent
 * background fills** as a function of the user's `Pref.WidgetOpacity`
 * percentage — for the card surface AND every button container.
 *
 * # Contract (2026-07-03, third iteration)
 *
 * A background fill is its opaque base colour carrying a **real alpha
 * channel** (`opacity * 255 / 100`), and the **identical translucent
 * ARGB is applied in every mode** the overlay renders in (WIDGET and
 * HOVER). Two consumers share this one alpha derivation:
 *
 *  1. **The card fill** — base `colorSurface` ([effectiveFill]).
 *  2. **Every button container** — base `colorSecondaryContainer`, the
 *     overlay's *third* background colour (card surface = layer 1,
 *     record-button `colorPrimary` = layer 2, icon-button
 *     `colorSecondaryContainer` = layer 3). Per user request
 *     (2026-07-03) all overlay buttons now paint that third colour at
 *     the SAME slider opacity as the card, so the whole card — fill and
 *     buttons alike — dims uniformly. Only the container FILL is
 *     translucent; the icons/text keep full opacity (they are drawn by
 *     Material on top of the tint, untouched by this policy).
 *
 * Night-awareness needs no extra anchor here: the base colour the
 * backend passes in is resolved from the view's themed context, which
 * `OverlayBackend.inflateAndAttach` already night-overrides via the
 * shared `effectiveNight` rule (F-119) — a dark theme yields dark base
 * colours, and the alpha applies on top of whichever palette is active.
 *
 * # Decision history — why translucent, not an opaque pre-blend
 *
 * 1. **Original report:** "the opacity is not identical between the
 *    widget mode and the other mode." Investigation found no divergent
 *    code path — the same ARGB was applied everywhere. The *perceived*
 *    difference stems from alpha compositing: WIDGET floats over the
 *    opaque keyboard, HOVER over arbitrary host-app content; the same
 *    alpha byte composites to different on-screen pixels.
 * 2. **First fix (reverted):** pre-blend `colorSurface` over the
 *    keyboard background into a fully-opaque colour — byte-identical
 *    rendering in every mode, but it eliminated real see-through
 *    entirely. User verdict: "Jetzt haben wir gar keine Opacity mehr"
 *    (the slider had no visible effect). Trade-off rejected.
 * 3. **Current contract:** true translucency restored. "Identical in
 *    all modes" means the same translucent ARGB is *applied*
 *    consistently; backdrop-induced compositing differences are
 *    accepted as inherent to transparency.
 *
 * Full narrative:
 * `docs/research/2026-07-02 - overlay-widget-transparency.md` §7.
 *
 * # SSoT / testability
 *
 * Pure integer maths — no Android View. `OverlayBackend` resolves the
 * live `colorSurface` (theme-dependent) and calls [effectiveFill];
 * tests assert the policy in isolation, and the backend test asserts
 * the wired result on the real drawable in both modes.
 *
 * @see net.devemperor.dictate.state.render.overlay.OverlayBackend.applyBackgroundOpacity
 */
object OverlayCardFill {

    /** Settings SeekBar floor (`fragment_preferences.xml`). */
    const val MIN_OPACITY_PERCENT: Int = 20

    /** Settings SeekBar ceiling — fully opaque surface. */
    const val MAX_OPACITY_PERCENT: Int = 100

    /**
     * The **translucent** ARGB for an opaque [baseColor] at the given
     * [opacityPercent]: [baseColor] with its alpha channel set to
     * `clamped * 255 / 100`. The single "opacity percent → ARGB"
     * derivation shared by the card fill AND the button containers —
     * both call through here so there is exactly one alpha-mapping and
     * one clamping rule in the whole overlay.
     *
     * Only the alpha byte is touched; the RGB channels of [baseColor]
     * pass through unchanged (the icons/text drawn on top stay opaque —
     * this is a *fill* policy, never a foreground one).
     *
     * @param baseColor the opaque source colour (a theme attr resolved
     *   from the night-correct themed view context) — the fill at 100 %.
     * @param opacityPercent user pref (clamped defensively to
     *   [MIN_OPACITY_PERCENT]..[MAX_OPACITY_PERCENT]; the SeekBar
     *   already enforces the range, but the SP value is writable via
     *   backup restore / adb).
     * @return [baseColor] at the mapped alpha — fully opaque only at
     *   100 %, genuinely translucent below.
     */
    fun translucent(baseColor: Int, opacityPercent: Int): Int {
        val clamped = opacityPercent.coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)
        return ColorUtils.setAlphaComponent(baseColor, clamped * 255 / 100)
    }

    /**
     * The translucent colour to paint into the **card fill** for the
     * given [opacityPercent]: `colorSurface` at the shared slider alpha.
     * Thin alias over [translucent] kept for call-site readability and
     * backward compatibility; the card is layer 1 of the overlay's
     * background hierarchy.
     *
     * @param surfaceColor the theme's `?attr/colorSurface`, resolved
     *   from the night-correct themed view context — the card colour at
     *   100 % opacity.
     * @param opacityPercent see [translucent].
     * @return `colorSurface` at the mapped alpha (the whole point of the
     *   feature: host content stays visible through the card).
     */
    fun effectiveFill(surfaceColor: Int, opacityPercent: Int): Int =
        translucent(surfaceColor, opacityPercent)
}
