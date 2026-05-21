package net.devemperor.dictate.state.render.overlay

import android.view.View

/**
 * Renders the **in-IME overlay-permission info-bar** visibility from
 * `state.overlay.onboardingPending`
 * (indirection-cleanup 2026-05-21, Chunk 4.1 — B-1 + C-7).
 *
 * # Why this exists
 *
 * Before Chunk 4.1 the IME service inflated the info-bar view, then
 * the `OverlayOnboardingObserver` callback did `infobar.setVisibility(
 * pending ? VISIBLE : GONE)` *inline* — a direct View mutation outside
 * the documented render-owner list (AC-6 violation). The catalog
 * AUDIO_FOCUS slot's `iconResolver` is the model: each render axis has
 * a single owner that the observer feeds. This renderer is that owner
 * for the overlay-permission-info-bar visibility axis.
 *
 * # Scope
 *
 * Visibility only. The grant + dismiss button click listeners stay on
 * the IME's [DictateInputMethodService] — they are 1-hop `dispatch(...)`
 * paths per [docs/plans/.../research/](C-8 legitimate). This renderer is a
 * write-only owner of one Boolean → View.VISIBLE/View.GONE mapping.
 *
 * Idempotent: re-applying the same boolean is a `View.setVisibility`
 * no-op at the framework level when the visibility flag is unchanged
 * (the existing `OverlayOnboardingObserver` already collapses
 * duplicates via `distinctUntilChanged`, so the renderer rarely sees
 * a redundant call).
 *
 * # Lifecycle
 *
 * The IME constructs the renderer with the inflated info-bar View
 * after `onCreateInputView`. The shared
 * [net.devemperor.dictate.core.OverlayOnboardingObserver] passes its
 * `pending` callback to the renderer's [apply]. On view-recreate the
 * IME drops the renderer instance and rebuilds it against the fresh
 * View tree.
 */
class OverlayPermissionInfobarRenderer(
    private val infobar: View,
) {
    /**
     * Apply the requested visibility to the info-bar.
     *
     * @param pending `true` → `View.VISIBLE`, `false` → `View.GONE`.
     */
    fun apply(pending: Boolean) {
        infobar.visibility = if (pending) View.VISIBLE else View.GONE
    }
}
