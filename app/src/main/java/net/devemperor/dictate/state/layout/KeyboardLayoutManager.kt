package net.devemperor.dictate.state.layout

import net.devemperor.dictate.core.audit.VisibilityWriteAuditLogger
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.ViewMode

/**
 * Render-orchestrator for the keyboard surfaces.
 *
 * The manager:
 *
 * 1. **Subscribes** to the orchestrator-emitted `DictateUiState` flow
 *    (wired in C15) and re-renders on every emit.
 * 2. **Picks** the [LayoutMode] **per backend** (not per emit). Since the
 *    bidirectional render-sync change on 2026-05-21:
 *    - `BackendType.IME_VIEW` backends always receive
 *      [LayoutCatalog.forKeyboard]`(state)`.
 *    - `BackendType.OVERLAY_WINDOW` backends always receive
 *      [LayoutCatalog.OVERLAY_5BUTTON].
 *    - `backendType == null` cross-cutting backends (e.g.
 *      [net.devemperor.dictate.state.render.ContentAreaController])
 *      receive [computeLayoutMode]`(state)`.
 *
 *    `state.viewMode` no longer gates which surface renders. Both
 *    surfaces render on every state-emit and reflect the same live
 *    `DictateUiState`. See ADR-0005 Decision-History 2026-05-21.
 * 3. **Fans** each render-tick out to every attached [RenderBackend]
 *    (Spec 2 §4.1 / R.10 — multi-backend list, NOT a single
 *    `activeBackend` slot). Every backend gets its render-tick; the
 *    [BackendType] only chooses *which* mode each backend sees.
 *
 * # Why a list of backends (not a single active one)?
 *
 * The §4 single-backend code-snippet in Spec 2 is **pedagogical** — the
 * production contract sits in §4.1 (R.10, C-4 F-6 Implementer-Anker):
 * `ImeViewBackend` and `ContentAreaController` are simultaneously
 * attached during normal IME runs, and Spec 3 adds `OverlayBackend` as a
 * third member when WIDGET/HOVER is active. A single-slot design would
 * force one backend to know about the others; the list keeps each
 * backend's concern clean.
 *
 * # Single click-sink
 *
 * The manager owns one `onAction: (Action) -> Unit` callback wired into
 * the orchestrator's `dispatch(action)`. It's passed to every backend's
 * [RenderBackend.attach] — backends turn user clicks into `Action`s and
 * push them through the same single pipe (F-8 Single-Dispatch).
 *
 * # Visibility-write audit boundary (CR3, Spec 2 §10 / §11.8 5c)
 *
 * Each [onStateChanged] fan-out is one "render generation". When a
 * [VisibilityWriteAuditLogger] is supplied (CR3 staged cutover), the
 * manager opens a fresh generation before fanning out so the
 * Strict-Mode no-double-write ledger keys per state-emit (RR-2). A
 * `null` logger (tests / release without the audit) is a no-op — the
 * fan-out is unchanged.
 *
 * @property catalog the data SoT for layout modes.
 * @property onAction click-sink — typically `orchestrator::dispatch`.
 * @property visibilityAuditLogger optional Strict-Mode ledger (CR3);
 *   `null` = no audit (unchanged fan-out).
 *
 * @see net.devemperor.dictate.state.layout.LayoutCatalog
 * @see net.devemperor.dictate.state.layout.RenderBackend
 * @see net.devemperor.dictate.core.audit.VisibilityWriteAuditLogger
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §4 + §4.1 + §10 + §11.8
 * @see docs/decisions/0004-ui-layout-catalog-motionlayout.md §3
 */
class KeyboardLayoutManager(
    private val catalog: LayoutCatalog,
    // `visibilityAuditLogger` sits BEFORE `onAction` so `onAction`
    // stays the last parameter — the codebase constructs the manager
    // with the trailing-lambda idiom `KeyboardLayoutManager(catalog) {
    // ... }`. The audit logger has a default so that call-site is
    // unchanged (CR3 — Spec 2 §10 / §11.8 5c).
    private val visibilityAuditLogger: VisibilityWriteAuditLogger? = null,
    private val onAction: (Action) -> Unit,
) {

    // Mutable list of currently-attached backends. Kept as a private field
    // so external code MUST go through attachBackend / detachBackend —
    // direct mutation would break the attach/detach invariant
    // (each backend gets exactly one attach + one detach).
    private val activeBackends: MutableList<RenderBackend> = mutableListOf()

    /** Last state snapshot — re-emitted to newly attached backends. */
    private var currentState: DictateUiState? = null

    /**
     * Attach a render backend.
     *
     * Wires [onAction] into the backend via [RenderBackend.attach], then
     * — if a state snapshot is already available — synchronously renders
     * the current state to the newly attached backend so it doesn't show
     * an uninitialised UI for one frame.
     *
     * Attaching the same backend twice is a programming error and
     * raises `IllegalStateException`; detach first if you mean to swap.
     */
    fun attachBackend(backend: RenderBackend) {
        check(backend !in activeBackends) {
            "Backend $backend is already attached; detach before re-attaching."
        }
        activeBackends.add(backend)
        backend.attach(onAction)
        // Immediate first render if state is already known. Without this,
        // a backend attached mid-session would stay blank until the next
        // state emit — bad for the ContentAreaController case where the
        // ime-view is re-inflated after rotation.
        currentState?.let { state -> renderTo(backend, state) }
    }

    /**
     * Detach a render backend — symmetric counterpart to [attachBackend].
     *
     * Detaching a backend that isn't currently attached is a no-op
     * (defensive: lifecycle owners may not know the exact bind/unbind
     * order in error paths).
     */
    fun detachBackend(backend: RenderBackend) {
        if (activeBackends.remove(backend)) {
            backend.detach()
        }
    }

    /**
     * Detach every currently-attached backend. Called at IME teardown
     * to release View references and click-listeners deterministically.
     */
    fun detachAll() {
        // Snapshot before iterating so the loop doesn't co-mutate.
        val snapshot = activeBackends.toList()
        activeBackends.clear()
        snapshot.forEach { it.detach() }
    }

    /**
     * Receive a new state snapshot. Idempotent — re-emitting the same
     * state triggers re-render, but well-behaved backends recognise the
     * no-op and produce no visible change.
     */
    fun onStateChanged(state: DictateUiState) {
        currentState = state
        // CR3 (Spec 2 §10 / §11.8 5c): one render generation per
        // state-emit. The Strict-Mode ledger keys per generation so an
        // idempotent re-render by the same axis owner is not mistaken
        // for a double-write (RR-2). No-op when no logger is wired.
        visibilityAuditLogger?.beginRenderGeneration()
        activeBackends.forEach { backend -> renderTo(backend, state) }
    }

    /**
     * Pick the "primary" mode for the given state, considering ViewMode:
     *
     * - KEYBOARD → [LayoutCatalog.forKeyboard]
     * - WIDGET / HOVER → [LayoutCatalog.OVERLAY_5BUTTON] (Spec 3)
     *
     * Since the bidirectional render-sync change on 2026-05-21, the
     * render-fan-out no longer goes through this function — it picks
     * a mode per backend via [modeForBackend]. This function is still
     * exposed because:
     *
     * 1. `backendType == null` cross-cutting backends (the
     *    [net.devemperor.dictate.state.render.ContentAreaController]
     *    pattern) want a single "current" mode; they consume whatever
     *    the manager considers active for the global `viewMode`.
     * 2. Instrumented tests and external callers may want to peek the
     *    active mode without going through a render-tick.
     *
     * The semantic shift: this is now an "informational" selector, not
     * the gate that decides whether a typed backend renders.
     */
    fun computeLayoutMode(state: DictateUiState): LayoutMode = when (state.viewMode) {
        ViewMode.KEYBOARD -> catalog.forKeyboard(state)
        ViewMode.WIDGET, ViewMode.HOVER -> catalog.OVERLAY_5BUTTON
    }

    /**
     * Number of currently-attached backends. Test-only; production code
     * should not branch on this.
     */
    @Suppress("unused")
    internal fun attachedBackendCount(): Int = activeBackends.size

    // ─── Internal ─────────────────────────────────────────────────────

    private fun renderTo(backend: RenderBackend, state: DictateUiState) {
        // Per backend, pick the mode the backend was built to consume.
        // The pre-2026-05-21 design gated rendering on `state.viewMode`
        // matching `backend.backendType`, which was the structural
        // implementation of ADR-0005's "KEYBOARD ↔ WIDGET mutually
        // exclusive" assumption. The user-confirmed UX model now wants
        // both surfaces visible and interactive simultaneously, so the
        // global viewMode no longer chooses *whether* a backend renders
        // — only *which* mode it receives. See ADR-0005 §"Decision
        // History" 2026-05-21.
        val mode = modeForBackend(backend, state)
        backend.render(state, mode)
    }

    private fun modeForBackend(backend: RenderBackend, state: DictateUiState): LayoutMode =
        when (backend.backendType) {
            BackendType.IME_VIEW -> catalog.forKeyboard(state)
            BackendType.OVERLAY_WINDOW -> catalog.OVERLAY_5BUTTON
            null -> computeLayoutMode(state)
        }
}
