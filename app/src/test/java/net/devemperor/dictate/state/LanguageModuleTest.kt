package net.devemperor.dictate.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-reducer tests for [LanguageModule].
 *
 * Coverage:
 * - SetOverride sets / clears the override field (idempotent on equal)
 * - RefreshFromPref returns null in Phase 1 (acknowledgement signal)
 * - Lens + id + initial state
 */
class LanguageModuleTest {

    private val module = LanguageModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial())

    @Test
    fun `SetOverride installs override`() {
        val state = LanguageState(effective = "en", override = null)
        val result = module.reduce(state, Action.LanguageAction.SetOverride(code = "de"), ctx())
        assertEquals("de", result!!.nextState.override)
        assertEquals("en", result.nextState.effective)  // effective untouched
    }

    @Test
    fun `SetOverride null clears override`() {
        val state = LanguageState(effective = "en", override = "de")
        val result = module.reduce(state, Action.LanguageAction.SetOverride(code = null), ctx())
        assertNull(result!!.nextState.override)
    }

    @Test
    fun `SetOverride with same value returns null`() {
        val state = LanguageState(effective = "en", override = "de")
        assertNull(module.reduce(state, Action.LanguageAction.SetOverride(code = "de"), ctx()))
    }

    @Test
    fun `RefreshFromPref returns null in Phase 1`() {
        // Phase-1 placeholder: the dispatch acknowledges the refresh but
        // does not carry a payload. The legacy LanguageController still
        // owns the SP read until B3 wires the dispatch surface.
        val state = LanguageState(effective = "en")
        assertNull(module.reduce(state, Action.LanguageAction.RefreshFromPref, ctx()))
    }

    @Test
    fun `module id is Language`() {
        assertEquals(ModuleId.Language, module.id)
    }

    @Test
    fun `lens round-trip preserves language axis`() {
        val state = DictateUiState.initial().copy(
            language = LanguageState(effective = "fr", override = "es"),
        )
        assertEquals(LanguageState(effective = "fr", override = "es"), module.read(state))
        val back = module.write(state, LanguageState(effective = "ja"))
        assertEquals(LanguageState(effective = "ja"), back.language)
    }

    @Test
    fun `initial state is system-effective LanguageState`() {
        assertEquals(LanguageState(effective = "system"), module.initialState())
    }
}
