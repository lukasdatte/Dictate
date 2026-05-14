package net.devemperor.dictate.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.devemperor.dictate.testutil.fakeModuleServices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Tests for [DictateOrchestrator] — the central dispatch loop, KClass
 * routing, MAX_CASCADE_DEPTH guard, EffectFailure origin-routing,
 * cascade-order determinism, and shutdown semantics.
 *
 * **Test scaffolding.** Uses production-side `TestDictateModule`
 * fixtures (see [net.devemperor.dictate.state.TestOnlyModules]) because
 * Kotlin's sealed-interface rule prevents test-side implementations of
 * [DictateModule].
 *
 * **Pure JVM.** No Android Context, no Robolectric — every fake is
 * hand-written (K-1). Each test resets the lens + dispatches a small
 * sequence; assertions check counters + observed effects + dispatch
 * outcomes.
 *
 * Quality-gate references:
 *  - K-1: no mocking framework.
 *  - K-4: pure JVM — no Android Context.
 */
class DictateOrchestratorTest {

    private lateinit var lens: TestStateLens
    private lateinit var store: DictateUiStateStore
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        lens = TestStateLens()
        store = DictateUiStateStore()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        lens.clear()
    }

    // ════════════════════════════════════════════════════════════════
    // Dispatch loop — reducer call + state write
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `dispatch routes action to the module that owns its KClass`() {
        val moduleA = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
        )
        val moduleB = TestDictateModule(
            id = TestModuleId.B,
            actionClass = Action.LivePromptAction::class,
            lens = lens,
        )
        val orchestrator = newOrchestrator(modules = listOf(moduleA, moduleB))

        val outcome = orchestrator.dispatch(Action.LanguageAction.RefreshFromPref)

        assertSame(DispatchOutcome.Applied, outcome)
        assertEquals(1, lens.get(TestModuleId.A))
        // Module B was NOT invoked — its counter stays at the (lazy) initial.
        assertNull(lens.get(TestModuleId.B))
    }

    @Test
    fun `dispatch returns Rejected when the reducer returns null`() {
        val module = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
            reducer = { _, _, _ -> null },  // always reject
        )
        val orchestrator = newOrchestrator(modules = listOf(module))

        val outcome = orchestrator.dispatch(Action.LanguageAction.RefreshFromPref)

        assertTrue("expected Rejected, got $outcome", outcome is DispatchOutcome.Rejected)
        assertEquals("reducer-null", (outcome as DispatchOutcome.Rejected).reason)
        // Counter NOT incremented — reducer returned null before increment.
        assertNull(lens.get(TestModuleId.A))
    }

    @Test
    fun `dispatch returns Unrouted when no module claims the action class`() {
        val orchestrator = newOrchestrator(modules = emptyList())

        val outcome = orchestrator.dispatch(Action.LanguageAction.RefreshFromPref)

        assertTrue("expected Unrouted, got $outcome", outcome is DispatchOutcome.Unrouted)
    }

    @Test
    fun `dispatch routes deeply-nested sealed leaf actions to the owning module`() {
        // Action.LanguageAction is a sealed class with a `SetOverride` data class
        // and a `RefreshFromPref` data object leaf. Claiming the root must route
        // every leaf — exercising the collectLeaves recursion.
        val module = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
        )
        val orchestrator = newOrchestrator(modules = listOf(module))

        orchestrator.dispatch(Action.LanguageAction.SetOverride("de"))
        orchestrator.dispatch(Action.LanguageAction.RefreshFromPref)

        assertEquals(2, lens.get(TestModuleId.A))
    }

    // ════════════════════════════════════════════════════════════════
    // Side effects
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `dispatch invokes runEffect for each emitted side-effect`() {
        val observed = mutableListOf<TestEffect>()
        val module = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
            reducer = { state, _, _ ->
                TransitionResult(state + 1, listOf(TestEffect.Tag("first"), TestEffect.Tag("second")))
            },
            effectHandler = { effect, _ -> observed += effect },
        )
        val orchestrator = newOrchestrator(modules = listOf(module))

        orchestrator.dispatch(Action.LanguageAction.RefreshFromPref)

        assertEquals(
            listOf(TestEffect.Tag("first"), TestEffect.Tag("second")),
            observed.toList(),
        )
    }

    @Test
    fun `runEffect throwables are wrapped as EffectFailure and routed to the origin module`() {
        // Module A emits TestEffect.Throws; runEffect throws.
        // The orchestrator wraps in Action.EffectFailure(originModuleId = TestA, …)
        // and re-dispatches; the same module's reduceFailure handles it.
        val failures = mutableListOf<Action.EffectFailure>()
        val module = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
            reducer = { state, _, _ ->
                TransitionResult(state + 1, listOf(TestEffect.Throws))
            },
            failureReducer = { state, failure, _ ->
                failures += failure
                TransitionResult(state, emptyList())  // applied — failure consumed
            },
        )
        val orchestrator = newOrchestrator(modules = listOf(module))

        val outcome = orchestrator.dispatch(Action.LanguageAction.RefreshFromPref)

        assertSame(DispatchOutcome.Applied, outcome)
        assertEquals("EffectFailure was not routed back to origin module", 1, failures.size)
        assertSame(TestModuleId.A, failures[0].originModuleId)
        assertEquals("Throws", failures[0].effect)
    }

    @Test
    fun `EffectFailure with unknown origin moduleId returns Unrouted`() {
        // No module with id `TestModuleId.E` is registered, but we manually
        // dispatch an EffectFailure naming it.
        val module = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
        )
        val orchestrator = newOrchestrator(modules = listOf(module))

        val outcome = orchestrator.dispatch(
            Action.EffectFailure(
                originModuleId = TestModuleId.E,    // not registered
                effect = "SomeEffect",
                reason = "io",
            ),
        )

        assertTrue("expected Unrouted, got $outcome", outcome is DispatchOutcome.Unrouted)
    }

    @Test
    fun `EffectFailure with no reduceFailure override returns Rejected with reducer-null`() {
        // Module's default `reduceFailure` returns null — i.e. "no failure
        // path defined", which is semantically Rejected (not Unrouted).
        val module = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
            // failureReducer left at default (returns null)
        )
        val orchestrator = newOrchestrator(modules = listOf(module))

        val outcome = orchestrator.dispatch(
            Action.EffectFailure(
                originModuleId = TestModuleId.A,
                effect = "AnyEffect",
                reason = "io",
            ),
        )

        assertTrue("expected Rejected, got $outcome", outcome is DispatchOutcome.Rejected)
        assertEquals("reducer-null", (outcome as DispatchOutcome.Rejected).reason)
    }

    // ════════════════════════════════════════════════════════════════
    // Cross-module cascade
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `cross-module cascade dispatches the emitted action to its owning module`() {
        // When moduleA dispatches LanguageAction, moduleB observes the change
        // and cascades LivePromptAction.EnableLivePrompt to itself (B is the
        // owner of LivePromptAction).
        //
        // The cascade-trigger condition uses the lens counter (not prev/next
        // equality) because the test lens stores counters externally — the
        // global DictateUiState data class itself is not mutated by the test
        // modules. Production modules DO mutate the data class, so production
        // observers can use prev != next freely.
        val moduleA = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
        )
        var cascadeFired = false
        val moduleB = TestDictateModule(
            id = TestModuleId.B,
            actionClass = Action.LivePromptAction::class,
            lens = lens,
            crossModule = { _, _ ->
                // Emit once, on the first observation only (after moduleA's
                // reducer runs). Subsequent passes (from the cascade itself)
                // see the marker already true.
                if (!cascadeFired && lens.get(TestModuleId.A) == 1) {
                    cascadeFired = true
                    listOf(Action.LivePromptAction.EnableLivePrompt)
                } else emptyList()
            },
        )
        val orchestrator = newOrchestrator(modules = listOf(moduleA, moduleB))

        orchestrator.dispatch(Action.LanguageAction.RefreshFromPref)

        // A: incremented by initial dispatch
        assertEquals(1, lens.get(TestModuleId.A))
        // B: incremented by cascade-emitted EnableLivePrompt
        assertEquals(1, lens.get(TestModuleId.B))
    }

    @Test
    fun `self-cascade is allowed (KG-RSB-2-Fix - observer of own action runs)`() {
        // Module A observes its OWN state change and emits another action of
        // its own type. This is the KG-RSB-2-Fix path:
        // RecordingModule.Idle→Preparing self-emits OverlayAction.ResetSuppressBit
        // (production analogue).
        //
        // Each dispatch increments the counter. The first action increments to 1.
        // The self-cascade fires one more dispatch (counter to 2). Cascade observation
        // re-fires only when prev != next.
        val seen = mutableListOf<Int>()
        val module = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
            reducer = { state, _, _ ->
                seen += state
                TransitionResult(state + 1, emptyList())
            },
            crossModule = { prev, next ->
                // Cascade once on the FIRST observation only — we differentiate
                // by the lens counter.
                if (lens.get(TestModuleId.A) == 1) {
                    listOf(Action.LanguageAction.RefreshFromPref)
                } else {
                    emptyList()
                }
            },
        )
        val orchestrator = newOrchestrator(modules = listOf(module))

        orchestrator.dispatch(Action.LanguageAction.RefreshFromPref)

        // First dispatch → counter 0→1. Cross-module observer fires:
        //   prev != next AND lens.get == 1 → cascade Refresh.
        // Cascade dispatch → counter 1→2. Re-snapshot observer:
        //   prev != next AND lens.get == 2 → empty cascade.
        // Net: two dispatches, two seen values.
        assertEquals(listOf(0, 1), seen)
        assertEquals(2, lens.get(TestModuleId.A))
    }

    @Test
    fun `cascade actions are emitted in registry order`() {
        // moduleA → cascade { Z }, moduleB → cascade { Y }, moduleC → cascade { X }
        // When the registry order is A, B, C, the cascade list is [Z, Y, X] —
        // dispatched in that order. We verify by recording which side-effects ran.
        val observedOrder = mutableListOf<String>()

        val moduleX = TestDictateModule(
            id = TestModuleId.C,
            actionClass = Action.OverlayAction::class,
            lens = lens,
            reducer = { state, action, _ ->
                observedOrder += "X(${action::class.simpleName})"
                TransitionResult(state + 1, emptyList())
            },
        )
        val moduleY = TestDictateModule(
            id = TestModuleId.D,
            actionClass = Action.LayoutAction::class,
            lens = lens,
            reducer = { state, action, _ ->
                observedOrder += "Y(${action::class.simpleName})"
                TransitionResult(state + 1, emptyList())
            },
        )
        val moduleZ = TestDictateModule(
            id = TestModuleId.E,
            actionClass = Action.AudioAction::class,
            lens = lens,
            reducer = { state, action, _ ->
                observedOrder += "Z(${action::class.simpleName})"
                TransitionResult(state + 1, emptyList())
            },
        )

        // The trigger module — claims a different action. Its cross-module
        // hook returns the three target actions on the FIRST observation
        // (size==1 = trigger reducer just ran).
        //
        // Why size==1 instead of prev != next: the test lens stores counters
        // externally; the global DictateUiState isn't mutated by the test
        // modules. We gate the cascade on `observedOrder.size` (a test-side
        // marker) instead. Production modules DO mutate the data class.
        val trigger = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
            reducer = { state, _, _ ->
                observedOrder += "trigger"
                TransitionResult(state + 1, emptyList())
            },
            crossModule = { _, _ ->
                if (observedOrder.size == 1) {
                    // First time only — emit on the trigger pass.
                    listOf(
                        Action.AudioAction.ToggleAudioFocusPref,
                        Action.LayoutAction.ToggleSingleRowMode,
                        Action.OverlayAction.DismissOverlayOnboarding,
                    )
                } else emptyList()
            },
        )

        // Registry order: trigger, moduleZ (Audio), moduleY (Layout), moduleX (Overlay).
        // Each cascade dispatch re-runs all observers but no further cascade fires
        // (size > 1 short-circuits the trigger's hook).
        val orchestrator = newOrchestrator(modules = listOf(trigger, moduleZ, moduleY, moduleX))

        orchestrator.dispatch(Action.LanguageAction.RefreshFromPref)

        // The three cascade actions are dispatched in cascade-list order:
        //   1. AudioAction.ToggleAudioFocusPref → moduleZ
        //   2. LayoutAction.ToggleSingleRowMode → moduleY
        //   3. OverlayAction.DismissOverlayOnboarding → moduleX
        assertEquals(
            listOf("trigger", "Z(ToggleAudioFocusPref)", "Y(ToggleSingleRowMode)", "X(DismissOverlayOnboarding)"),
            observedOrder,
        )
    }

    // ════════════════════════════════════════════════════════════════
    // MAX_CASCADE_DEPTH guard
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `MAX_CASCADE_DEPTH cap stops runaway cascade (debug = error, release = Rejected)`() {
        // A module whose cross-module observer always emits its own action.
        // The cascade-depth counter trips at MAX_CASCADE_DEPTH = 8 (Spec 1
        // §4.3 + ADR-0002 §"MAX_CASCADE_DEPTH").
        //
        // Behaviour by build variant:
        //  - debug:   `error()` throws IllegalStateException at the cap →
        //             the recursive dispatch unwinds back to the user.
        //  - release: Log.e + return DispatchOutcome.Rejected(action, "cascade-loop")
        //             — silently swallowed by the recursive call; the outer
        //             dispatch eventually returns Applied because the
        //             initial action did succeed.
        //
        // Both unitTest variants (debug + release) execute this test; we
        // branch on BuildConfig.DEBUG so the test exercises both paths.
        val module = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
            crossModule = { _, _ -> listOf(Action.LanguageAction.RefreshFromPref) },
        )
        val orchestrator = newOrchestrator(modules = listOf(module))

        if (net.devemperor.dictate.BuildConfig.DEBUG) {
            try {
                orchestrator.dispatch(Action.LanguageAction.RefreshFromPref)
                fail("expected IllegalStateException from MAX_CASCADE_DEPTH error() on debug builds")
            } catch (t: IllegalStateException) {
                assertTrue(
                    "exception message should mention cascade-loop; got: ${t.message}",
                    t.message?.contains("Cascade loop detected") == true,
                )
            }
        } else {
            // Release path: no throw; the recursive dispatch hits Rejected,
            // but the outermost dispatch's chain still returns Applied
            // (the original action was applied).
            val outcome = orchestrator.dispatch(Action.LanguageAction.RefreshFromPref)
            assertSame(DispatchOutcome.Applied, outcome)
        }

        // Counter reached MAX_CASCADE_DEPTH applications before the cap fired
        // (same value in both variants).
        assertEquals(
            "Cascade should run MAX_CASCADE_DEPTH applications, then trip the cap",
            DictateOrchestrator.MAX_CASCADE_DEPTH,
            lens.get(TestModuleId.A),
        )
    }

    @Test
    fun `MAX_CASCADE_DEPTH constant is the documented value`() {
        // ADR-0002 §"MAX_CASCADE_DEPTH" pins the cap at 8. Surfacing it
        // here as an explicit test so a future change to the constant is
        // caught at code-review time (the constant is intentionally
        // conservative).
        assertEquals(8, DictateOrchestrator.MAX_CASCADE_DEPTH)
    }

    // ════════════════════════════════════════════════════════════════
    // Shutdown — module terminate-order
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `shutdown calls terminate on every module in registry order`() {
        val terminateOrder = mutableListOf<ModuleId>()
        val moduleA = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
            terminator = { _ -> terminateOrder += TestModuleId.A },
        )
        val moduleB = TestDictateModule(
            id = TestModuleId.B,
            actionClass = Action.LivePromptAction::class,
            lens = lens,
            terminator = { _ -> terminateOrder += TestModuleId.B },
        )
        val orchestrator = newOrchestrator(modules = listOf(moduleA, moduleB))

        orchestrator.shutdown()

        assertEquals(listOf<ModuleId>(TestModuleId.A, TestModuleId.B), terminateOrder)
    }

    @Test
    fun `shutdown continues after a module terminate throws`() {
        val terminateOrder = mutableListOf<ModuleId>()
        val moduleA = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
            terminator = { _ ->
                terminateOrder += TestModuleId.A
                throw IllegalStateException("test-thrown")
            },
        )
        val moduleB = TestDictateModule(
            id = TestModuleId.B,
            actionClass = Action.LivePromptAction::class,
            lens = lens,
            terminator = { _ -> terminateOrder += TestModuleId.B },
        )
        val orchestrator = newOrchestrator(modules = listOf(moduleA, moduleB))

        orchestrator.shutdown()    // must not propagate the throw

        assertEquals(
            "Both modules' terminate hooks must have been visited",
            listOf<ModuleId>(TestModuleId.A, TestModuleId.B),
            terminateOrder,
        )
    }

    // ════════════════════════════════════════════════════════════════
    // emitAction — async re-entry
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `emitAction posts dispatch to scope (async re-entry)`() {
        val module = TestDictateModule(
            id = TestModuleId.A,
            actionClass = Action.LanguageAction::class,
            lens = lens,
        )
        // Using Unconfined scope makes the launched coroutine run synchronously
        // in the test thread — emitAction completes before we assert.
        val orchestrator = newOrchestrator(modules = listOf(module))

        orchestrator.emitAction(Action.LanguageAction.RefreshFromPref)

        assertEquals(
            "emitAction should have triggered one dispatch on Unconfined scope",
            1,
            lens.get(TestModuleId.A),
        )
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    /** Build an orchestrator wired to the test [scope] + [store] + a no-op services fixture. */
    private fun newOrchestrator(modules: List<DictateModule<*, *, *>>): DictateOrchestrator {
        val registry = DictateModuleRegistry(modules)
        return DictateOrchestrator(
            scope = scope,
            store = store,
            services = fakeModuleServices(scope = scope),
            registry = registry,
        )
    }

}
