// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import kotlin.reflect.KClass

/**
 * Owns the [FeatureToggles] axis — four user-toggle booleans
 * (`rewordingEnabled`, `autoFormattingEnabled`, `instantOutputEnabled`,
 * `autoEnterEnabled`). All four are Pref-mirrored via
 * `PipelinePrefMirror` (C7).
 *
 * **`ToggleVibration` deviation note (Phase 1):**
 *
 * [Action.FeatureToggleAction.ToggleVibration] exists in the action
 * hierarchy because the legacy UI groups all five toggles together,
 * but `vibrationEnabled` lives on [AudioState] (owned by
 * [AudioModule]), not on [FeatureToggles]. Cross-axis writes are
 * forbidden by the lens (ADR-0001 §"Pure-Reducer Invariant"). For
 * Phase 1 the reducer returns `null` for `ToggleVibration`
 * (`DispatchOutcome.Rejected("reducer-null")`) — the legacy UI keeps
 * its own SP-write path until the action surface is corrected to
 * dispatch [Action.AudioAction] for vibration. The five-action
 * grouping is preserved so the Action hierarchy is stable across
 * phases.
 *
 * **No cross-module observer:** Spec 1 §15.1 explicitly lists this
 * module as observer-free. Pipeline reads `features.autoEnterEnabled`
 * during the auto-enter flow (Coupling-Matrix §15.1.x row
 * `FeatureToggle × Pipeline`) — that's a Pipeline-side read, not a
 * FeatureToggle cascade.
 *
 * **No effects.** All four toggles are Pref-mirrored — SP writes
 * happen through `PipelinePrefMirror` (C7).
 *
 * @see net.devemperor.dictate.state.FeatureToggles
 * @see net.devemperor.dictate.state.Action.FeatureToggleAction
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §15.1
 */
object FeatureToggleModule : DictateModule<FeatureToggles, Action.FeatureToggleAction, FeatureToggleModule.Effect> {

    override val id: ModuleId = ModuleId.FeatureToggle
    override val actionClass: KClass<Action.FeatureToggleAction> = Action.FeatureToggleAction::class

    override fun read(global: DictateUiState): FeatureToggles = global.features
    override fun write(global: DictateUiState, sub: FeatureToggles): DictateUiState =
        global.copy(features = sub)

    override fun initialState(): FeatureToggles = FeatureToggles()

    /**
     * No module-local effects — see Pref-mirror note in the module KDoc.
     */
    sealed interface Effect : SideEffect

    override fun reduce(
        state: FeatureToggles,
        action: Action.FeatureToggleAction,
        ctx: ReducerContext,
    ): TransitionResult<FeatureToggles, Effect>? = when (action) {

        Action.FeatureToggleAction.ToggleRewording -> TransitionResult(
            nextState = state.copy(rewordingEnabled = !state.rewordingEnabled),
            sideEffects = emptyList(),
        )

        Action.FeatureToggleAction.ToggleAutoFormatting -> TransitionResult(
            nextState = state.copy(autoFormattingEnabled = !state.autoFormattingEnabled),
            sideEffects = emptyList(),
        )

        Action.FeatureToggleAction.ToggleInstantOutput -> TransitionResult(
            nextState = state.copy(instantOutputEnabled = !state.instantOutputEnabled),
            sideEffects = emptyList(),
        )

        Action.FeatureToggleAction.ToggleAutoEnter -> TransitionResult(
            nextState = state.copy(autoEnterEnabled = !state.autoEnterEnabled),
            sideEffects = emptyList(),
        )

        // See "ToggleVibration deviation" in the module KDoc.
        Action.FeatureToggleAction.ToggleVibration -> null
    }

    override fun runEffect(effect: Effect, services: ModuleServices) {
        // No effects — see [Effect] KDoc. Empty sealed interface.
    }
}
