@file:JvmName("SlotRenderer")

package net.devemperor.dictate.state.render

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.layout.ButtonSlot

/**
 * Apply a [ButtonSlot]'s resolvers to a concrete `android.view.View`.
 *
 * The **only** code path that translates `ButtonSlot` resolvers into
 * Android-view property writes — consumed by [ImeViewBackend] today and
 * by `OverlayBackend` once Spec 3 lands. A second copy of this logic in
 * the overlay backend would re-introduce the drift class that the
 * resend-btn bug (Spec 2 §1.1 #3b) was built on.
 *
 * # Why a top-level function (not a method on `RenderBackend`)?
 *
 * Backends use this helper, but **they don't share a base class** —
 * [ImeViewBackend] wraps a `MotionLayout`, `OverlayBackend` wraps a
 * `WindowManager`-attached `LinearLayout`. A free function is the
 * cleanest SSoT: zero inheritance, mockable from JVM tests by passing
 * a Fake `View` (the Material button branch is opt-in via the
 * `is MaterialButton` check).
 *
 * # Property ordering
 *
 * Visibility first, then `isEnabled` + `alpha`, then icon / text. The
 * order matters when MotionLayout is concurrently animating a
 * transition: setting visibility before MotionLayout-driven
 * `transitionToState` ensures the catalog's truth wins (with
 * `motion:visibilityMode="ignore"` per R.11 / Spec 2 §7.3) — a
 * delayed visibility write would race the animation.
 *
 * # Click listeners are NOT applied here
 *
 * The static handlers (click / long-click / touch) are wired exactly
 * once per backend `attach()` in `wireStaticHandlers()` and read
 * `stateRef` / `modeRef` at click time (L8 — forbidden pattern (l)).
 * That responsibility belongs to the calling backend, not this helper.
 *
 * @return `true` when the slot is visible (caller may need this for
 *   optional follow-up work like animation start/stop).
 *
 * @see net.devemperor.dictate.state.render.ImeViewBackend
 * @see net.devemperor.dictate.state.layout.ButtonSlot
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §5.1
 * @see docs/decisions/0004-ui-layout-catalog-motionlayout.md §3
 */
fun applySlotToView(
    slot: ButtonSlot,
    view: View,
    state: DictateUiState,
    ctx: Context,
): Boolean {
    val visible = slot.visibilityPredicate(state)
    view.visibility = if (visible) View.VISIBLE else View.GONE
    view.isEnabled = slot.enabledResolver(state)
    view.alpha = slot.alphaResolver(state)
    if (view is MaterialButton) {
        // B4-VAL F-28: cache last-applied @DrawableRes Int per View under
        // a stable tag-id so a `getDrawable(...)` allocation only happens
        // when the resource id actually changed (Spec 2 §11.5 — per-tick
        // allocation budget). The tag-id is declared in `res/values/ids.xml`.
        slot.iconResolver(state)?.let { iconRes ->
            val cached = view.getTag(R.id.slot_renderer_last_icon_res) as? Int
            if (cached != iconRes) {
                view.icon = ContextCompat.getDrawable(ctx, iconRes)
                view.setTag(R.id.slot_renderer_last_icon_res, iconRes)
            }
        }
        slot.textResolver(state)?.let { text ->
            // Same short-circuit for text — String objects are interned but
            // the assignment triggers MaterialButton internal relayout work.
            val cached = view.getTag(R.id.slot_renderer_last_text) as? CharSequence
            if (cached != text) {
                view.text = text
                view.setTag(R.id.slot_renderer_last_text, text)
            }
        }
    }
    return visible
}
