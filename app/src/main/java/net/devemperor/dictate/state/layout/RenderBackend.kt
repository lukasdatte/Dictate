package net.devemperor.dictate.state.layout

import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState

/**
 * The single render-surface mutation contract.
 *
 * Backends translate a `(state, mode)` pair into Android-view property
 * writes. The [KeyboardLayoutManager] keeps **a list** of attached
 * backends (Spec 2 §4.1 / R.10 — C-4 F-6) and fans each render-tick out to
 * every member — `ImeViewBackend` + `ContentAreaController` are both
 * attached during normal IME runs, and Spec 3 adds `OverlayBackend` as a
 * third member when WIDGET/HOVER is active.
 *
 * # Attach/detach lifecycle
 *
 * - [attach] is called **once** per surface (re)inflate, with the click-
 *   sink that turns `actionResolver` returns into orchestrator dispatches.
 *   Backends wire static handlers (click / long-click / touch) here — NOT
 *   in [render] — so each click-listener lambda lives for the surface's
 *   whole lifetime and reads `stateRef`/`modeRef` as single-source fields
 *   (forbidden pattern (l), Spec 2 §6 L8).
 * - [render] is called per state-emit. Backends iterate the active
 *   [LayoutMode]'s slots, evaluate resolvers against [state], and apply
 *   the resulting properties to their views.
 * - [detach] tears down listeners and releases View references.
 *
 * # Multi-backend rationale (R.10)
 *
 * Container-visibility (e.g. `mainButtonsCl` vs `qwertz_container` vs
 * `emojiPicker_container`) is **orthogonal** to button-level visibility —
 * it belongs to the `state.layout.contentArea` axis, not to a slot
 * resolver. Modelling container toggles as a separate `ContentAreaController`
 * backend (a [RenderBackend] in its own right) keeps the slot resolvers
 * concern-pure ("which buttons should I show inside the main-buttons
 * container?") while the container controller answers "which container is
 * the user looking at right now?". The manager attaches both and lets
 * each react to the same state emit.
 *
 * # null-action contract (R.3)
 *
 * Click handlers call `slot.actionResolver(state, services)?.let { onAction(it) }`
 * — a `null` resolver-return is a silent no-op (no log, no
 * `DispatchOutcome.Rejected`). See [ButtonSlot.actionResolver] KDoc.
 *
 * @see net.devemperor.dictate.state.layout.KeyboardLayoutManager
 * @see net.devemperor.dictate.state.layout.ButtonSlot
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §4 + §5
 * @see docs/decisions/0004-ui-layout-catalog-motionlayout.md §3
 */
interface RenderBackend {

    /**
     * Which render surface this backend serves. The
     * [KeyboardLayoutManager] uses this to filter the fan-out: a backend
     * only sees layout modes whose [LayoutMode.backend] matches.
     *
     * `null` means "consume every layout mode" — used for cross-cutting
     * backends like `ContentAreaController` whose work depends on the
     * `state.layout.contentArea` axis rather than on a specific layout
     * mode.
     */
    val backendType: BackendType?

    /**
     * Wire up the backend's static handlers (click / long-click / touch)
     * and capture the [onAction] sink for resolver-emitted actions.
     *
     * Called **once** per surface lifetime. Calling [attach] a second time
     * without an intervening [detach] is undefined behaviour — most
     * backend implementations will either ignore it or throw, but the
     * contract here is "the manager will not do this".
     */
    fun attach(onAction: (Action) -> Unit)

    /**
     * Tear down handlers and release View references.
     *
     * After [detach] the backend MUST NOT call the previously-supplied
     * `onAction` sink (it's been nulled out by the manager). Click
     * listeners that survive on the detached View read `stateRef == null`
     * and short-circuit (Spec 2 §6).
     */
    fun detach()

    /**
     * Apply [state] + [mode] to the backend's views.
     *
     * The backend MUST be idempotent: re-calling [render] with the same
     * pair must produce no visible change. This keeps the multi-backend
     * fan-out safe — a state emit triggers `render` on every attached
     * backend, and only the ones that actually moved produce visible
     * updates.
     *
     * Implementations are expected to bail (or render to a "neutral"
     * surface) when [LayoutMode.backend] does NOT match their
     * [backendType]. The manager pre-filters in production, but a
     * misroute is a programming error worth catching with a `require`.
     */
    fun render(state: DictateUiState, mode: LayoutMode)
}
