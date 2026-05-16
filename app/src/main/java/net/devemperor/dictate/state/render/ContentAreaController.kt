package net.devemperor.dictate.state.render

import android.view.View
import net.devemperor.dictate.core.ContentArea
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.layout.BackendType
import net.devemperor.dictate.state.layout.LayoutMode
import net.devemperor.dictate.state.layout.RenderBackend

/**
 * Container-visibility RenderBackend (Spec 2 §4.1 / R.10).
 *
 * # Wiring status (IMPL-STATE post-C15, B4-VAL F-6)
 *
 * **Not yet attached in production.** `DictateInputMethodService` only
 * attaches [ImeViewBackend] today; [net.devemperor.dictate.core.KeyboardStateManager.applyContentAreaVisibility]
 * continues to own this axis until the D-13 follow-up block migrates the
 * IME-side wiring. The unit tests in `ContentAreaControllerTest` exercise
 * the contract so the controller can be wired in one step once the
 * matching KSM method is removed.
 *
 * # Why a separate backend?
 *
 * The IME-View hosts **three mutually-exclusive content areas**: the
 * main button row ([ContentArea.MAIN_BUTTONS]), the QWERTZ sub-keyboard
 * ([ContentArea.QWERTZ]), and the emoji picker ([ContentArea.EMOJI_PICKER]).
 * That switch is a top-level container visibility decision — it has
 * **nothing** to do with which buttons live in the active layout mode.
 * Modelling it as resolvers on individual [net.devemperor.dictate.state.layout.ButtonSlot]s
 * would scatter the same "is QWERTZ visible?" predicate across every
 * single keyboard slot — the kind of redundancy [LayoutCatalog]
 * eliminated for button-level visibility.
 *
 * Per R.10 (Issue 2.1.15 Option B) the manager keeps a **list** of
 * active backends; a [ContentAreaController] is the second member next
 * to the [ImeViewBackend]. The two render in parallel each tick, each
 * owns its own concern, no Single-Source-of-Truth violation.
 *
 * # `backendType = null` — consume every mode
 *
 * The controller cares about `state.layout.contentArea` regardless of
 * which layout mode is active; the manager's filter (`backend.backendType
 * == null || ... == mode.backend`, [KeyboardLayoutManager.renderTo])
 * treats `null` as "every render-tick". This contrasts with
 * [ImeViewBackend] which only sees `IME_VIEW` modes.
 *
 * # Click-listeners
 *
 * The container views themselves have no click listeners — they are
 * pure visibility surfaces. The buttons **inside** the containers
 * (QWERTZ keys, emoji glyphs) are managed by their own click logic
 * inside the IME service (not yet migrated to the catalog — Phase 2
 * scope). [attach] therefore captures `onAction` only as future-proofing
 * for the eventual "close QWERTZ" / "back to main buttons" affordance.
 *
 * # CR3 staged-safety-net (render-path-cutover.md §6 RR-2)
 *
 * The controller is **attached in CR3** but [gate]d **dormant** until
 * CR4: while [net.devemperor.dictate.core.KeyboardStateManager.applyContentAreaVisibility]
 * is still the live writer of this axis, a real write here would
 * double-write the container (silent flicker — RR-2). When [gate] is
 * dormant, [render] reports the *intended* write to the audit ledger
 * (proving KSM is the sole live writer, Spec 2 §10) but does NOT touch
 * the view. CR4 [arm]s the gate in the same chunk it removes the KSM
 * drive. A `null` gate = legacy "always write" (the pre-CR3 contract;
 * keeps the existing unit tests' semantics).
 *
 * @property views container references — non-null for all three
 *   containers. The IME service builds the holder in `onCreateInputView`.
 * @property gate the dormant/armed staged-safety-net switch (RR-2).
 *   `null` = always write (legacy contract / unit tests).
 *
 * @see net.devemperor.dictate.state.layout.RenderBackend
 * @see net.devemperor.dictate.state.render.RenderGate
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §4.1
 * @see docs/decisions/0004-ui-layout-catalog-motionlayout.md §3
 */
class ContentAreaController(
    private val views: ContentAreaViews,
    private val gate: RenderGate? = null,
) : RenderBackend {

    /**
     * `null` = "consume every mode" — the manager fans every render-tick
     * out to this backend regardless of which surface the catalog
     * picked.
     */
    override val backendType: BackendType? = null

    @Suppress("unused")
    private var onAction: ((Action) -> Unit)? = null

    override fun attach(onAction: (Action) -> Unit) {
        this.onAction = onAction
        // No click-listeners to wire — the container views are pure
        // visibility surfaces (see class KDoc).
    }

    override fun detach() {
        onAction = null
    }

    override fun render(state: DictateUiState, @Suppress("UNUSED_PARAMETER") mode: LayoutMode) {
        // Mode is unused — content-area is orthogonal to LayoutMode by
        // design. We still receive it because [RenderBackend.render] is
        // a single contract for all backends.

        val area = state.layout.contentArea
        writeVisibility(
            views.mainButtonsContainer,
            if (area == ContentArea.MAIN_BUTTONS) View.VISIBLE else View.GONE,
        )
        writeVisibility(
            views.qwertzContainer,
            if (area == ContentArea.QWERTZ) View.VISIBLE else View.GONE,
        )
        writeVisibility(
            views.emojiPickerContainer,
            if (area == ContentArea.EMOJI_PICKER) View.VISIBLE else View.GONE,
        )
    }

    /**
     * Route every visibility write through the [gate] (RR-2). When the
     * gate is dormant the intended write is recorded in the audit
     * ledger (proving the legacy KSM is the sole live writer) but the
     * view is left untouched; when armed (CR4) or absent (legacy
     * contract) the real mutation happens.
     */
    private fun writeVisibility(view: View, target: Int) {
        if (gate == null) {
            view.visibility = target
            return
        }
        if (gate.shouldWrite(view.id, target)) {
            view.visibility = target
        }
    }
}

/**
 * Container view-holder for [ContentAreaController].
 *
 * Encapsulating the three views behind a typed data class makes the
 * controller's dependencies explicit and lets unit tests pass simple
 * fake-view instances without going through `findViewById`.
 *
 * The `View` supertype (rather than `ViewGroup` or a concrete type) is
 * deliberate: the controller writes `visibility` only, so the narrower
 * compile-time contract aids substitution under JVM unit tests.
 */
data class ContentAreaViews(
    val mainButtonsContainer: View,
    val qwertzContainer: View,
    val emojiPickerContainer: View,
)
