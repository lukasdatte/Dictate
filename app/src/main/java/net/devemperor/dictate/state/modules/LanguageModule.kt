// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import kotlin.reflect.KClass

/**
 * Owns the [LanguageState] axis — the effective transcription language
 * plus an optional ReprocessStaging override.
 *
 * **Migration from the legacy `LanguageController` (Spec 1 §15.1):**
 *
 * The previous `core.LanguageController` resolved the effective
 * language from two sources (curated-list + pref-pos for permanent,
 * `PipelineUiState.ReprocessStaging.selectedLanguage` for the
 * transient override) and pushed callbacks to the IME. LanguageModule
 * subsumes that responsibility — the `language` axis carries both
 * fields and the IME observes the state directly via
 * [DictateUiStateStore.state].
 *
 * In Phase 1, the controller code is left in place; the module is the
 * state-owner for the new state pipeline. B3
 * (Subsystem-Adapter-Migration) wires the legacy controller through to
 * dispatch [Action.LanguageAction] for state updates; later phases
 * delete the controller entirely.
 *
 * **`effective` vs `override`:**
 *
 * - `effective` — the permanent pref-resolved language (read from
 *   `Pref.InputLanguagePos` + curated list at boot, refreshed via
 *   [Action.LanguageAction.RefreshFromPref]).
 * - `override` — set per Reprocess-Staging session via
 *   [Action.LanguageAction.SetOverride]; null clears the override and
 *   falls back to `effective`. **Never persisted.**
 *
 * The reducer is intentionally trivial — pref reads happen in B3 (the
 * IME caller resolves and dispatches `RefreshFromPref(value)` style;
 * for Phase 1, the reducer just acknowledges the action without an
 * I/O-bound payload). The actual SP-read happens before dispatch — see
 * Spec 1 §4.11 Pre-Dispatch-Resolution pattern.
 *
 * **No cross-module observer:** the only inbound cascade is
 * `LivePrompt → Language` (`LanguageAction.SetOverride` from a
 * LivePrompt chain), which LanguageModule receives via the normal
 * dispatch path; no outbound cascade originates here.
 *
 * @see net.devemperor.dictate.state.LanguageState
 * @see net.devemperor.dictate.state.Action.LanguageAction
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §15.1
 */
object LanguageModule : DictateModule<LanguageState, Action.LanguageAction, LanguageModule.Effect> {

    override val id: ModuleId = ModuleId.Language
    override val actionClass: KClass<Action.LanguageAction> = Action.LanguageAction::class

    override fun read(global: DictateUiState): LanguageState = global.language
    override fun write(global: DictateUiState, sub: LanguageState): DictateUiState =
        global.copy(language = sub)

    override fun initialState(): LanguageState = LanguageState(effective = "system")

    /**
     * No effects in Phase 1. The legacy `LanguageController` still owns
     * the SharedPreferences read/write surface (curated-list + pos); B3
     * wires the controller to dispatch [Action.LanguageAction] after
     * its own writes settle, so the state mirror updates without a
     * round-trip through this module's effect channel.
     */
    sealed interface Effect : SideEffect

    override fun reduce(
        state: LanguageState,
        action: Action.LanguageAction,
        ctx: ReducerContext,
    ): TransitionResult<LanguageState, Effect>? = when (action) {

        is Action.LanguageAction.SetOverride ->
            if (action.code != state.override) {
                TransitionResult(
                    nextState = state.copy(override = action.code),
                    sideEffects = emptyList(),
                )
            } else null

        // Pref-refresh acknowledgement — the caller (legacy controller
        // path in B3) computes the new effective language from
        // SharedPreferences before dispatching. Phase 1 keeps the
        // mutation in `effective` an explicit no-op-or-update pattern
        // by re-reading from ctx.global (already mirrored) — the
        // refresh trigger is consumed as a re-emit signal. Once B3
        // wires the dispatcher to carry the resolved code, this will
        // become a typed-payload action.
        Action.LanguageAction.RefreshFromPref -> null
    }

    override fun runEffect(effect: Effect, services: ModuleServices) {
        // No effects — see [Effect] KDoc. Empty sealed interface.
    }
}
