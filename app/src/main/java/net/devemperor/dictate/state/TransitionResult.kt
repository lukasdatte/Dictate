package net.devemperor.dictate.state

/**
 * Result of a single [DictateModule.reduce] call.
 *
 * The reducer returns:
 * - [nextState] — the new sub-state value (`copy()`-derived; must NOT be the
 *   same instance unless the transition is a true no-op).
 * - [sideEffects] — a list of effect **plans** the orchestrator will execute
 *   after the state update. Effects are typed per-module
 *   (`E : SideEffect`), so the compiler enforces that reducers only emit
 *   their own module's effects.
 *
 * **Why a list (not a single effect)?** A single state transition can
 * legitimately require multiple effects (e.g. `Recording.Active → Idle`
 * stops the MediaRecorder, the timer, the amplitude stream, **and** the
 * border glow — Spec 1 §15.2). Forcing one effect per transition would
 * push that orchestration into the reducer, breaking SRP.
 *
 * **Why a `null` reducer return (not an empty `TransitionResult`)?** A
 * reducer that returns `null` signals "this action is not relevant in
 * the current state" → orchestrator returns
 * `DispatchOutcome.Rejected("reducer-null")`. An empty
 * `TransitionResult(state, emptyList())` would re-emit the same state
 * to the store, which is wasteful (subscribers fire on every emission
 * regardless of equality).
 *
 * @param S the module's sub-state type
 * @param E the module's effect interface (`sealed interface … : SideEffect`)
 *
 * @see net.devemperor.dictate.state.DictateModule
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"Pure-Reducer Invariant"
 */
data class TransitionResult<S, E : SideEffect>(
    val nextState: S,
    val sideEffects: List<E> = emptyList(),
)

/**
 * Read-only context handed to every [DictateModule.reduce] call.
 *
 * **Why an explicit ctx (not a function-shape change)?** Reducers
 * occasionally need cross-axis reads (e.g. `RecordingModule.reduce` reads
 * `ctx.global.audio.useBluetoothMic` to bake the bluetooth flag into the
 * new `RecordingState.Preparing`). The ctx makes those reads
 * type-safe + statically discoverable.
 *
 * **Why expose [global] (the full state) instead of single-axis getters?**
 * Cross-axis reads are part of the cross-module-coupling matrix
 * (Spec 1 §15.1.x) — making them explicit lets a code-reviewer audit
 * "which axes does RecordingModule read?" by grep'ing for
 * `ctx.global.` in the module file.
 *
 * **What [now] is for:** the **only** legal time source in a reducer.
 * Direct `System.currentTimeMillis()` calls inside `reduce` are
 * forbidden — they break test determinism. Tests inject a fixed `now`
 * via the ctx constructor.
 *
 * @property global complete state snapshot at reduce-time. Read-only by
 *   construction (`val`).
 * @property now monotonic timestamp in ms (default
 *   `System.currentTimeMillis()` at construction).
 *
 * @see net.devemperor.dictate.state.DictateModule
 */
data class ReducerContext(
    val global: DictateUiState,
    val now: Long = System.currentTimeMillis(),
)
