package net.devemperor.dictate.state

import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.reflect.KClass

/**
 * Type-token / smoke tests for [DictateModule].
 *
 * **Why no fake module here?** [DictateModule] is a `sealed interface`
 * (Spec 1 §4.1 + ADR-0001 §"Module contract"): implementations are only
 * allowed inside the production `main` Kotlin module — the registry of
 * "all modules that exist" per the binding contract. Test code cannot
 * legally create a fake implementation, so we cannot exercise the
 * methods via a stand-in.
 *
 * What we *can* verify at this skeleton chunk (C3):
 *
 * - The interface compiles and is referenceable from test code.
 * - Companion / dependent types ([Action], [SideEffect], [ModuleId],
 *   [ModuleServices], [TransitionResult], [ReducerContext], [PrefBinding])
 *   are reachable.
 *
 * Actual behavioural verification (reduce / runEffect /
 * onCrossModuleStateChange / reduceFailure / prefBindings / terminate)
 * happens once concrete modules exist (Chunks C5 + C6); each module's
 * own test exercises its own surface. AUDIT-TEST in Phase 3.2 will
 * sweep the block to ensure coverage of the interface surface.
 *
 * Quality-Gate references:
 *  - K-1: no mocking framework.
 *  - K-4: pure JVM — no Android Context.
 */
class DictateModuleTest {

    @Test
    fun `DictateModule type token is referenceable from test code`() {
        val token: KClass<DictateModule<*, *, *>> = DictateModule::class
        assertNotNull("DictateModule::class must be a valid type token", token)
    }

    @Test
    fun `companion types referenced by DictateModule signatures are all reachable`() {
        // No assertions on values; the value of this test is that it
        // compiles — if any of these types disappear, this file won't
        // build. That is a deliberate compile-time safety net for the
        // C3 skeleton: any later refactor that breaks the public type
        // surface of state-core has to update this file too.
        val ids: ModuleId = ModuleId.Recording
        val state: DictateUiState = DictateUiState.initial()
        val action: Action = Action.RecordingAction.PauseRecording
        val effect: SideEffect = object : SideEffect {}
        val ctx: ReducerContext = ReducerContext(global = state, now = 0L)
        val result: TransitionResult<RecordingState, *> =
            TransitionResult<RecordingState, SideEffectMarker>(nextState = RecordingState.Idle)
        val services: ModuleServices = ModuleServices()
        val binding: PrefBinding<RecordingState, Int> = PrefBinding(
            prefKey = "x",
            read = { 0 },
            write = { s, _ -> s },
        )

        assertNotNull(ids)
        assertNotNull(state)
        assertNotNull(action)
        assertNotNull(effect)
        assertNotNull(ctx)
        assertNotNull(result)
        assertNotNull(services)
        assertNotNull(binding)
    }

    /**
     * Stand-in `SideEffect` marker for `TransitionResult` generic
     * instantiation. Test-local — production code uses sealed per-module
     * effect interfaces (Spec 1 §15.x).
     */
    private interface SideEffectMarker : SideEffect
}
