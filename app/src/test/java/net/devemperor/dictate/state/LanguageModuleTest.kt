package net.devemperor.dictate.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.devemperor.dictate.testutil.FakePipelineSessionRepo
import net.devemperor.dictate.testutil.fakeModuleServices
import net.devemperor.dictate.testutil.testPipelineRecovery
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-reducer tests for [LanguageModule] plus an end-to-end
 * dispatch-path test proving the D-13 settings-change propagation.
 *
 * Coverage:
 * - SetOverride sets / clears the override field (idempotent on equal)
 * - RefreshFromPref(effective) writes `effective` (idempotent on equal)
 * - Lens + id + initial state
 * - **AC-5 propagation:** a settings-activity language change reaches
 *   `LanguageState.effective` via the IME's Pre-Dispatch-Resolution
 *   (`LanguageResolver` → `RefreshFromPref(code)` dispatch), driven here
 *   through a real [DictateOrchestrator] so the next transcription /
 *   F-15 RenderBackend read sees the new language.
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
    fun `RefreshFromPref writes the resolved effective language`() {
        // D-13: the action is now payload-bearing. The caller resolved the
        // permanent language from prefs (LanguageResolver) before dispatch;
        // the reducer writes it into `effective`.
        val state = LanguageState(effective = "system")
        val result = module.reduce(
            state,
            Action.LanguageAction.RefreshFromPref(effective = "de"),
            ctx(),
        )
        assertEquals("de", result!!.nextState.effective)
        assertNull(result.nextState.override) // override untouched
    }

    @Test
    fun `RefreshFromPref with an unchanged effective returns null`() {
        // Idempotent — a no-change refresh must not emit a no-op state.
        val state = LanguageState(effective = "en")
        assertNull(
            module.reduce(state, Action.LanguageAction.RefreshFromPref(effective = "en"), ctx()),
        )
    }

    @Test
    fun `RefreshFromPref does not clear an active override`() {
        // The transient ReprocessStaging override survives a permanent
        // pref-refresh; only SetOverride(null) clears it.
        val state = LanguageState(effective = "en", override = "fr")
        val result = module.reduce(
            state,
            Action.LanguageAction.RefreshFromPref(effective = "de"),
            ctx(),
        )
        assertEquals("de", result!!.nextState.effective)
        assertEquals("fr", result.nextState.override)
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

    // ════════════════════════════════════════════════════════════════
    // AC-5 — settings-change propagation through a real dispatch
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `settings language change propagates to LanguageState_effective via dispatch`() {
        // Build a real orchestrator wired with the production LanguageModule.
        // The IME's Pre-Dispatch-Resolution (Spec 1 §4.11) is simulated:
        // the resolved code is passed as the RefreshFromPref payload — the
        // exact path DictateInputMethodService.pushPermanentLanguageToOrchestrator
        // takes after a Settings write fires its inputLanguagesListener.
        val store = DictateUiStateStore(DictateUiState.initial())
        val orchestrator = DictateOrchestrator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            store = store,
            services = fakeModuleServices(),
            registry = DictateModuleRegistry(listOf(LanguageModule)),
            prefMirror = PipelinePrefMirror(FakeSharedPreferences()),
            recovery = testPipelineRecovery(FakePipelineSessionRepo()),
        )

        // Boot sentinel before any RefreshFromPref.
        assertEquals("system", store.snapshot.language.effective)

        // Settings activity changed the language to "de"; the IME resolved
        // it and dispatched. The reducer must write it through.
        val outcome = orchestrator.dispatch(
            Action.LanguageAction.RefreshFromPref(effective = "de"),
        )

        assertEquals(DispatchOutcome.Applied, outcome)
        // The next transcription-config snapshot + F-15 RenderBackend read
        // now see "de" instead of the "system" sentinel.
        assertEquals("de", store.snapshot.language.effective)
    }
}
