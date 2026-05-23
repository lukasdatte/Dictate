package net.devemperor.dictate.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single-source-of-truth container for [DictateUiState].
 *
 * Wraps a `MutableStateFlow<DictateUiState>` and exposes:
 * - Read-only [state] for subscribers.
 * - [update] for atomic compose-style mutation (only the orchestrator
 *   calls it).
 * - [snapshot] for synchronous reducer/cascade reads.
 *
 * **SRP:** the store is pure data. It has **no** Pref reads, no action
 * routing, no side-effect hooks. All semantics live in [DictateOrchestrator]
 * (Chunk C4) and the modules (Chunks C5/C6).
 *
 * **Single-mutation-channel invariant:** `_state` is private; `update()`
 * is the only mutator API. The orchestrator's `dispatchInternal` calls
 * `store.update { module.write(it, result.nextState) }` exactly once per
 * accepted action.
 *
 * **Threading:** `MutableStateFlow.update` is thread-safe (CAS-loop), but
 * the orchestrator caller is main-thread-confined per ADR-0001 §
 * "Main-thread confined dispatch". The store does not enforce the
 * main-thread requirement on its own — it can be safely accessed from
 * any thread, including for read-only `snapshot` peeks.
 *
 * @see net.devemperor.dictate.state.DictateUiState
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"State diagram"
 * @see docs/architecture/state-architecture/state-and-actions.md §1
 */
class DictateUiStateStore(initial: DictateUiState = DictateUiState.initial()) {

    private val _state = MutableStateFlow(initial)

    /** Read-only flow of state mutations. Subscribers observe via `state.collect { … }`. */
    val state: StateFlow<DictateUiState> = _state.asStateFlow()

    /**
     * Atomic compose-style mutation. The [reducer] takes the current
     * state and returns the next state.
     *
     * This is the **only** public mutation API on the store. The
     * orchestrator's `dispatchInternal` invokes it once per accepted
     * action with `module.write(it, result.nextState)`.
     */
    fun update(reducer: (DictateUiState) -> DictateUiState) {
        _state.update(reducer)
    }

    /**
     * Synchronous snapshot of the current state — used by the orchestrator
     * for reducer/cascade reads (`prevGlobal` / `nextGlobal`).
     *
     * **NOT for view updates.** Views subscribe via [state] so they see
     * every emission; a snapshot read would race with concurrent
     * mutations.
     */
    val snapshot: DictateUiState get() = _state.value
}
