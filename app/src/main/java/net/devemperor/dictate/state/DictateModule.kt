package net.devemperor.dictate.state

import kotlin.reflect.KClass

/**
 * Plugin contract for a Dictate state-mutation module.
 *
 * Each module owns **one** sub-state axis of [DictateUiState] and provides
 * the full mutation lifecycle for that axis: the reducer
 * ([reduce] + [reduceFailure]), the effect handler ([runEffect]), an
 * optional cross-module observer ([onCrossModuleStateChange]), and the
 * lens that the orchestrator uses to swap sub-state into the global state
 * ([read] / [write]).
 *
 * **Type parameters:**
 *
 * - [S] — the sub-state type this module owns (e.g. `RecordingState`).
 * - [A] — the module-scoped sealed Action class (e.g.
 *   `Action.RecordingAction`).
 * - [E] — the module-scoped sealed Effect interface
 *   (e.g. `RecordingModule.Effect`, all variants extending [SideEffect]).
 *
 * **Why `sealed interface`?** Compile-time-known module population.
 * The orchestrator's `KClass<out Action>`-Lookup is built at init from
 * registered modules; making the contract `sealed` lets the compiler
 * (and a maintainer) reason about "all the modules that exist".
 *
 * **Why an `object`-singleton per module?** Modules are stateless — their
 * "state" is the lens read of the global [DictateUiState]. Singleton +
 * immutable property fields keep the per-module memory footprint at one
 * object regardless of how many bind/unbind cycles the service goes
 * through.
 *
 * **Pure-reducer invariant (F1+F2, binding contract):**
 *
 * [reduce] is a pure function on `(S, A, ReducerContext) →
 * TransitionResult<S, E>?`. Hardware/IO/threading/`else`-branches over
 * sealed [A]s are forbidden (forbidden patterns (b)+(c) — see
 * `docs/architecture/state-architecture/forbidden-patterns.md`).
 * Cross-axis writes are forbidden by the lens (the reducer signs on
 * `S`, not on [DictateUiState] — writing `state.audio = …` is a compile
 * error from `RecordingModule.reduce`).
 *
 * **Mandatory vs. optional members:**
 *
 * | Member | Mandatory? | Default |
 * |--------|------------|---------|
 * | [id], [actionClass] | yes | — |
 * | [read], [write], [initialState] | yes | — |
 * | [reduce], [runEffect] | yes | — |
 * | [reduceFailure] | no | `null` (no failure recovery) |
 * | [onCrossModuleStateChange] | no | empty list (no cascade) |
 * | [prefBindings] | no | empty list (Phase-1 stays empty — see [PrefBinding] KDoc) |
 * | [terminate] | no | no-op |
 *
 * @see net.devemperor.dictate.state.DictateUiState
 * @see net.devemperor.dictate.state.Action
 * @see net.devemperor.dictate.state.SideEffect
 * @see net.devemperor.dictate.state.ModuleServices
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §3 + §4 + §5
 * @see docs/decisions/0002-state-cross-module-cascade.md §"Cross-module modes"
 * @see docs/architecture/state-architecture/modules.md §3
 */
sealed interface DictateModule<S, A : Action, E : SideEffect> {

    /** Stable identifier for the module — used for logging, debugging, and
     *  [Action.EffectFailure.originModuleId]-based routing. */
    val id: ModuleId

    /**
     * The Action class this module owns. Used by `DictateOrchestrator` for
     * type-safe O(1) action routing via `KClass.sealedSubclasses`.
     *
     * Duplicate registration is an init-time error (see
     * `DictateModuleRegistry.init` block in C4).
     */
    val actionClass: KClass<A>

    // ─── State Lens (read / write the sub-state in DictateUiState) ────

    /** Read this module's sub-state from the global state ("raus" lens). */
    fun read(global: DictateUiState): S

    /** Write this module's sub-state back into the global state ("rein" lens). */
    fun write(global: DictateUiState, sub: S): DictateUiState

    /** Initial value for this module's sub-state (used at boot). */
    fun initialState(): S

    // ─── Reducer (F1+F2 Pure Function) ────────────────────────────────

    /**
     * Pure-function reducer: `(sub-state, action, ctx) → (next-sub-state,
     * side-effects)`.
     *
     * **Contract:**
     * - Deterministic — equal inputs ⇒ equal outputs.
     * - May read [ReducerContext.global] for cross-axis facts; may NOT
     *   write any axis other than [S].
     * - May NOT call hardware (`MediaRecorder.prepare()`,
     *   `audioFile.exists()`), threading primitives, the store directly,
     *   or `dispatch()`.
     * - Returns `null` to signal "action not relevant in current state"
     *   → orchestrator emits `DispatchOutcome.Rejected("reducer-null")`.
     *   This is **normal**, not a bug (e.g. clicking Send while
     *   Recording is `Idle`).
     * - The `when (action)` block MUST be expression-form over the sealed
     *   [A] — no `else` branch (forbidden pattern (c)).
     */
    fun reduce(state: S, action: A, ctx: ReducerContext): TransitionResult<S, E>?

    /**
     * Failure-reducer for [Action.EffectFailure]. Called by the orchestrator
     * when one of **this module's** effects threw inside `runEffect`.
     *
     * **Why separated from [reduce]?** [reduce]'s action parameter is the
     * module-scoped [A] type (e.g. `Action.RecordingAction`);
     * [Action.EffectFailure] is a direct `Action` subtype, NOT an `A`. A
     * shared hook would be type-unsafe.
     *
     * **Default behaviour:** returns `null` →
     * `DispatchOutcome.Rejected("reducer-null")`, which is semantically
     * correct ("no failure path defined"). Modules with recovery needs
     * override this hook and roll their sub-state back (e.g.
     * `Preparing → Idle` plus a cleanup-effect).
     *
     * **Matching the effect:** [Action.EffectFailure.effect] is a string
     * (the orchestrator captures `effect.toString()`). For
     * `object`-effects this is the simple-name; for `data class`-effects
     * it includes the args. Use exact-equality for the former,
     * `startsWith("Name(")` for the latter — see [SideEffect] KDoc.
     */
    fun reduceFailure(
        state: S,
        failure: Action.EffectFailure,
        ctx: ReducerContext,
    ): TransitionResult<S, E>? = null

    // ─── EffectHandler (Hardware / IO execution) ──────────────────────

    /**
     * Execute a side-effect. Called by the orchestrator AFTER the state
     * has been written for the transition that emitted [effect].
     *
     * **Contract:**
     * - Hardware and IO operations live here, never in [reduce].
     * - Synchronous body — long-running work launches into
     *   [ModuleServices.scope] (Phase 2; see [ModuleServices] KDoc).
     * - May NOT call `orchestrator.dispatch(...)` synchronously
     *   (forbidden pattern (h)). To re-enter dispatch, use
     *   `services.emitAction(action)` which posts the action async via
     *   `scope.launch { dispatch(action) }`.
     * - Throws are wrapped by the orchestrator into
     *   [Action.EffectFailure] and routed back to [reduceFailure].
     *   The IME never crashes from a `runEffect` throw.
     * - The `when (effect)` block MUST be expression-form over the sealed
     *   [E].
     */
    fun runEffect(effect: E, services: ModuleServices)

    // ─── Cross-Module Observer (optional) ─────────────────────────────

    /**
     * Cross-module observer hook. The orchestrator calls this on every
     * registered module after each successful state mutation, passing the
     * `(prev, next)` snapshots. The module returns the actions it wants
     * to cascade; the orchestrator dispatches them recursively at
     * `depth + 1`.
     *
     * **Snapshot semantics:** `(prev, next)` are frozen for the whole
     * cascade pass — every observer sees the same `(prev, next)` tuple,
     * regardless of cascade order. The cascade actions are dispatched
     * **after** all observers have returned.
     *
     * **Self-cascade is allowed** (KG-RSB-2 fix, 2026-05-11). The previous
     * self-filter has been removed; the only cascade safeguard is
     * `MAX_CASCADE_DEPTH = 8`. See
     * `docs/decisions/0002-state-cross-module-cascade.md` §"Self-cascade".
     *
     * **Default:** empty list (no reaction).
     */
    fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> = emptyList()

    // ─── Pref-Bindings (Phase 2 hook, default empty) ──────────────────

    /**
     * Declarative SharedPreferences ↔ sub-state mirror entries.
     *
     * **Phase 1 (today):** keep the default empty list. The hardcoded
     * mirror in `PipelinePrefMirror` (Spec 1 §4.5) is the single source
     * for Pref ↔ state mapping in Phase 1.
     *
     * **Phase 2 (backlog):** replace the hardcoded mirror with iteration
     * over `modules.flatMap { it.prefBindings() }`.
     *
     * @see net.devemperor.dictate.state.PrefBinding
     */
    fun prefBindings(): List<PrefBinding<S, *>> = emptyList()

    // ─── Shutdown hook ────────────────────────────────────────────────

    /**
     * Terminal cleanup. Called from `DictateOrchestrator.shutdown()` (which
     * the Service.onDestroy calls **before** cancelling the service scope,
     * Phase-B S-4).
     *
     * Modules emit their final synchronous hardware releases here:
     * `RecordingManager.release()`, `BluetoothSco.stop()`, etc. May
     * block up to ~1–2 s under the `runBlocking`-timeout that
     * `Service.onDestroy` provides (~5 s FGS budget).
     *
     * **Default:** no-op. Modules with no hardware/resource ownership
     * leave the default.
     */
    fun terminate(services: ModuleServices) = Unit
}
