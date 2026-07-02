package net.devemperor.dictate.state

import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.testutil.FakeSharedPreferences
import net.devemperor.dictate.testutil.fakeModuleServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-reducer tests for [ThemingModule].
 *
 * The former `SetTheme` / `SetAccentColor` / `SetOverlayCharacters` /
 * `SetOutputSpeed` tests were deleted together with the dead setters
 * (F-037 trim, widget-transparency spec 2026-07-02) — those fields are
 * fed exclusively by the `PipelinePrefMirror`, covered in
 * `PipelinePrefMirrorTest`.
 *
 * Coverage:
 * - `SetWidgetOpacity` updates the field + emits the persist effect
 *   (idempotent against same-value dispatch)
 * - `PersistWidgetOpacity` writes `Pref.WidgetOpacity`
 * - Lens + id + initial state
 */
class ThemingModuleTest {

    private val module = ThemingModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial())

    @Test
    fun `SetWidgetOpacity updates widgetOpacity and emits persist effect`() {
        val state = ThemingState(widgetOpacity = 100)
        val result = module.reduce(state, Action.ThemingAction.SetWidgetOpacity(55), ctx())
        assertEquals(55, result!!.nextState.widgetOpacity)
        assertEquals(
            // The persist effect is mandatory — a state-only setter
            // would be silently reverted on the next mirror sync (the
            // F-037 failure mode this arm explicitly closes).
            listOf(ThemingModule.Effect.PersistWidgetOpacity(55)),
            result.sideEffects,
        )
    }

    @Test
    fun `SetWidgetOpacity with same value returns null`() {
        val state = ThemingState(widgetOpacity = 60)
        assertNull(module.reduce(state, Action.ThemingAction.SetWidgetOpacity(60), ctx()))
    }

    @Test
    fun `PersistWidgetOpacity effect writes Pref_WidgetOpacity`() {
        val sp = FakeSharedPreferences()
        val services = fakeModuleServices(sharedPrefs = sp)

        module.runEffect(ThemingModule.Effect.PersistWidgetOpacity(35), services)

        assertEquals(35, sp.get(Pref.WidgetOpacity))
    }

    @Test
    fun `module id is Theming`() {
        assertEquals(ModuleId.Theming, module.id)
    }

    @Test
    fun `lens round-trip preserves theming axis`() {
        val custom = ThemingState(
            theme = "dark",
            accentColor = 1,
            overlayCharacters = "?",
            outputSpeed = 9,
            widgetOpacity = 40,
        )
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
