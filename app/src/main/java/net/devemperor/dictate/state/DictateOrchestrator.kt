package net.devemperor.dictate.state

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.devemperor.dictate.BuildConfig
import kotlin.reflect.KClass

/**
 * Typed outcome of a single [DictateOrchestrator.dispatch] call.
 *
 * Replaces the earlier three indistinguishable cases (Spec 1 Logic L-7:
 * Applied vs NoOp vs InvalidState).
 *
 * - [Applied] — reducer accepted the action; state was updated and any
 *   side-effects + cross-module cascade ran.
 * - [Rejected] — the targeted module returned `null` from its
 *   `reduce`/`reduceFailure` hook. Semantically: "action irrelevant in
 *   current state" or "no failure path defined" — **not** a bug.
 * - [Unrouted] — no module owns the action's KClass (or, for
 *   [Action.EffectFailure], no module matches the [Action.EffectFailure.originModuleId]).
 *   In release builds this is logged + returned; in debug builds it
 *   crashes via `error()` (see [DictateOrchestrator.MAX_CASCADE_DEPTH]
 *   guard for the equivalent treatment of cascade loops).
 *
 * @see net.devemperor.dictate.state.DictateOrchestrator.dispatch
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"Module contract"
 */
sealed interface DispatchOutcome {
    /** Reducer accepted the action; state changed (or stayed equal via copy()). */
    data object Applied : DispatchOutcome

    /**
     * Reducer returned `null` — "action not relevant in current state".
     * Carries the action + a short reason for log/debug analysis.
     */
    data class Rejected(val action: Action, val reason: String) : DispatchOutcome

    /**
     * No module owns the action's KClass (or the EffectFailure's originModuleId).
     * Indicates either a missing registry entry or a ProGuard misconfiguration
     * (see Spec 1 §4.3 ProGuard-Keep block; ADR-0001 §"Failure modes").
     */
    data class Unrouted(val action: Action) : DispatchOutcome
}

/**
 * Composition root of the state-mutation pipeline.
 *
 * **Note on naming (F-8 — disambiguation).** `DictateOrchestrator` is
 * the **state-action-router** introduced by ADR-0001 — it owns the
 * registry-driven `Action → reducer → state-write → effects → cascade`
 * dispatch loop. The legacy
 * [net.devemperor.dictate.core.PipelineOrchestrator] is the
 * **audio-pipeline runner** (transcription/completion + DAO writes);
 * the two classes are unrelated and **co-exist during the Block 2 →
 * Block 3 migration window**. B3 absorbs the legacy
 * `PipelineOrchestrator` into the new architecture as a
 * `PipelineRunnerSubsystem` adapter behind the modular orchestrator.
 * Tests + reviewers should keep the distinction in mind when reading
 * stack traces and KDoc references.
 *
 * The orchestrator is the **only** mutator of [DictateUiStateStore]. It
 * routes every dispatched [Action] to exactly one [DictateModule], runs
 * that module's pure reducer, writes the new sub-state back into the
 * global state via the module's lens, executes the emitted side-effects,
 * and propagates cross-module cascades — capped by [MAX_CASCADE_DEPTH].
 *
 * **Why a class (not a top-level function)?** The orchestrator owns four
 * pieces of cross-cutting state: the registry, two derived lookup maps
 * (KClass → module, ModuleId → module), and the cascade-depth counter
 * threading through recursive `dispatchInternal` calls. A class scopes
 * them naturally.
 *
 * **Construction-time work:**
 *
 *  1. Build [moduleByLeafClass] from `module.actionClass.sealedSubclasses`
 *     (recursive walk via [collectActionLeaves]). Duplicate routing throws.
 *  2. Build [moduleById] from `module.id`. Duplicate id was already
 *     rejected by `DictateModuleRegistry.init`.
 *
 * **Dispatch loop (Spec 1 §4.3, six steps):**
 *
 *  1. **Cascade-limit check** — depth ≥ [MAX_CASCADE_DEPTH] short-circuits.
 *  1a. **EffectFailure special-case** — route via [Action.EffectFailure.originModuleId]
 *     (not KClass), because all modules emit the same Action subtype.
 *  1b. **Regular routing** — `moduleByLeafClass[action::class]`.
 *  2. **Reducer call** — `module.reduce(subState, action, ctx)` or
 *     `module.reduceFailure(...)` for EffectFailure. `null` → Rejected.
 *  3. **State write** — `store.update { module.write(it, result.nextState) }`.
 *  4. **Side-effects** — `module.runEffect(effect, services)`. Throws are
 *     wrapped into `Action.EffectFailure(originModuleId = module.id, ...)`
 *     and re-dispatched at `depth + 1`.
 *  5. **Cross-module cascade** — every registered module observes
 *     `(prevGlobal, nextGlobal)` and returns a list of cascade actions.
 *     Order follows `DictateModuleRegistry.all`. Each cascade action is
 *     recursively dispatched at `depth + 1` with a **fresh** snapshot —
 *     later cascades see earlier mutations.
 *
 * **Cross-cutting contracts:**
 *
 * - **Main-thread confined** (ADR-0001 §"Main-Thread Confined Dispatch") —
 *   re-entrant `dispatch()` from inside `runEffect` is forbidden pattern (h).
 *   Effect handlers re-enter via [emitAction] which posts to [scope].
 * - **Frozen cascade snapshot** (ADR-0002 §"Frozen snapshot") — every
 *   observer in one pass sees the same `(prev, next)` tuple.
 * - **Self-cascade is allowed** (ADR-0002 §"Self-cascade", KG-RSB-2-Fix) —
 *   the self-filter has been **deliberately removed**. The only loop
 *   guard is [MAX_CASCADE_DEPTH].
 * - **EffectFailure routes by `originModuleId`** (ADR-0002 §"EffectFailure
 *   routing"), not KClass.
 *
 * @property scope coroutine scope owned by the host service. Side-effects
 *   that need async work launch into it via `services.scope.launch`. The
 *   orchestrator itself uses [scope] only inside [emitAction] (async
 *   re-entry).
 * @property store the single source of truth — mutated via [store.update]
 *   exactly once per accepted action.
 * @property services the DI container handed to every `runEffect` call.
 *   Injected as a flat instance rather than a factory closure because
 *   the FGS construction order (B-3 wiring) builds services first, then
 *   the orchestrator — there is no re-construction during the service's
 *   lifetime.
 * @property registry source of [DictateModuleRegistry.all]; defaulted to
 *   the production registry. Tests inject a custom registry via the
 *   secondary constructor.
 *
 * @see net.devemperor.dictate.state.DictateModule
 * @see net.devemperor.dictate.state.DictateUiStateStore
 * @see net.devemperor.dictate.state.DictateModuleRegistry
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"Required mechanics"
 * @see docs/decisions/0002-state-cross-module-cascade.md §"Dispatch loop"
 * @see docs/architecture/state-architecture/modules.md §3
 * @see docs/architecture/state-architecture/cross-module-cascade.md §1
 * @see docs/architecture/state-architecture/effects-and-failures.md §3
 */
class DictateOrchestrator(
    private val scope: CoroutineScope,
    private val store: DictateUiStateStore,
    private val services: ModuleServices,
    private val registry: DictateModuleRegistry = DictateModuleRegistry,
    private val prefMirror: PipelinePrefMirror? = null,
    private val recovery: PipelineRecovery? = null,
) {

    /** Read-only view of the state for subscribers (IME / UI / notification coord.). */
    val state: StateFlow<DictateUiState> = store.state

    /**
     * Construction-time wiring of the Pref-mirror and DB-recovery side
     * inputs to the store.
     *
     * **Order matters (Spec 1 §4.3 + §11.2.2 Block-1b step 7-8):**
     *
     *  1. `prefMirror.attach(store)` runs **synchronously** — when the
     *     IME-side `bindService` returns the LocalBinder and the IME
     *     first reads `state.value`, the 19 mirrored prefs are already
     *     applied. Without this, the IME would briefly see
     *     `DictateUiState.initial()` defaults (Phase-B S-1 boot-race).
     *  2. `recovery.recover(store)` is launched **asynchronously** into
     *     [scope] — it does DB IO and must not block the 5-second FGS
     *     startForeground budget (Spec 1 §11.6.1).
     *
     * Both [prefMirror] and [recovery] are nullable so unit tests that
     * exercise the dispatch loop in isolation can construct an
     * orchestrator without Android-backed plumbing. Production wiring
     * always supplies both.
     */
    init {
        // Review-fix G3 (2026-05-21) — wire the mirror to a sink that
        // runs cross-module observers on every external SP-driven state
        // mutation. Method-reference avoids a fresh lambda allocation
        // per dispatch and keeps the cascade engine encapsulated within
        // the orchestrator. See [runMirrorSync] for the dispatch shape.
        prefMirror?.attach(store, ::runMirrorSync)
        if (recovery != null) {
            scope.launch { recovery.recover(store) }
        }
    }

    /**
     * `KClass → module` routing map for **non-failure** actions. Built at
     * init from each module's `actionClass.sealedSubclasses` (recursive
     * via [collectActionLeaves]). Duplicate leaf-class is an init-time error.
     *
     * **Reflection dependency:** uses `KClass.sealedSubclasses`. ProGuard
     * must keep the [Action] hierarchy in release builds — the rule in
     * `app/proguard-rules.pro` (added in Chunk C4) is **non-negotiable**.
     * Without it, `sealedSubclasses` returns an empty list and **every**
     * non-failure dispatch becomes [DispatchOutcome.Unrouted].
     *
     * @see DictateModule.actionClass
     * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"Required mechanics" item 5
     */
    private val moduleByLeafClass: Map<KClass<out Action>, DictateModule<*, *, *>> = run {
        val map = mutableMapOf<KClass<out Action>, DictateModule<*, *, *>>()
        registry.all.forEach { module ->
            collectActionLeaves(module.actionClass).forEach { leaf ->
                require(map.put(leaf, module) == null) {
                    "Action $leaf is routed to multiple modules — ambiguity detected at init"
                }
            }
        }
        map.toMap()
    }

    /**
     * `ModuleId → module` lookup for [Action.EffectFailure]-routing.
     *
     * Why a second lookup: all modules emit the same Kotlin class for
     * EffectFailure (`Action.EffectFailure`), so KClass-routing would
     * resolve every failure to one arbitrary module. The
     * [Action.EffectFailure.originModuleId] field carries the id of the
     * module whose `runEffect` threw, and this map sends the failure
     * back to that module's [DictateModule.reduceFailure] hook.
     *
     * @see Action.EffectFailure
     * @see docs/decisions/0002-state-cross-module-cascade.md §"EffectFailure routing"
     */
    private val moduleById: Map<ModuleId, DictateModule<*, *, *>> =
        registry.all.associateBy { it.id }

    // ─── Public dispatch surface ─────────────────────────────────────

    /**
     * Single-dispatch entry. **Main-thread confined.**
     *
     * Reducers, side-effects, and cascade observers all run synchronously
     * during this call. Async re-entry (from a `runEffect`) goes through
     * [emitAction], which posts a `scope.launch { dispatch(action) }`.
     *
     * @return the [DispatchOutcome] for this action. Caller may ignore
     *   the return value — most call-sites do, since the outcome is
     *   already reflected in `state.collect { … }`.
     */
    fun dispatch(action: Action): DispatchOutcome = dispatchInternal(action, depth = 0)

    /**
     * Async re-entry — posts the action to [scope] for later main-thread
     * dispatch. Re-entrant calls from inside `runEffect` MUST use this
     * (not [dispatch]) to avoid mid-cascade reentrancy.
     *
     * Each [emitAction]-driven dispatch starts a **fresh** cascade-depth
     * counter (depth = 0). It is a separate "cascade pass" — see
     * ADR-0002 §"Frozen snapshot".
     *
     * **Dispatcher contract:** [scope] is constructed by the host service
     * with `Dispatchers.Main.immediate` (Spec 1 §4.3 + ADR-0003) — the
     * launched coroutine inherits that dispatcher. Unit tests inject an
     * `Unconfined` (or test-) dispatcher so re-entry executes
     * synchronously without a real main looper.
     */
    fun emitAction(action: Action) {
        scope.launch { dispatch(action) }
    }

    /**
     * Terminal cleanup. Detaches the [PipelinePrefMirror] first (so a
     * late SP-listener-fire cannot write into the dying store), then
     * iterates [DictateModuleRegistry.all] and calls each module's
     * [DictateModule.terminate]. Per Spec 1 §4.3 the host service MUST
     * call this **before** cancelling [scope] — synchronous hardware-
     * releases inside `terminate` need a live scope (some
     * implementations may launch a final `serviceScope.launch` to
     * commit DB writes).
     *
     * A module that throws does not block other modules; the throw is
     * logged at WARN and the loop continues.
     */
    fun shutdown() {
        prefMirror?.detach()
        registry.all.forEach { module ->
            try {
                @Suppress("UNCHECKED_CAST")
                (module as DictateModule<Any, Action, SideEffect>).terminate(services)
            } catch (t: Throwable) {
                Log.w(TAG, "Module ${module.id} terminate failed", t)
            }
        }
    }

    // ─── Internal dispatch loop ──────────────────────────────────────

    private fun dispatchInternal(action: Action, depth: Int): DispatchOutcome {
        // Step 1: Cascade-depth guard. Only loop safeguard (self-cascade
        // is intentionally allowed — see KG-RSB-2-Fix comment block in
        // Step 5 below). `MAX_CASCADE_DEPTH = 8` is conservative; real
        // cascades observed in Spec 1 §15 are 1–3 deep.
        if (depth >= MAX_CASCADE_DEPTH) {
            val msg = "Cascade loop detected at depth=$depth, action=$action"
            if (BuildConfig.DEBUG) {
                error(msg)
            }
            Log.e(TAG, msg)
            return DispatchOutcome.Rejected(action, "cascade-loop")
        }

        // Step 1a: EffectFailure special-case — route to the ORIGIN
        // module (not by KClass). All modules emit the same Action
        // subtype, so KClass-routing would resolve to an arbitrary
        // module. See ADR-0002 §"EffectFailure routing".
        val module: DictateModule<*, *, *> = if (action is Action.EffectFailure) {
            moduleById[action.originModuleId]
                ?: run {
                    Log.w(TAG, "EffectFailure for unknown originModuleId=${action.originModuleId}: $action")
                    return DispatchOutcome.Unrouted(action)
                }
        } else {
            // Step 1b: regular KClass-Lookup for all other actions.
            moduleByLeafClass[action::class]
                ?: run {
                    Log.w(TAG, "No module routes action ${action::class.simpleName} — dropping: $action")
                    return DispatchOutcome.Unrouted(action)
                }
        }

        @Suppress("UNCHECKED_CAST")
        val typedModule = module as DictateModule<Any, Action, SideEffect>

        val prevGlobal = store.snapshot
        val subState = typedModule.read(prevGlobal)
        val ctx = ReducerContext(global = prevGlobal)

        // Step 2: reducer (pure, F1+F2). Two paths: regular reduce(...)
        // for module-scoped actions, reduceFailure(...) for EffectFailure
        // (its action type doesn't match the module's `A` type, so a
        // shared hook would be type-unsafe — see DictateModule §4.2).
        val result = if (action is Action.EffectFailure) {
            typedModule.reduceFailure(subState, action, ctx)
        } else {
            typedModule.reduce(subState, action, ctx)
        } ?: return DispatchOutcome.Rejected(action, "reducer-null")

        // Step 3: state write (atomic).
        store.update { typedModule.write(it, result.nextState) }

        // Step 4: side-effects. Throws are wrapped into EffectFailure
        // and re-dispatched at depth+1 with originModuleId = this module.
        // The IME never crashes from a runEffect throw (Issue 2.1.3
        // Option D).
        result.sideEffects.forEach { effect ->
            try {
                typedModule.runEffect(effect, services)
            } catch (t: Throwable) {
                Log.e(TAG, "Effect failure in ${typedModule.id}: $effect", t)
                dispatchInternal(
                    Action.EffectFailure(
                        originModuleId = typedModule.id,
                        effect = effect.toString(),
                        reason = t.message ?: t.javaClass.simpleName,
                    ),
                    depth + 1,
                )
            }
        }

        // Step 5: cross-module observation.
        //
        // ╔═══════════════════════════════════════════════════════════════════════════╗
        // ║ ⚠ DO NOT RE-ADD SELF-FILTER (KG-RSB-2-Fix, 2026-05-11)                   ║
        // ║                                                                           ║
        // ║ A prior implementation filtered `modules.filter { it.id != module.id }`  ║
        // ║ before this loop. That filter is DELIBERATELY removed. Self-cascade is   ║
        // ║ required (Spec 1 §15.2 RecordingModule: Idle→Preparing self-emits        ║
        // ║ Action.OverlayAction.ResetSuppressBit via its own observer).             ║
        // ║                                                                           ║
        // ║ Re-introducing the self-filter:                                          ║
        // ║   - Breaks regression test                                               ║
        // ║     `DictateOrchestratorTest.selfCascade_observerCanEmitOwnAction`.      ║
        // ║   - Re-introduces the HOVER-Overlay reopen bug (KG-RSB-2 production).   ║
        // ║                                                                           ║
        // ║ The only loop guard is MAX_CASCADE_DEPTH (Step 1 above).                 ║
        // ╚═══════════════════════════════════════════════════════════════════════════╝
        //
        // Frozen-snapshot semantics: prev/next are captured ONCE here and
        // every observer sees the same tuple. Cascade actions dispatched
        // recursively at depth+1 will re-snapshot for their own pass.
        val nextGlobal = store.snapshot
        val cascadeActions = registry.all.flatMap { observer ->
            observer.onCrossModuleStateChange(prevGlobal, nextGlobal)
        }

        // Step 6: recursive cascade dispatch (deterministic order = registry order).
        // Each iteration re-snapshots, so later cascades see the effect
        // of earlier ones. Reordering DictateModuleRegistry.all is a
        // plan-relevant refactor (verified by
        // `DictateOrchestratorCascadeOrderTest`).
        cascadeActions.forEach { cascadeAction ->
            dispatchInternal(cascadeAction, depth + 1)
        }

        return DispatchOutcome.Applied
    }

    /**
     * Mirror-sync entry — apply [reducer] to the store and run
     * cross-module observers against the resulting `(prev, next)` tuple,
     * recursively dispatching any cascade actions.
     *
     * **Review-fix G3 (2026-05-21) — closes the SP→State→Cascade gap.**
     * Before this method, [PipelinePrefMirror] called `store.update`
     * directly. That bypassed [dispatchInternal] Step 5 (cross-module
     * observation), so an external Settings-Activity SP write could
     * change `audio.audioFocusEnabledPref` mid-recording without ever
     * reaching [AudioModule.onCrossModuleStateChange]'s
     * `ApplyAudioFocusRuntimeFromPref` cascade arm — the live
     * `AudioManager` would stay stale. This was the exact R-5 latent
     * regression the indirection-cleanup plan §6.1 was supposed to
     * prevent (and that the D-1 mitigation depended on).
     *
     * **Why this shape (not a synthetic Action.MirrorSync):** the mirror
     * is **not** semantically a user action — there is no reducer to call,
     * no module that owns "the mirror" as an action axis, no
     * `actionClass` to register. Creating a pseudo-action with a
     * pseudo-module would be a placeholder-with-no-domain-meaning
     * (anti-SOLID). The mirror IS state authority for a slice of state
     * (the 19 SP-mirrored axes); it produces a (prev, next) directly,
     * and the cascade-engine is the right tool to react to that diff.
     *
     * **Dispatch shape — mirrors [dispatchInternal] Steps 3/5/6:**
     * 1. Snapshot `prev` (`store.snapshot`).
     * 2. Apply [reducer] via `store.update`.
     * 3. Snapshot `next` (`store.snapshot`).
     * 4. Compute cascade via `registry.all.flatMap { it.onCrossModuleStateChange(prev, next) }`
     *    — same loop, same frozen-snapshot semantics as Step 5.
     * 5. Recursively dispatch each cascade action through
     *    [dispatchInternal] at `depth = 0`. An external SP change is a
     *    **fresh dispatch pass**, not a continuation of an in-flight
     *    action's cascade, so the depth counter resets. The
     *    `MAX_CASCADE_DEPTH` guard still protects the inner cascade
     *    chain.
     *
     * **Thread safety:** the SP listener fires on the thread that called
     * `apply()` (typically a background disk thread). `store.update` is
     * CAS-safe; `dispatchInternal` calls are serialised through the same
     * store-mutation channel.
     */
    internal fun runMirrorSync(reducer: (DictateUiState) -> DictateUiState) {
        val prev = store.snapshot
        store.update(reducer)
        val next = store.snapshot

        // Same frozen-snapshot semantics as dispatchInternal Step 5:
        // every observer in this pass sees the same (prev, next) tuple.
        val cascadeActions = registry.all.flatMap { observer ->
            observer.onCrossModuleStateChange(prev, next)
        }

        // Each cascade action starts its own dispatch pass at depth = 0
        // (fresh external trigger, not a continuation). The cascade
        // depth guard still applies inside each pass.
        cascadeActions.forEach { cascadeAction ->
            dispatchInternal(cascadeAction, depth = 0)
        }
    }

    companion object {
        private const val TAG = "DictateOrchestrator"

        /**
         * Cap on cross-module cascade recursion depth. Above this, the
         * orchestrator short-circuits with
         * [DispatchOutcome.Rejected] (release) or `error()` (debug).
         *
         * Set per ADR-0002. Real cascade depths observed in Spec 1 §15
         * are 1–3; the cap is conservative.
         */
        const val MAX_CASCADE_DEPTH = 8
    }
}
