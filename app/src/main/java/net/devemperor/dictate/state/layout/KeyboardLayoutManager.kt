package net.devemperor.dictate.state.layout

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
 * 2. **Picks** the active [LayoutMode] per emit via
 *    [LayoutCatalog.forKeyboard] for KEYBOARD mode, falling back to
 *    [LayoutCatalog.OVERLAY_5BUTTON] for WIDGET/HOVER.
 * 3. **Fans** each render-tick out to every attached [RenderBackend]
 *    (Spec 2 §4.1 / R.10 — multi-backend list, NOT a single
 *    `activeBackend` slot). A backend opts in to a specific
 *    [BackendType] via [RenderBackend.backendType]; backends whose type
 *    doesn't match the picked mode get skipped (a `null`-type backend
 *    sees every render, the `ContentAreaController` use-case).
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
 * @property catalog the data SoT for layout modes.
 * @property onAction click-sink — typically `orchestrator::dispatch`.
 *
 * @see net.devemperor.dictate.state.layout.LayoutCatalog
 * @see net.devemperor.dictate.state.layout.RenderBackend
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §4 + §4.1
 * @see docs/decisions/0004-ui-layout-catalog-motionlayout.md §3
 */
class KeyboardLayoutManager(
    private val catalog: LayoutCatalog,
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
        activeBackends.forEach { backend -> renderTo(backend, state) }
    }

    /**
     * Pick the active mode for the given state, considering ViewMode:
     *
     * - KEYBOARD → [LayoutCatalog.forKeyboard]
     * - WIDGET / HOVER → [LayoutCatalog.overlay5Button] (Spec 3)
     *
     * Exposed for tests and for backends that need to peek the mode
     * without going through a render-tick (e.g. instrumented testing).
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
        val mode = computeLayoutMode(state)
        // A backend with `backendType == null` consumes every mode
        // (ContentAreaController). Otherwise only the matching surface
        // receives the render-tick.
        if (backend.backendType == null || backend.backendType == mode.backend) {
            backend.render(state, mode)
        }
    }
}
