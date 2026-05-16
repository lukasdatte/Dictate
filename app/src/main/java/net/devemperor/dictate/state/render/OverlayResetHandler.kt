package net.devemperor.dictate.state.render

import android.view.View
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.layout.BackendType
import net.devemperor.dictate.state.layout.LayoutMode
import net.devemperor.dictate.state.layout.RenderBackend

/**
 * RenderBackend that resets IME-View widgets which the overlay surface
 * controls during WIDGET/HOVER mode (Spec 2 §4.1 / R.10).
 *
 * # Wiring status (IMPL-STATE post-C15, B4-VAL F-6)
 *
 * **Not yet attached in production.** The
 * `overlay_characters_ll.visibility = View.GONE` line inside
 * [net.devemperor.dictate.core.KeyboardStateManager.applyVisibility]
 * continues to own this defensive reset until the D-13 follow-up block
 * migrates the IME-side wiring. `OverlayResetHandlerTest` exercises the
 * contract so the handler can be wired in one step once the matching
 * KSM line is removed.
 *
 * # The reset concern
 *
 * Two IME-View widgets are mutated by overlay-side logic:
 *
 *  - **`overlay_characters_ll`** — the enter-key character overlay
 *    surfaces. The `EnterOverlayHandler` (Spec 2 §11.7) shows it on a
 *    long-press of `enter_btn` and hides it on release. The handler
 *    runs **only while the keyboard is in KEYBOARD ViewMode** —
 *    when the user switches to WIDGET/HOVER (ViewMode transition),
 *    the handler can't observe an explicit "I'm closing" signal and
 *    the surface stays VISIBLE if a long-press was mid-flight at the
 *    moment of transition. The legacy code re-applied `GONE` after
 *    every visibility pass (`KeyboardStateManager.applyVisibility`);
 *    this handler is the replacement.
 *
 * # When this backend fires
 *
 * `backendType = null` consumes every render-tick. The reset writes
 * are idempotent — once `overlay_characters_ll.visibility` is `GONE`,
 * setting `GONE` again is a no-op. The cost is one branch + one
 * setter per tick.
 *
 * # Why "ResetHandler" and not "OverlayBackend"?
 *
 * The actual floating overlay window lives behind `OverlayBackend`
 * (Spec 3 §4 / B5) — that backend manages the WindowManager-bound
 * 5-button surface. This handler exists for an orthogonal concern:
 * keeping IME-View widgets in a known state when the overlay is up.
 * Splitting them keeps each backend's responsibility crisp (SRP).
 *
 * # CR3 staged-safety-net (render-path-cutover.md §6 RR-2)
 *
 * Attached in CR3 but [gate]d **dormant** until CR4: the
 * `overlay_characters_ll.visibility = GONE` defensive reset is still
 * driven by [net.devemperor.dictate.core.KeyboardStateManager.applyVisibility]
 * (line ~142) until CR4. Dormant → report the intended reset to the
 * audit ledger (proves KSM is the sole live writer, Spec 2 §10), do
 * not touch the view. CR4 [arm]s the gate in the same chunk it removes
 * the KSM reset line. `null` gate = legacy always-write (unit-test
 * contract). Same pattern as [ContentAreaController].
 *
 * @property views the IME-side widgets that need an overlay-aware
 *   reset. Nullable members are skipped (defensive — re-skin variants
 *   may not include every widget).
 * @property gate the dormant/armed staged-safety-net switch (RR-2).
 *   `null` = always write (legacy contract / unit tests).
 *
 * @see net.devemperor.dictate.state.layout.RenderBackend
 * @see net.devemperor.dictate.state.render.RenderGate
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §4.1
 */
class OverlayResetHandler(
    private val views: OverlayResetViews,
    private val gate: RenderGate? = null,
) : RenderBackend {

    override val backendType: BackendType? = null

    @Suppress("unused")
    private var onAction: ((Action) -> Unit)? = null

    override fun attach(onAction: (Action) -> Unit) {
        this.onAction = onAction
    }

    override fun detach() {
        onAction = null
    }

    override fun render(
        @Suppress("UNUSED_PARAMETER") state: DictateUiState,
        @Suppress("UNUSED_PARAMETER") mode: LayoutMode,
    ) {
        // The overlay-characters strip is **always** invisible at the
        // start of any state-driven render-tick. The
        // [net.devemperor.dictate.keyboard.EnterOverlayHandler] sets it
        // VISIBLE while a touch sequence is active and hides it on
        // ACTION_UP / ACTION_CANCEL; this defensive reset catches the
        // edge case where a ViewMode transition (KEYBOARD → WIDGET/HOVER)
        // interrupts the touch sequence and the handler never observes
        // the matching release event.
        val strip = views.overlayCharactersStrip ?: return
        if (gate == null) {
            strip.visibility = View.GONE
            return
        }
        if (gate.shouldWrite(strip.id, View.GONE)) {
            strip.visibility = View.GONE
        }
    }
}

/**
 * View-holder for [OverlayResetHandler].
 *
 * Today this is a one-field DTO; the type stays for extensibility —
 * future overlay-side widgets that need IME-View reset hooks (e.g.
 * Phase 2's split-bar) will land as additional fields without
 * touching the controller signature.
 */
data class OverlayResetViews(
    val overlayCharactersStrip: View?,
)
