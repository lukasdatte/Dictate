// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import kotlin.reflect.KClass

/**
 * Owns the [ThemingState] axis — `theme`, `accentColor`,
 * `overlayCharacters`, and `outputSpeed`. All four are Pref-mirrored
 * via `PipelinePrefMirror` (C7).
 *
 * **No cross-module observer:** Spec 1 §15.1 lists this module as
 * observer-free. Theming changes are observed by the rendering side
 * (KeyboardLayoutManager via `state.collect`); other modules do not
 * react to theming.
 *
 * **No effects.** All four fields are Pref-mirrored — SP writes happen
 * through `PipelinePrefMirror` (C7).
 *
 * @see net.devemperor.dictate.state.ThemingState
 * @see net.devemperor.dictate.state.Action.ThemingAction
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §15.1
 */
object ThemingModule : DictateModule<ThemingState, Action.ThemingAction, ThemingModule.Effect> {

    override val id: ModuleId = ModuleId.Theming
    override val actionClass: KClass<Action.ThemingAction> = Action.ThemingAction::class

    override fun read(global: DictateUiState): ThemingState = global.theming
    override fun write(global: DictateUiState, sub: ThemingState): DictateUiState =
        global.copy(theming = sub)

    override fun initialState(): ThemingState = ThemingState()

    /**
     * No module-local effects — see Pref-mirror note in the module KDoc.
     */
    sealed interface Effect : SideEffect

    override fun reduce(
        state: ThemingState,
        action: Action.ThemingAction,
        ctx: ReducerContext,
    ): TransitionResult<ThemingState, Effect>? = when (action) {

        is Action.ThemingAction.SetTheme ->
            if (action.theme != state.theme) {
                TransitionResult(
                    nextState = state.copy(theme = action.theme),
                    sideEffects = emptyList(),
                )
            } else null

        is Action.ThemingAction.SetAccentColor ->
            if (action.color != state.accentColor) {
                TransitionResult(
                    nextState = state.copy(accentColor = action.color),
                    sideEffects = emptyList(),
                )
            } else null

        is Action.ThemingAction.SetOverlayCharacters ->
            if (action.chars != state.overlayCharacters) {
                TransitionResult(
                    nextState = state.copy(overlayCharacters = action.chars),
                    sideEffects = emptyList(),
                )
            } else null

        is Action.ThemingAction.SetOutputSpeed ->
            if (action.speed != state.outputSpeed) {
                TransitionResult(
                    nextState = state.copy(outputSpeed = action.speed),
                    sideEffects = emptyList(),
                )
            } else null
    }

    override fun runEffect(effect: Effect, services: ModuleServices) {
        // No effects — see [Effect] KDoc. Empty sealed interface.
    }
}
