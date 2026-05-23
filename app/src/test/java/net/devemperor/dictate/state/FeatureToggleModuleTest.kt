package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-reducer tests for [FeatureToggleModule].
 *
 * Coverage:
 * - Each of the four owned toggles flips its mirrored field
 * - `ToggleVibration` returns null in Phase 1 (cross-axis — see module KDoc)
 * - Lens + id + initial state
 */
class FeatureToggleModuleTest {

    private val module = FeatureToggleModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial())

    @Test
    fun `ToggleRewording flips rewordingEnabled`() {
        val state = FeatureToggles(rewordingEnabled = true)
        val result = module.reduce(state, Action.FeatureToggleAction.ToggleRewording, ctx())
        assertFalse(result!!.nextState.rewordingEnabled)
    }

    @Test
    fun `ToggleAutoFormatting flips autoFormattingEnabled`() {
        val state = FeatureToggles(autoFormattingEnabled = false)
        val result = module.reduce(state, Action.FeatureToggleAction.ToggleAutoFormatting, ctx())
        assertTrue(result!!.nextState.autoFormattingEnabled)
    }

    @Test
    fun `ToggleInstantOutput flips instantOutputEnabled`() {
        val state = FeatureToggles(instantOutputEnabled = true)
        val result = module.reduce(state, Action.FeatureToggleAction.ToggleInstantOutput, ctx())
        assertFalse(result!!.nextState.instantOutputEnabled)
    }

    @Test
    fun `ToggleAutoEnter flips autoEnterEnabled`() {
        val state = FeatureToggles(autoEnterEnabled = false)
        val result = module.reduce(state, Action.FeatureToggleAction.ToggleAutoEnter, ctx())
        assertTrue(result!!.nextState.autoEnterEnabled)
    }

    @Test
    fun `ToggleVibration returns null in Phase 1 (cross-axis deviation)`() {
        // vibrationEnabled lives on AudioState, not FeatureToggles.
        // The reducer rejects to preserve the lens invariant; the
        // legacy SP-write path keeps the UI functional until B3 wires
        // ToggleVibration through Action.AudioAction.
        val state = FeatureToggles()
        assertNull(module.reduce(state, Action.FeatureToggleAction.ToggleVibration, ctx()))
    }

    @Test
    fun `module id is FeatureToggle`() {
        assertEquals(ModuleId.FeatureToggle, module.id)
    }

    @Test
    fun `lens round-trip preserves features axis`() {
        val custom = FeatureToggles(
            rewordingEnabled = false,
            autoFormattingEnabled = true,
            instantOutputEnabled = false,
            autoEnterEnabled = true,
        )
        val state = DictateUiState.initial().copy(features = custom)
        assertEquals(custom, module.read(state))
        val back = module.write(state, FeatureToggles())
        assertEquals(FeatureToggles(), back.features)
    }

    @Test
    fun `initial state is default FeatureToggles`() {
        assertEquals(FeatureToggles(), module.initialState())
    }
}
