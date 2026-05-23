package net.devemperor.dictate.state.render

import android.view.View
import net.devemperor.dictate.core.ContentArea
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.WidgetOrigin
import net.devemperor.dictate.state.WidgetState
import net.devemperor.dictate.state.layout.BackendType
import net.devemperor.dictate.state.layout.LayoutMode
import net.devemperor.dictate.state.layout.RenderBackend

/**
 * Container-visibility RenderBackend (Spec 2 §4.1 / R.10).
 *
 * # Wiring status (post-CR-DEL — sole live owner)
 *
 * **Sole live owner of the ContentArea visibility axis.** Attached via
 * `KeyboardLayoutManager.attachBackend` (CR3), armed in CR4, and — now
 * that `KeyboardStateManager` is **deleted** (CR-DEL completed the D-13
 * migration) — this controller is the **only** writer of the
 * `main`/`qwertz`/`emoji` (+ the `editButtonsLl`) ContentArea axis. The
 * earlier "not yet attached / KSM still owns it / D-13 follow-up"
 * framing is historical: there is no `KeyboardStateManager` and no
 * parallel writer left. `ContentAreaControllerTest` covers the
 * contract.
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
 * The controller was **attached in CR3** but [gate]d **dormant** until
 * CR4: while the legacy `KeyboardStateManager.applyContentAreaVisibility`
 * was still the live writer of this axis (pre-CR-DEL), a real write
 * here would have double-written the container (silent flicker —
 * RR-2). When [gate] is dormant, [render] reports the *intended* write
 * to the audit ledger (the dormant-phase single-live-writer proof,
 * Spec 2 §10) but does NOT touch the view. CR4 [arm]ed the gate in the
 * same chunk it removed the legacy drive; CR-DEL then deleted
 * `KeyboardStateManager` entirely (this controller is now the sole
 * owner — see "Wiring status" above). A `null` gate = legacy "always
 * write" (the pre-CR3 contract;
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

        // 2026-05-23 — derive HIDDEN_STRIP override.
        //
        // While the user holds the floating widget overlay open
        // (`Visible(USER)`), the IME must collapse to a thin strip so
        // it doesn't visually compete with the widget. The override
        // only triggers for USER origin: a PIPELINE-origin widget
        // implies the IME-View is hidden anyway (W3 surfaced the
        // widget *because* `OnImeViewHidden` fired), so hiding the
        // keyboard would be redundant. The decision is pure render-
        // time derivation — `state.layout.contentArea` is untouched
        // so the user's pre-widget content area pops right back when
        // the widget is closed.
        val effectiveArea = if (state.widget is WidgetState.Visible &&
            state.widget.origin == WidgetOrigin.USER
        ) {
            ContentArea.HIDDEN_STRIP
        } else {
            state.layout.contentArea
        }

        writeVisibility(
            views.mainButtonsContainer,
            if (effectiveArea == ContentArea.MAIN_BUTTONS) View.VISIBLE else View.GONE,
        )
        writeVisibility(
            views.qwertzContainer,
            if (effectiveArea == ContentArea.QWERTZ) View.VISIBLE else View.GONE,
        )
        writeVisibility(
            views.emojiPickerContainer,
            if (effectiveArea == ContentArea.EMOJI_PICKER) View.VISIBLE else View.GONE,
        )
        // CR-DEL (RR-3 gap) — the 4th ContentArea axis Spec 2 §13 row 2
        // marks `editButtonsLl` BLEIBT (ContentArea-Achse). The (now
        // deleted) `KeyboardStateManager.applyContentAreaVisibility`
        // formerly owned it (visible iff MAIN_BUTTONS || QWERTZ);
        // relocated here verbatim so the kill-list class deleted with no
        // stranded visibility axis.
        // Nullable + null-skip so pre-CR-DEL tests constructing the 3-arg
        // holder stay byte-identical.
        views.editButtonsContainer?.let { editButtons ->
            writeVisibility(
                editButtons,
                if (effectiveArea == ContentArea.MAIN_BUTTONS ||
                    effectiveArea == ContentArea.QWERTZ
                ) {
                    View.VISIBLE
                } else {
                    View.GONE
                },
            )
        }
        // Minimal-strip view — the visible "the keyboard is hidden,
        // tap the widget" indicator. Sibling of the three real
        // containers; visible only in HIDDEN_STRIP. Nullable for the
        // same byte-identical-tests reason as `editButtonsContainer`.
        views.minimalStripView?.let { strip ->
            writeVisibility(
                strip,
                if (effectiveArea == ContentArea.HIDDEN_STRIP) View.VISIBLE else View.GONE,
            )
        }
    }

    /**
     * Route every visibility write through the [gate] (RR-2). When the
     * gate is dormant the intended write is recorded in the audit
     * ledger (the dormant-phase single-live-writer proof, used while
     * the legacy KSM still drove this axis pre-CR-DEL) but the view is
     * left untouched; when armed (CR4 onward) or absent (legacy
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
    /**
     * CR-DEL — the `edit_buttons_keyboard_ll` row (Spec 2 §13 row 2,
     * ContentArea-Achse). Visible iff `MAIN_BUTTONS || QWERTZ`. Nullable
     * with a `null` default so unit tests / callers that predate the 4th
     * axis keep compiling and behave byte-identically (the write is
     * skipped when absent). The IME service supplies the concrete view.
     */
    val editButtonsContainer: View? = null,
    /**
     * 2026-05-23 — `keyboard_minimal_strip` view. Visible iff the
     * derived [ContentArea.HIDDEN_STRIP] mode is active (user-toggled
     * widget overlay open). The IME service supplies the concrete view
     * from the layout. Nullable for the same byte-identical-tests
     * reason as [editButtonsContainer].
     */
    val minimalStripView: View? = null,
)
