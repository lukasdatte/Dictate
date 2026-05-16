package net.devemperor.dictate.core

import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Standalone helper owning the two `edit_numbers_btn` animations
 * extracted from `MainButtonsController` (behaviour group G15, Spec 2
 * §9.2 rows `:424-437` / `:452-477` — *"bleibt erhalten — externe
 * Animation auf `edit_numbers_btn`, kein Slot-Resolver. Wird als
 * `EditNumbersAnimator`-Helper extrahiert."*).
 *
 * # Why a separate helper (not a slot resolver)
 *
 * This is an **external animation** on a single button, not a
 * state-derived render property — it has no place in the
 * pure-resolver [net.devemperor.dictate.state.layout.ButtonSlot] model
 * (Spec 2 §9.2 explicitly excludes it). Extracting it as its own helper
 * (the same pattern Spec 2 §9.x uses for
 * [net.devemperor.dictate.state.render.RecordingAnimationController] and
 * the `KeyPressAnimator`) keeps `MainButtonsController` shrinking toward
 * deletion (CR-DEL) without stranding the animation: the IME service
 * holds this helper directly and drives it from the same call-sites that
 * used to call `mainButtonsController.animateSmallModeToggle` /
 * `animateEditNumbersBounce`.
 *
 * # Decoupled inputs
 *
 * The legacy methods read `stateManager.isSmallMode` and
 * `sp.get(Pref.Animations)` directly. The helper takes those as
 * **suppliers** so it depends on neither `KeyboardStateManager` (on the
 * CR-DEL kill-list) nor `SharedPreferences` directly — the caller wires
 * the live sources. This keeps the helper a pure view-animation unit and
 * survives the controller deletion unchanged.
 *
 * @property editNumbersButton the `edit_numbers_btn` view to animate.
 * @property animationsEnabled supplier for `Pref.Animations`; when
 *   `false` the animations collapse to an instant set (no tween) — the
 *   layout change itself is the user-visible feedback.
 * @property isSmallMode supplier for the current small-mode flag (the
 *   rotation target depends on it: 180° in small-mode, 0° otherwise).
 *
 * @see net.devemperor.dictate.core.MainButtonsController
 * @see net.devemperor.dictate.keyboard.KeyPressAnimator
 * @see docs/plans/2026-05-15 - dictate-cutover-completion/research/render-path-cutover.md §3 G15 / §7 A2
 */
class EditNumbersAnimator(
    private val editNumbersButton: View,
    private val animationsEnabled: () -> Boolean,
    private val isSmallMode: () -> Boolean,
) {

    /**
     * Rotate `edit_numbers_btn` to reflect the small-mode toggle
     * (extracted verbatim from `MainButtonsController.animateSmallModeToggle`,
     * `:424-437`). 180° when small-mode is on, 0° otherwise; tweened over
     * 200 ms when [animationsEnabled] is `true`, set instantly otherwise.
     *
     * @param animate request the tween (still gated by [animationsEnabled]).
     */
    fun animateSmallModeToggle(animate: Boolean) {
        val target = if (isSmallMode()) 180f else 0f
        if (animate && animationsEnabled()) {
            editNumbersButton.animate()
                .rotation(target)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            editNumbersButton.rotation = target
        }
    }

    /**
     * Visual feedback for the SingleRowMode long-press toggle (extracted
     * verbatim from `MainButtonsController.animateEditNumbersBounce`,
     * `:452-477`).
     *
     * Quality-Gate K6 (preserved from the legacy KDoc): a naive 180°
     * rotation would clash with [animateSmallModeToggle] — both would
     * fight over the same `rotation` axis and the end state would depend
     * on toggle order. Instead the long-press uses a horizontal
     * `translationX` bounce (±8 dp, ~200 ms total, end state
     * `translationX = 0f`) so click and long-press animations stay
     * orthogonal.
     *
     * When [animationsEnabled] is `false` the call is a no-op — the
     * layout change itself is the user-visible feedback.
     */
    fun animateEditNumbersBounce() {
        if (!animationsEnabled()) return
        val density = editNumbersButton.resources.displayMetrics.density
        val offset = 8f * density // 8dp in pixels
        val btn = editNumbersButton
        // Cancel any in-flight animation so back-to-back long-presses do
        // not accumulate translationX drift.
        btn.animate().cancel()
        btn.translationX = 0f
        btn.animate()
            .translationX(offset)
            .setDuration(70)
            .withEndAction {
                btn.animate()
                    .translationX(-offset)
                    .setDuration(70)
                    .withEndAction {
                        btn.animate()
                            .translationX(0f)
                            .setDuration(60)
                            .start()
                    }
                    .start()
            }
            .start()
    }
}
