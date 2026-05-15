// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import kotlin.reflect.KClass

/**
 * Owns the [ResendState] axis — `lastAudioExists` (audio file from the
 * last successful pipeline is on disk), `resendEnabled` (user pref
 * mirror for the Resend-button), and the short `resendCooldown` window
 * after a click.
 *
 * **Cross-module cascade (Coupling-Matrix §15.1.x):**
 *
 * - `Pipeline → Resend`: PipelineModule cascades
 *   [Action.ResendAction.MarkLastAudio]`(exists = true)` after a
 *   successful `PipelineDone`. The cascade lives in PipelineModule, not
 *   here — ResendModule only **owns** the resulting state mutation.
 * - `Resend → Pipeline`: clicking Resend (or long-press) dispatches
 *   [Action.PipelineAction.TriggerPipeline] / `StartReprocessStaging`
 *   from the UI resolver path. ResendModule itself emits no Pipeline
 *   cascades — the click resolver already has the audio-file ref.
 *
 * **No `runEffect` work** beyond persisting the `resendEnabled` pref
 * mirror. The cooldown timer is intentionally driven by an
 * orchestrator-emitted [Action.ResendAction.ResendCooldownExpired]
 * action (the Phase-1 placeholder relies on the UI side scheduling that
 * action via `Handler.postDelayed`; a dedicated cooldown timer
 * subsystem is a Phase-2 nicety). Effect handling is therefore minimal.
 *
 * **No `reduceFailure` override:** Effects here are idempotent pref
 * writes — if the SP write throws (extremely unlikely on Android), the
 * next dispatch picks up the lag.
 *
 * @see net.devemperor.dictate.state.ResendState
 * @see net.devemperor.dictate.state.Action.ResendAction
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §15.1
 */
object ResendModule : DictateModule<ResendState, Action.ResendAction, ResendModule.Effect> {

    override val id: ModuleId = ModuleId.Resend
    override val actionClass: KClass<Action.ResendAction> = Action.ResendAction::class

    override fun read(global: DictateUiState): ResendState = global.resend
    override fun write(global: DictateUiState, sub: ResendState): DictateUiState =
        global.copy(resend = sub)

    override fun initialState(): ResendState = ResendState()

    /**
     * No hardware-touching side-effects. The pref mirror lives in
     * [net.devemperor.dictate.preferences.Pref.ResendButton] and is
     * synced by `PipelinePrefMirror` (C7); ResendModule reads from
     * the mirrored state.
     */
    sealed interface Effect : SideEffect

    override fun reduce(
        state: ResendState,
        action: Action.ResendAction,
        ctx: ReducerContext,
    ): TransitionResult<ResendState, Effect>? = when (action) {

        // ResendLastAudio click — the actual pipeline trigger is emitted by
        // the UI resolver path (it carries the audio-file reference).
        // Reducer's job is only to arm the cooldown window so the button
        // doesn't double-fire.
        Action.ResendAction.ResendLastAudio ->
            if (!state.resendCooldown) {
                TransitionResult(
                    nextState = state.copy(resendCooldown = true),
                    sideEffects = emptyList(),
                )
            } else null  // already in cooldown — second click is silent no-op

        // Long-press → ReprocessStaging entry. Same cooldown arming.
        Action.ResendAction.ResendLastAudioLong ->
            if (!state.resendCooldown) {
                TransitionResult(
                    nextState = state.copy(resendCooldown = true),
                    sideEffects = emptyList(),
                )
            } else null

        // Cooldown timer expired (dispatched by the UI side via
        // `Handler.postDelayed` in Phase 1). Clear the cooldown bit.
        Action.ResendAction.ResendCooldownExpired ->
            if (state.resendCooldown) {
                TransitionResult(
                    nextState = state.copy(resendCooldown = false),
                    sideEffects = emptyList(),
                )
            } else null

        // Cross-module cascade target (from PipelineModule.onCrossModuleStateChange
        // after a successful PipelineDone). Idempotent.
        is Action.ResendAction.MarkLastAudio ->
            if (action.exists != state.lastAudioExists) {
                TransitionResult(
                    nextState = state.copy(lastAudioExists = action.exists),
                    sideEffects = emptyList(),
                )
            } else null
    }

    override fun runEffect(effect: Effect, services: ModuleServices) {
        // No effects — see [Effect] KDoc. Empty sealed interface.
    }
}
