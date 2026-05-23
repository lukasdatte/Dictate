package net.devemperor.dictate.state

import androidx.annotation.VisibleForTesting
import kotlin.reflect.KClass

// ════════════════════════════════════════════════════════════════════
// Test fixtures for [DictateModule] / [DictateModuleRegistry] /
// [DictateOrchestrator]. Production-side because [DictateModule] is a
// `sealed interface` (ADR-0001 §"Required mechanics" item 4); Kotlin's
// sealed-type rule restricts implementations to the **same Kotlin
// compilation unit**. Android Gradle splits `main` and `unitTest` into
// separate compilations, so the fixtures cannot live in `src/test/`.
//
// The fixtures here are marked @VisibleForTesting and are **not**
// registered in [DictateModuleRegistry.Default.all]; production wiring
// never instantiates them. Tests construct ad-hoc registries via
// `DictateModuleRegistry(listOf(testModuleA, …))`.
//
// **Action-class reuse strategy.** To avoid bloating
// `Action::class.sealedSubclasses` with a test-only Action subtype
// (which would break `ActionHierarchyTest`'s contract that the
// hierarchy has exactly N production children), the fixtures **reuse
// production Action subtypes** (e.g. `Action.LanguageAction`,
// `Action.InterruptionAction`). Tests construct **ad-hoc registries**
// via `DictateModuleRegistry(listOf(testModuleA, …))`, not the
// production `Default` singleton — so reusing the production
// `actionClass` does not collide with C6's production registry.
// Phase 1 production code does NOT route those actions from tests.
//
// **Action-class collision constraint for tests.** Two test modules
// must claim disjoint Action subtypes (the registry validator will
// reject duplicates). Choose pairings from the *unrouted-in-C4*
// production actions (e.g. one test module per test, max 2-3
// concurrent).
//
// **Type variance.** Most test-friendly: declare the per-instance
// `actionClass: KClass<A>` as `KClass<out Action>` then explicitly
// rebind to the test-action type via cast in `reduce`. The fixture
// erases the action type to `Action` so a single fixture class can be
// instantiated with any sub-sealed.
//
// @see net.devemperor.dictate.state.DictateOrchestrator
// @see net.devemperor.dictate.state.DictateModuleRegistry
// ════════════════════════════════════════════════════════════════════

/** Test-only [SideEffect] surface. Not sealed — tests can add more variants. */
@VisibleForTesting
sealed interface TestEffect : SideEffect {
    /** A no-op effect — runEffect does nothing. */
    data object NoOp : TestEffect

    /** Triggers a throw inside runEffect — used to exercise EffectFailure routing. */
    data object Throws : TestEffect

    /** Records an arbitrary tag for the test to verify side-effect ordering. */
    data class Tag(val tag: String) : TestEffect
}

/**
 * Test-only [ModuleId] sub-hierarchy. Disjoint from the 14 production
 * ids, so test fixtures never collide with the production registry.
 *
 * **Why sealed sub-interface?** Tests need to add new ids without
 * touching the production `ModuleId` body. Sub-sealing keeps the
 * compile-time exhaustiveness guarantee that
 * `when (id: ModuleId) { is TestModuleId.A -> … }` is checked.
 */
@VisibleForTesting
sealed interface TestModuleId : ModuleId {
    data object A : TestModuleId
    data object B : TestModuleId
    data object C : TestModuleId
    data object D : TestModuleId
    data object E : TestModuleId
}

/**
 * Configurable test [DictateModule].
 *
 * **State `S = Int`** — a counter the reducer increments on every
 * applied action. Tests assert against the counter (via [lens.get]) to
 * verify "reduce was called for action X" without a mocking framework.
 *
 * **Action subtype `A: Action`** — the parameterised type. Pass any
 * sealed-Action subclass; the fixture re-narrows internally. Production
 * Action subtypes can be reused (see "Action-class reuse strategy"
 * KDoc above).
 *
 * Each callback parameter (`reducer`, `crossModule`, …) defaults to a
 * conservative no-op so tests override only the surfaces they care
 * about.
 *
 * @property id one of the [TestModuleId] variants (or a production
 *   [ModuleId] for the failure-routing tests).
 * @property actionClass which Action root this module claims.
 * @property lens shared counter store across all test modules in one
 *   test (one slot per module-id).
 * @property reducer body of `reduce` over the *un-narrowed* `Action`.
 *   Default: increment counter, emit no effects.
 * @property crossModule body of `onCrossModuleStateChange`. Default:
 *   empty list (no cascade).
 * @property effectHandler body of `runEffect`. Default: throws on
 *   [TestEffect.Throws], no-ops otherwise.
 * @property failureReducer body of `reduceFailure`. Default: null
 *   (matches production default).
 * @property terminator body of `terminate`. Default: no-op.
 *
 * @see TestStateLens
 */
@VisibleForTesting
class TestDictateModule<A : Action>(
    override val id: ModuleId,
    override val actionClass: KClass<A>,
    val initial: Int = 0,
    val lens: TestStateLens,
    val reducer: (Int, A, ReducerContext) -> TransitionResult<Int, TestEffect>? = { s, _, _ ->
        TransitionResult(s + 1, emptyList())
    },
    val crossModule: (DictateUiState, DictateUiState) -> List<Action> = { _, _ -> emptyList() },
    val effectHandler: (TestEffect, ModuleServices) -> Unit = { effect, _ ->
        // Statement-form `when`: only TestEffect.Throws has a non-no-op
        // body. Other variants fall through to Unit.
        if (effect == TestEffect.Throws) {
            throw IllegalStateException("test-only effect throw")
        }
    },
    val failureReducer: (Int, Action.EffectFailure, ReducerContext) -> TransitionResult<Int, TestEffect>? = { _, _, _ -> null },
    val terminator: (ModuleServices) -> Unit = { _ -> Unit },
) : DictateModule<Int, A, TestEffect> {

    override fun read(global: DictateUiState): Int = lens.read(id, initial)
    override fun write(global: DictateUiState, sub: Int): DictateUiState {
        lens.write(id, sub)
        return global
    }

    override fun initialState(): Int = initial

    override fun reduce(state: Int, action: A, ctx: ReducerContext): TransitionResult<Int, TestEffect>? =
        reducer(state, action, ctx)

    override fun reduceFailure(
        state: Int,
        failure: Action.EffectFailure,
        ctx: ReducerContext,
    ): TransitionResult<Int, TestEffect>? = failureReducer(state, failure, ctx)

    override fun runEffect(effect: TestEffect, services: ModuleServices) = effectHandler(effect, services)

    override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
        crossModule(prev, next)

    override fun terminate(services: ModuleServices) = terminator(services)
}

/**
 * Tiny stand-in lens: stores per-module `Int` counters in a map keyed
 * by [ModuleId]. The [DictateUiState] is **not** mutated — the lens is
 * the "owner" of the test-side counter state.
 *
 * **Why not a custom [DictateUiState] field?** Adding a test-only field
 * to the production data class would pollute the single-source-of-
 * truth. The lens sits outside the data class and shares state across
 * the test's modules.
 *
 * Multiple [TestDictateModule] instances share one lens via the [id]
 * key.
 */
@VisibleForTesting
class TestStateLens {

    private val slots: MutableMap<ModuleId, Int> = mutableMapOf()

    fun read(id: ModuleId, default: Int): Int = slots[id] ?: default

    fun write(id: ModuleId, sub: Int) {
        slots[id] = sub
    }

    /** Read the current counter without going through `global`. */
    fun get(id: ModuleId): Int? = slots[id]

    /** Reset between tests. */
    fun clear() {
        slots.clear()
    }
}
