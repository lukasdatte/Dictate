package net.devemperor.dictate.state

import kotlin.reflect.KClass

/**
 * Single registry of all [DictateModule]s active in the running IME.
 *
 * **Why a registry abstraction (not a hand-coded `listOf` in the
 * orchestrator)?** Three reasons:
 *
 *  1. **Ordered iteration is a contract** (ADR-0002 §"Cascade-Order").
 *     Cross-module cascade order is the order of [all]. Putting the
 *     order behind a named `object` makes "I reordered the registry" a
 *     reviewable code change rather than a casual cleanup.
 *  2. **Init-time invariants** are enforced once, in one place — see
 *     [validate] below. Catching duplicate ids or duplicate action-class
 *     ownership at construction time means production code can never see
 *     an ambiguous routing map.
 *  3. **Tests can inject a custom subset** via the secondary constructor
 *     on [DictateOrchestrator], building registries with fake modules to
 *     exercise specific dispatch paths.
 *
 * **Why a class (not just an `object`)?** Subclassing + secondary
 * constructor lets tests build registries with arbitrary module subsets
 * without monkey-patching the singleton. Production code uses the
 * singleton via [companion object][Companion]; tests construct
 * `DictateModuleRegistry(listOf(FakeRecordingModule))`.
 *
 * **Cascade order contract (ADR-0002):** The order of [all] is the order
 * of cascade-action emission in
 * [DictateOrchestrator.dispatchInternal] step 5. Reordering [all] is a
 * plan-relevant refactor — it changes observable cascade semantics. The
 * order is verified by `DictateOrchestratorCascadeOrderTest`.
 *
 * **Phase-1 vs. Phase-2 note:** Chunks C5 + C6 populate the singleton's
 * `all` list with the 13 production modules; Phase 2's `InterruptionModule`
 * is added at that time (activated with real producers 2026-07-02, F-036). Until C5 runs, the singleton's `all` is empty
 * and the orchestrator does no routing — Block 2 dispatches go through
 * the no-op stub in `DictatePipelineService.LocalBinder.dispatch`
 * (C7 wires the orchestrator into the binder).
 *
 * @property all ordered list of modules. The order is part of the
 *   binding contract — see "Cascade order contract" above.
 *
 * @see net.devemperor.dictate.state.DictateModule
 * @see net.devemperor.dictate.state.DictateOrchestrator
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"DictateModuleRegistry.all"
 * @see docs/decisions/0002-state-cross-module-cascade.md §"Cascade order"
 * @see docs/architecture/state-architecture/modules.md §7.1
 */
open class DictateModuleRegistry(
    val all: List<DictateModule<*, *, *>>,
) {

    init {
        validate(all)
    }

    /**
     * Strict invariant check: **every direct sealed subclass of [Action]
     * (except [Action.EffectFailure] — routed via `originModuleId`, not
     * KClass) is claimed by exactly one module in [all]**.
     *
     * **Why not in [validate]?** Test registries deliberately ship with
     * a subset of modules (e.g. just `RecordingModule`) to exercise
     * specific dispatch paths. Running the coverage check on those
     * registries would crash test construction. This stricter check is
     * called separately from production wiring at service-bind time
     * (Spec 1 §4.8 invariant 3, deferred to chunk C7 per the registry's
     * `validate` KDoc).
     *
     * **What it catches:** a new Action sealed-subclass added in code
     * without a corresponding module registration — the orchestrator
     * would silently route to `DispatchOutcome.Unrouted` at runtime
     * (drop) instead of failing fast at startup. This guard turns the
     * runtime silent-drop into an init-time `IllegalStateException`
     * that the host service can surface in logcat before
     * `startForeground` ever runs.
     *
     * **ProGuard dependency:** uses `Action::class.sealedSubclasses`.
     * The keep rule in `app/proguard-rules.pro` (added in C4) is
     * non-negotiable. Without it, `sealedSubclasses` returns an empty
     * list in release builds and **the check passes vacuously** —
     * masking the bug-class it is supposed to catch.
     *
     * @throws IllegalStateException if any direct Action sealed
     *   subclass is unclaimed.
     *
     * @see net.devemperor.dictate.state.Action.EffectFailure
     * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §4.8
     */
    fun assertCompleteCoverage() {
        // Test-side modules (TestOnlyModules.kt) re-use the production
        // Action sealed-subtypes — they don't introduce new top-level
        // sealed children. So the check below is stable across test
        // registries that include them.
        val specialCaseSubtypes: Set<kotlin.reflect.KClass<out Action>> =
            setOf(Action.EffectFailure::class)
        val claimed: Set<kotlin.reflect.KClass<out Action>> =
            all.map { it.actionClass }.toSet()
        @Suppress("UNCHECKED_CAST")
        val allDirectSubtypes: Set<kotlin.reflect.KClass<out Action>> =
            (Action::class.sealedSubclasses as List<kotlin.reflect.KClass<out Action>>).toSet()
        val missing = allDirectSubtypes - claimed - specialCaseSubtypes
        check(missing.isEmpty()) {
            "Missing module-routing for Action sealed subtypes: " +
                missing.joinToString { it.simpleName ?: "<anon>" } +
                ". Every direct sealed subclass of Action::class must be claimed by " +
                "exactly one module (except special-cases like EffectFailure)."
        }
    }

    /**
     * Production singleton. Populated incrementally as C5 + C6 land:
     *
     * - **C5** adds the 5 core modules: `RecordingModule`, `PipelineModule`,
     *   `AudioModule`, `ViewModeModule`, `OverlayModule`.
     * - **C6** adds the 8 auxiliary modules: `ResendModule`, `LivePromptModule`,
     *   `LanguageModule`, `LayoutModule`, `FeatureToggleModule`, `ThemingModule`,
     *   `PendingSessionsModule`, `KeyboardInputModule` (+ `InterruptionModule`,
     *   activated 2026-07-02, F-036).
     *
     * Order in this list is the **cascade-emission order** (ADR-0002 §"Cascade
     * order"). The core modules go first because they sit on the hot path
     * (recording start → pipeline-trigger → view-mode switch) and the
     * cascade-flow shape is easier to reason about when the "owner" of a
     * given transition cascades before observers.
     *
     * Tests do **not** use this companion — they construct an ad-hoc
     * registry via `DictateModuleRegistry(listOf(FakeXxxModule))`.
     */
    companion object Default : DictateModuleRegistry(
        listOf(
            // ─── Core (C5) — hot-path FSMs and observer-rich modules ───
            RecordingModule,
            PipelineModule,
            AudioModule,
            ViewModeModule,
            // B3.1 / ADR-0008 — Widget+ImeView axes. No-op until B3.2
            // fills W1-W8 transitions; co-exists with ViewModeModule.
            WidgetModule,
            OverlayModule,
            // ─── Aux (C6) — simpler axes ───
            ResendModule,
            LivePromptModule,
            LanguageModule,
            LayoutModule,
            FeatureToggleModule,
            ThemingModule,
            PendingSessionsModule,
            KeyboardInputModule,
            // ADR-0006 completion (2026-07-02) — transient pipeline-error
            // + engagement hints feeding the state-derived info bar.
            InfoHintModule,
            // F-036 (2026-07-02) — audio-focus / headset interruption
            // axis (registered as a stub since C6, active now).
            InterruptionModule,
        ),
    )
}

/**
 * Init-time structural validation of a registry's module list.
 *
 * Three invariants:
 *
 *  1. **Unique [DictateModule.id]** — duplicate ids would make
 *     EffectFailure-routing ambiguous (a failure originating from
 *     `Recording` cannot reach two different modules).
 *  2. **Unique [DictateModule.actionClass]** — duplicate
 *     action-classes would route the same action to two modules
 *     (multi-owner-per-axis anti-pattern, Spec 1 §1.1 SRP violation).
 *     This is the DI-container pattern from Hilt/Dagger.
 *  3. **No leaf-class overlap across modules' sealed hierarchies** —
 *     even when `actionClass` differs, two modules could each claim a
 *     subtree that shares a leaf. The walk inside
 *     [DictateOrchestrator.moduleByLeafClass] re-checks this for the
 *     production-routing map; doing the structural check here makes
 *     the failure message more locatable (registry-level, not
 *     dispatcher-level).
 *
 * **Note on the "complete coverage" check (Spec 1 §4.8 invariant 3):**
 * The check that "every direct sealed subclass of `Action` is owned by
 * exactly one module" is **not** enforced here — it would require the
 * full production registry, which is built incrementally in C5/C6.
 * It is exposed as a separate method
 * [DictateModuleRegistry.assertCompleteCoverage] that the host service
 * calls at service-bind time (after all modules are registered);
 * test registries with a subset of modules skip the check.
 *
 * @param modules the candidate registry's module list.
 * @throws IllegalArgumentException with a precise message if any
 *   invariant is violated.
 */
private fun validate(modules: List<DictateModule<*, *, *>>) {
    // Invariant 1: unique ids
    val ids = modules.map { it.id }
    require(ids.toSet().size == ids.size) {
        "Duplicate ModuleId in registry: ${ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}"
    }

    // Invariant 2: unique actionClass tokens (the *root* actionClass per module)
    val actionClasses = modules.map { it.actionClass }
    require(actionClasses.toSet().size == actionClasses.size) {
        "Duplicate actionClass in registry: " +
                actionClasses.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
    }

    // Invariant 3: no leaf-class overlap across distinct modules.
    // Two modules with disjoint root `actionClass`-references could
    // still collide if one root is a supertype of a leaf the other
    // root claims. The orchestrator's `moduleByLeafClass` would catch
    // it at init too, but registering it here gives a registry-level
    // diagnostic ("which two modules collided") instead of a
    // dispatcher-level one.
    val ownership: MutableMap<KClass<out Action>, ModuleId> = mutableMapOf()
    modules.forEach { module ->
        collectActionLeaves(module.actionClass).forEach { leaf ->
            val prior = ownership.put(leaf, module.id)
            require(prior == null) {
                "Action leaf class $leaf is claimed by both $prior and ${module.id}"
            }
        }
    }
}

/**
 * Walk the sealed hierarchy rooted at [root] and collect every concrete
 * leaf class. A leaf is a `data class`/`data object` (or any
 * non-sealed) descendant of the sealed [Action] hierarchy.
 *
 * Shared between [DictateOrchestrator]'s routing-map construction and
 * [DictateModuleRegistry]'s validation. Internal — the public surface
 * never exposes the walker.
 *
 * **Recursive walk:** Spec 1 §4.3 mandates a recursive collection
 * because Action sealed sub-classes may themselves contain sealed
 * sub-hierarchies (a Phase-2 feature could nest a sealed
 * `Action.PipelineAction.Reprocess`). Walking one level only would
 * silently miss those leaves.
 *
 * **Reflection cost:** `KClass.sealedSubclasses` is the cost driver
 * (init-time only — once per service-bind). ProGuard must keep the
 * sealed hierarchy intact in release builds; see Spec 1 §4.3 ProGuard
 * block + the rule in `app/proguard-rules.pro`.
 */
internal fun collectActionLeaves(root: KClass<out Action>): List<KClass<out Action>> =
    if (root.sealedSubclasses.isEmpty()) {
        listOf(root)
    } else {
        root.sealedSubclasses.flatMap { child ->
            @Suppress("UNCHECKED_CAST")
            collectActionLeaves(child as KClass<out Action>)
        }
    }
