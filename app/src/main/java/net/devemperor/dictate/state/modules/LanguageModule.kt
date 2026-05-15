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
 * **Migration from the legacy language controller (Spec 1 §15.1,
 * D-13 / Epic §4 Block C1):**
 *
 * The previous `core` language controller resolved the effective
 * language from two sources (curated-list + pref-pos for permanent,
 * `PipelineUiState.ReprocessStaging.selectedLanguage` for the
 * transient override) and pushed callbacks to the IME. It has been
 * **deleted**. LanguageModule is now the sole language SoT — the
 * `language` axis carries both fields and the IME observes the state
 * directly via [DictateUiStateStore.state]. The permanent SP read /
 * write surface moved to
 * [net.devemperor.dictate.preferences.LanguageResolver] (the
 * unbound-path SoT, used by the Settings UI and the pre-bind IME).
 *
 * **`effective` vs `override`:**
 *
 * - `effective` — the permanent pref-resolved language. The IME
 *   resolves it from `SharedPreferences` via
 *   [net.devemperor.dictate.preferences.LanguageResolver.effectiveLanguage]
 *   and dispatches [Action.LanguageAction.RefreshFromPref] carrying the
 *   code; the reducer writes it here.
 * - `override` — set per Reprocess-Staging session via
 *   [Action.LanguageAction.SetOverride]; null clears the override and
 *   falls back to `effective`. **Never persisted.**
 *
 * The reducer stays I/O-free: the SP read happens **before** dispatch
 * (Spec 1 §4.11 Pre-Dispatch-Resolution pattern) so the module remains
 * a pure state-transition function.
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
     * No effects. The SharedPreferences read/write surface lives in
     * [net.devemperor.dictate.preferences.LanguageResolver]; the caller
     * resolves before dispatch and this module only mutates state, so no
     * effect channel is needed.
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

        // Payload-bearing pref-refresh (D-13). The caller resolved the
        // permanent effective language from SharedPreferences via
        // LanguageResolver before dispatch (Spec 1 §4.11
        // Pre-Dispatch-Resolution); the reducer writes it. Idempotent —
        // a refresh that does not change the value reduces to null so the
        // store does not emit a no-op state.
        is Action.LanguageAction.RefreshFromPref ->
            if (action.effective != state.effective) {
                TransitionResult(
                    nextState = state.copy(effective = action.effective),
                    sideEffects = emptyList(),
                )
            } else null
    }

    override fun runEffect(effect: Effect, services: ModuleServices) {
        // No effects — see [Effect] KDoc. Empty sealed interface.
    }
}
