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

    // ── PC send-mode (ADR-0019) ─────────────────────────────────────────

    @Test
    fun `ToggleWindowsAutoSend flips the effective flag and persists the pref`() {
        val state = FeatureToggles(windowsPaired = true, windowsAutoSendActive = false)
        val result = module.reduce(state, Action.FeatureToggleAction.ToggleWindowsAutoSend, ctx())!!

        assertTrue("the button must light on this frame", result.nextState.windowsAutoSendActive)
        assertEquals(
            "the toggle's only surface is the keyboard, so the dispatch owns the write",
            listOf(FeatureToggleModule.Effect.PersistWindowsAutoSend(true)),
            result.sideEffects,
        )
    }

    @Test
    fun `ToggleWindowsAutoSend off persists false`() {
        val state = FeatureToggles(windowsPaired = true, windowsAutoSendActive = true)
        val result = module.reduce(state, Action.FeatureToggleAction.ToggleWindowsAutoSend, ctx())!!

        assertFalse(result.nextState.windowsAutoSendActive)
        assertEquals(
            listOf(FeatureToggleModule.Effect.PersistWindowsAutoSend(false)),
            result.sideEffects,
        )
    }

    @Test
    fun `ToggleWindowsAutoSend is rejected while no PC is paired`() {
        // Lighting the button without a target would claim the transcript goes
        // to the PC while ADR-0019's gate still sends it to the host field.
        val state = FeatureToggles(windowsPaired = false, windowsAutoSendActive = false)
        assertNull(module.reduce(state, Action.FeatureToggleAction.ToggleWindowsAutoSend, ctx()))
    }

    // ── Screen context (opt-in a11y) ────────────────────────────────────

    @Test
    fun `ToggleScreenContext flips the opt-in and persists it`() {
        val state = FeatureToggles(screenContextAvailable = true, screenContextEnabled = false)
        val result = module.reduce(state, Action.FeatureToggleAction.ToggleScreenContext, ctx())!!

        assertTrue(result.nextState.screenContextEnabled)
        assertEquals(
            listOf(FeatureToggleModule.Effect.PersistScreenContext(true)),
            result.sideEffects,
        )
    }

    @Test
    fun `ToggleScreenContext is rejected while the a11y service is off`() {
        // Lighting the button would promise context that no read can deliver.
        val state = FeatureToggles(screenContextAvailable = false)
        assertNull(module.reduce(state, Action.FeatureToggleAction.ToggleScreenContext, ctx()))
    }

    @Test
    fun `SetScreenContextAvailable only emits on a real change`() {
        // Pushed on every onStartInputView; a no-op must not churn the store.
        val state = FeatureToggles(screenContextAvailable = true)
        assertNull(
            module.reduce(state, Action.FeatureToggleAction.SetScreenContextAvailable(true), ctx()),
        )
        val flipped = module.reduce(
            state, Action.FeatureToggleAction.SetScreenContextAvailable(false), ctx(),
        )!!
        assertFalse(flipped.nextState.screenContextAvailable)
    }

    @Test
    fun `losing the a11y service does not clear the user's opt-in`() {
        // The opt-in is the user's intent; the service being off is a fact
        // about the system. Clearing the pref would silently forget the intent
        // and make them re-opt-in after every OS hiccup.
        val state = FeatureToggles(screenContextAvailable = true, screenContextEnabled = true)
        val next = module.reduce(
            state, Action.FeatureToggleAction.SetScreenContextAvailable(false), ctx(),
        )!!.nextState
        assertTrue(next.screenContextEnabled)
        assertFalse(next.screenContextAvailable)
    }

    // ── PC-only terminal mode (pc-dictation-activity) ───────────────────

    @Test
    fun `SetPcOnly flips the transient mode with no side effect`() {
        val on = module.reduce(FeatureToggles(pcOnly = false), Action.FeatureToggleAction.SetPcOnly(true), ctx())!!
        assertTrue(on.nextState.pcOnly)
        assertTrue("pcOnly is purely transient — never persisted", on.sideEffects.isEmpty())

        val off = module.reduce(FeatureToggles(pcOnly = true), Action.FeatureToggleAction.SetPcOnly(false), ctx())!!
        assertFalse(off.nextState.pcOnly)
    }

    @Test
    fun `SetPcOnly only emits on a real change`() {
        // The Activity may re-push on every resume/rebind; a no-op must not churn the store.
        val state = FeatureToggles(pcOnly = true)
        assertNull(module.reduce(state, Action.FeatureToggleAction.SetPcOnly(true), ctx()))
    }

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
