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
 * is added at that time. Until C5 runs, the singleton's `all` is empty
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
     * Production singleton. Populated incrementally as C5 + C6 land:
     *
     * - **C5** adds the 5 core modules: `RecordingModule`, `PipelineModule`,
     *   `AudioModule`, `ViewModeModule`, `OverlayModule`.
     * - **C6** adds the 8 auxiliary modules: `ResendModule`, `LivePromptModule`,
     *   `LanguageModule`, `LayoutModule`, `FeatureToggleModule`, `ThemingModule`,
     *   `PendingSessionsModule`, `KeyboardInputModule` (+ Phase-2 `InterruptionModule`).
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
            RecordingModule,
            PipelineModule,
            AudioModule,
            ViewModeModule,
            OverlayModule,
            // C6 will append: ResendModule, LivePromptModule, LanguageModule,
            // LayoutModule, FeatureToggleModule, ThemingModule,
            // PendingSessionsModule, KeyboardInputModule, InterruptionModule.
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
 * Chunk C7 adds that strict check via an `assertCompleteCoverage()`
 * call at service-bind time (after all modules are registered).
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
