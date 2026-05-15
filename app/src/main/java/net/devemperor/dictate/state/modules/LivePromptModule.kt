// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import kotlin.reflect.KClass

/**
 * Owns the [LivePromptState] axis — `enabled` (user-toggle) and
 * `pendingChain` (a follow-up prompt is queued).
 *
 * **Cross-module cascade (Coupling-Matrix §15.1.x):**
 *
 * - `Pipeline → LivePrompt`: PipelineModule cascades
 *   [Action.LivePromptAction.ChainNext] after a successful
 *   `PipelineDone` *if* `livePrompt.enabled && livePrompt.pendingChain`
 *   (see PipelineModule.onCrossModuleStateChange). The cascade lives in
 *   PipelineModule; LivePromptModule only **owns** the resulting state
 *   transitions for its own axis.
 *
 * In Phase 1 the module is intentionally minimal — its surface is the
 * three actions on the data axis. Chain emission to the next pipeline
 * is handled at the resolver / IME level once the implementation lands
 * (Phase-2 follow-up). The reducer ensures the state axis is correct
 * regardless of where the trigger originates.
 *
 * @see net.devemperor.dictate.state.LivePromptState
 * @see net.devemperor.dictate.state.Action.LivePromptAction
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §15.1
 */
object LivePromptModule : DictateModule<LivePromptState, Action.LivePromptAction, LivePromptModule.Effect> {

    override val id: ModuleId = ModuleId.LivePrompt
    override val actionClass: KClass<Action.LivePromptAction> = Action.LivePromptAction::class

    override fun read(global: DictateUiState): LivePromptState = global.livePrompt
    override fun write(global: DictateUiState, sub: LivePromptState): DictateUiState =
        global.copy(livePrompt = sub)

    override fun initialState(): LivePromptState = LivePromptState()

    /**
     * No module-local effects in Phase 1. The chain-trigger that
     * eventually submits a follow-up pipeline lives in the
     * PipelineModule cascade pathway (see Spec 1 §15.1).
     */
    sealed interface Effect : SideEffect

    override fun reduce(
        state: LivePromptState,
        action: Action.LivePromptAction,
        ctx: ReducerContext,
    ): TransitionResult<LivePromptState, Effect>? = when (action) {

        Action.LivePromptAction.EnableLivePrompt ->
            if (!state.enabled) {
                TransitionResult(
                    nextState = state.copy(enabled = true),
                    sideEffects = emptyList(),
                )
            } else null

        Action.LivePromptAction.DisableLivePrompt ->
            if (state.enabled) {
                // Disabling also drops any pending chain — there's no
                // valid follow-up while the feature is off.
                TransitionResult(
                    nextState = state.copy(enabled = false, pendingChain = false),
                    sideEffects = emptyList(),
                )
            } else null

        is Action.LivePromptAction.ChainNext ->
            // ChainNext is cascaded by PipelineModule after PipelineDone
            // when the chain bit is set. Reducer consumes the bit — the
            // next pipeline-trigger originates at the resolver layer
            // (which already has the audio-file reference).
            if (state.pendingChain) {
                TransitionResult(
                    nextState = state.copy(pendingChain = false),
                    sideEffects = emptyList(),
                )
            } else null
    }

    override fun runEffect(effect: Effect, services: ModuleServices) {
        // No effects — see [Effect] KDoc. Empty sealed interface.
    }
}
