package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-reducer tests for [ThemingModule].
 *
 * Coverage:
 * - Each of the four setters updates the corresponding field (idempotent)
 * - Lens + id + initial state
 */
class ThemingModuleTest {

    private val module = ThemingModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial())

    @Test
    fun `SetTheme updates theme`() {
        val state = ThemingState(theme = "system")
        val result = module.reduce(state, Action.ThemingAction.SetTheme("dark"), ctx())
        assertEquals("dark", result!!.nextState.theme)
    }

    @Test
    fun `SetTheme with same value returns null`() {
        val state = ThemingState(theme = "light")
        assertNull(module.reduce(state, Action.ThemingAction.SetTheme("light"), ctx()))
    }

    @Test
    fun `SetAccentColor updates accentColor`() {
        val state = ThemingState(accentColor = -1)
        val result = module.reduce(state, Action.ThemingAction.SetAccentColor(0x123456), ctx())
        assertEquals(0x123456, result!!.nextState.accentColor)
    }

    @Test
    fun `SetAccentColor with same value returns null`() {
        val state = ThemingState(accentColor = 42)
        assertNull(module.reduce(state, Action.ThemingAction.SetAccentColor(42), ctx()))
    }

    @Test
    fun `SetOverlayCharacters updates overlayCharacters`() {
        val state = ThemingState(overlayCharacters = "abc")
        val result = module.reduce(state, Action.ThemingAction.SetOverlayCharacters("xyz"), ctx())
        assertEquals("xyz", result!!.nextState.overlayCharacters)
    }

    @Test
    fun `SetOverlayCharacters with same value returns null`() {
        val state = ThemingState(overlayCharacters = "()-:!?,.")
        assertNull(module.reduce(state, Action.ThemingAction.SetOverlayCharacters("()-:!?,."), ctx()))
    }

    @Test
    fun `SetOutputSpeed updates outputSpeed`() {
        val state = ThemingState(outputSpeed = 5)
        val result = module.reduce(state, Action.ThemingAction.SetOutputSpeed(10), ctx())
        assertEquals(10, result!!.nextState.outputSpeed)
    }

    @Test
    fun `SetOutputSpeed with same value returns null`() {
        val state = ThemingState(outputSpeed = 5)
        assertNull(module.reduce(state, Action.ThemingAction.SetOutputSpeed(5), ctx()))
    }

    @Test
    fun `module id is Theming`() {
        assertEquals(ModuleId.Theming, module.id)
    }

    @Test
    fun `lens round-trip preserves theming axis`() {
        val custom = ThemingState(theme = "dark", accentColor = 1, overlayCharacters = "?", outputSpeed = 9)
        val state = DictateUiState.initial().copy(theming = custom)
        assertEquals(custom, module.read(state))
        val back = module.write(state, ThemingState())
        assertEquals(ThemingState(), back.theming)
    }

    @Test
    fun `initial state is default ThemingState`() {
        assertEquals(ThemingState(), module.initialState())
    }
}
