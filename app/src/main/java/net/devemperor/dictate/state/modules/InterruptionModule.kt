// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import kotlin.reflect.KClass

/**
 * **Phase-2 stub** — owns the [InterruptionState] axis (currently
 * `null` in Phase 1, populated in Phase 2 by call-state, headset-plug,
 * and screen-state listeners).
 *
 * **Why register the stub at all (Spec 1 §15.1, ADR-0001):**
 *
 * - The [Action.InterruptionAction] sealed leaves
 *   ([PhoneCallStateChanged], [HeadsetPlugChanged],
 *   [ScreenStateChanged]) exist in the action hierarchy because the
 *   IME-side listeners are wired today. Without a module owner, the
 *   orchestrator would emit `DispatchOutcome.Unrouted` for every
 *   inbound interruption signal, breaking the F-8 single-dispatch
 *   coverage invariant.
 * - Reserving the [ModuleId.Interruption] slot at C6 lets B3 / Phase-2
 *   land a real implementation without touching the registry surface
 *   (OCP).
 *
 * **Stub semantics:** reducer returns `null` (Rejected) for every
 * inbound action; no state mutation, no side-effects, no cascade.
 * Phase 2 will populate:
 *
 * - `PhoneCallStateChanged(incoming = true)` ⇒ cascade
 *   [Action.RecordingAction.CancelRecording] (Coupling-Matrix §15.1.x
 *   row `Interruption × Recording`).
 * - `HeadsetPlugChanged(plugged)` ⇒ update mic routing.
 * - `ScreenStateChanged(awake)` ⇒ optional recording pause on
 *   screen-off (config-driven).
 *
 * @see net.devemperor.dictate.state.InterruptionState
 * @see net.devemperor.dictate.state.Action.InterruptionAction
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §15.1
 */
object InterruptionModule : DictateModule<InterruptionState?, Action.InterruptionAction, InterruptionModule.Effect> {

    override val id: ModuleId = ModuleId.Interruption
    override val actionClass: KClass<Action.InterruptionAction> = Action.InterruptionAction::class

    override fun read(global: DictateUiState): InterruptionState? = global.interruption
    override fun write(global: DictateUiState, sub: InterruptionState?): DictateUiState =
        global.copy(interruption = sub)

    /**
     * `null` in Phase 1 — the axis is unmodelled until Phase-2 populates
     * the listener wiring. Reducer returns `null` for all actions, so
     * the state never leaves this initial value in Phase 1.
     */
    override fun initialState(): InterruptionState? = null

    /**
     * No Phase-1 effects. Phase 2 adds entries for the listener
     * (un)registration when the module-owner becomes a real
     * lifecycle owner of the call-state / headset-plug receivers.
     */
    sealed interface Effect : SideEffect

    /**
     * Phase-1 stub: every action is silently rejected
     * (`DispatchOutcome.Rejected("reducer-null")`). Phase 2 replaces
     * this body with the real reducer arms.
     */
    override fun reduce(
        state: InterruptionState?,
        action: Action.InterruptionAction,
        ctx: ReducerContext,
    ): TransitionResult<InterruptionState?, Effect>? = null

    override fun runEffect(effect: Effect, services: ModuleServices) {
        // No effects — see [Effect] KDoc. Empty sealed interface.
    }
}
