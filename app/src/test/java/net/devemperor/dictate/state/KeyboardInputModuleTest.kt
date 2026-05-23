package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-reducer tests for [KeyboardInputModule] (Spec 1 §15.6).
 *
 * Coverage:
 * - Each of the four input actions translates 1:1 to the matching Effect
 * - State stays `Unit` throughout (Unit-state contract)
 * - Lens write returns the global state unchanged
 * - id + initial state
 */
class KeyboardInputModuleTest {

    private val module = KeyboardInputModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial())

    @Test
    fun `Backspace emits SendBackspace effect`() {
        val result = module.reduce(Unit, Action.KeyboardInputAction.Backspace, ctx())
        assertEquals(Unit, result!!.nextState)
        assertTrue(result.sideEffects.contains(KeyboardInputModule.Effect.SendBackspace))
    }

    @Test
    fun `EnterKey emits SendEnter effect`() {
        val result = module.reduce(Unit, Action.KeyboardInputAction.EnterKey, ctx())
        assertTrue(result!!.sideEffects.contains(KeyboardInputModule.Effect.SendEnter))
    }

    @Test
    fun `SpaceKey emits SendSpace effect`() {
        val result = module.reduce(Unit, Action.KeyboardInputAction.SpaceKey, ctx())
        assertTrue(result!!.sideEffects.contains(KeyboardInputModule.Effect.SendSpace))
    }

    @Test
    fun `CopyToClipboard emits the typed effect with the same text payload`() {
        val result = module.reduce(
            Unit,
            Action.KeyboardInputAction.CopyToClipboard("hello"),
            ctx(),
        )
        assertTrue(
            result!!.sideEffects.contains(KeyboardInputModule.Effect.CopyToClipboard("hello")),
        )
    }

    @Test
    fun `module id is KeyboardInput`() {
        assertEquals(ModuleId.KeyboardInput, module.id)
    }

    @Test
    fun `lens write returns the same global state (Unit-state)`() {
        val global = DictateUiState.initial()
        assertSame(global, module.write(global, Unit))
    }

    @Test
    fun `initial state is Unit`() {
        assertEquals(Unit, module.initialState())
    }
}
