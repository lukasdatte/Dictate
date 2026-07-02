// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import kotlin.reflect.KClass

/**
 * Owns the [InterruptionState] axis — pauses an `Active` recording when
 * an external interruption arrives (another app took audio focus, or
 * the headset disconnected) and records the reason so the info bar can
 * surface it.
 *
 * **The single interruption authority (F-007 consolidation,
 * 2026-07-02).** Before this implementation two things were wrong:
 *
 *  1. This module was a Phase-2 stub whose KDoc falsely claimed the
 *     action leaves were "dispatched by the IME-side listeners today"
 *     — no producer existed anywhere (F-036).
 *  2. The de-facto interruption reactor was the service-side
 *     audio-focus listener: it classified *every* non-GAIN focus
 *     change (including duck-only notification dings) as focus-lost,
 *     and [AudioModule]'s observer paused the recording on the
 *     granted-flag edge (F-007) — the wrong channel with the wrong
 *     classification.
 *
 * Now `DictatePipelineService`'s focus listener routes through
 * `AudioFocusChangeClassifier`, which dispatches
 * [Action.InterruptionAction.AudioFocusInterrupted] for interrupting
 * losses only (hard LOSS + LOSS_TRANSIENT; duck-only losses are
 * ignored), and AudioModule no longer pauses on the granted edge —
 * this module's reducer + observer are the only recording-pause
 * authority for external interruptions.
 *
 * **Producers live FGS-side** (`DictatePipelineService`, not the IME):
 * recording survives IME teardown, so interruption detection must too
 * — the same ownership reasoning as the recording hardware.
 *
 *  - Audio focus: `buildAudioFocusGate`'s listener →
 *    `AudioFocusChangeClassifier.actionsFor(focusChange)`.
 *  - Headset: `AudioDeviceCallback.onAudioDevicesRemoved` →
 *    `HeadsetDeviceClassifier.isExternalMicInput(type, isSource)` →
 *    [Action.InterruptionAction.HeadsetDisconnected].
 *
 * **Reducer cascade (Coupling-Matrix row `Interruption × Recording`,
 * F-036 Gap-1 fallback):**
 *
 *  - Interruption while recording `Active` ⇒ record
 *    [InterruptionState.lastInterruption] on the own axis; the
 *    [onCrossModuleStateChange] observer sees the event edge and
 *    cascades [Action.RecordingAction.PauseRecording] (ADR-0002
 *    Mode 2). The original Phase-2 sketch said `CancelRecording`; the
 *    Gap-1 fallback decided **pause** — audio captured so far stays
 *    valuable, and the paused state is already visible in the UI.
 *  - Interruption while `Idle` / `Preparing` / `Paused` / `Interrupted`
 *    ⇒ `null` (Rejected) — nothing to pause, nothing to record.
 *  - **No auto-resume.** Focus regain / device re-attach never resumes;
 *    the user resumes manually (same rationale as Spec 1 §15.3 for the
 *    old focus-loss pause).
 *
 * **Self-cascade lifecycle:** when the recording leaves `Paused`
 * (resume / stop / cancel) while an interruption event is recorded, the
 * observer cascades [Action.InterruptionAction.ClearInterruption] so
 * the recorded reason (and the info-bar item derived from it) lives
 * exactly as long as the interruption-caused pause.
 *
 * @see net.devemperor.dictate.state.InterruptionState
 * @see net.devemperor.dictate.state.Action.InterruptionAction
 * @see net.devemperor.dictate.core.AudioFocusChangeClassifier
 * @see net.devemperor.dictate.core.HeadsetDeviceClassifier
 * @see net.devemperor.dictate.state.infobar.InfoBarSelector
 * @see docs/research/2026-07-02 - recording-interruption-handling.md
 * @see docs/decisions/0002-state-cross-module-cascade.md §"Mode 2"
 */
object InterruptionModule : DictateModule<InterruptionState, Action.InterruptionAction, InterruptionModule.Effect> {

    override val id: ModuleId = ModuleId.Interruption
    override val actionClass: KClass<Action.InterruptionAction> = Action.InterruptionAction::class

    override fun read(global: DictateUiState): InterruptionState = global.interruption
    override fun write(global: DictateUiState, sub: InterruptionState): DictateUiState =
        global.copy(interruption = sub)

    override fun initialState(): InterruptionState = InterruptionState()

    /**
     * No effects — the pause is a Mode-2 action-cascade (see module
     * KDoc), the state is in-RAM only, and the producers' lifecycle
     * (listener registration) is owned by the service's
     * `onCreate`/`onDestroy`, not by this module. Empty sealed
     * interface keeps the [DictateModule] type parameters honest.
     */
    sealed interface Effect : SideEffect

    override fun reduce(
        state: InterruptionState,
        action: Action.InterruptionAction,
        ctx: ReducerContext,
    ): TransitionResult<InterruptionState, Effect>? = when (action) {
        Action.InterruptionAction.AudioFocusInterrupted ->
            recordIfActive(state, InterruptionReason.AUDIO_FOCUS_LOST, ctx)

        Action.InterruptionAction.HeadsetDisconnected ->
            recordIfActive(state, InterruptionReason.HEADSET_DISCONNECTED, ctx)

        Action.InterruptionAction.ClearInterruption ->
            if (state.lastInterruption != null) {
                TransitionResult(
                    nextState = InterruptionState(),
                    sideEffects = emptyList(),
                )
            } else {
                // Stale clear (nothing recorded) → Rejected, no re-emit.
                null
            }
    }

    /**
     * Shared arm for both interruption kinds: record the event iff a
     * recording is currently `Active` (Gap-1 fallback — `Idle`,
     * `Preparing`, `Paused`, `Interrupted` are all no-ops). The
     * observer turns the resulting event edge into the
     * `PauseRecording` cascade.
     */
    private fun recordIfActive(
        state: InterruptionState,
        reason: InterruptionReason,
        ctx: ReducerContext,
    ): TransitionResult<InterruptionState, Effect>? =
        if (ctx.global.recording is RecordingState.Active) {
            TransitionResult(
                nextState = state.copy(
                    lastInterruption = InterruptionEvent(
                        reason = reason,
                        occurredAt = ctx.now,
                    ),
                ),
                sideEffects = emptyList(),
            )
        } else {
            null
        }

    override fun runEffect(effect: Effect, services: ModuleServices) {
        // No effects — see [Effect] KDoc. Empty sealed interface.
    }

    /**
     * Cross-module observer (ADR-0002 Mode 2) — two arms:
     *
     *  1. **Pause cascade:** a freshly recorded interruption event
     *     (edge on the own axis) while the recording is still `Active`
     *     in the frozen snapshot ⇒ [Action.RecordingAction.PauseRecording].
     *     The reducer already gated on `Active`, so within the same
     *     dispatch pass `next.recording` is provably still `Active`;
     *     the extra check is defence-in-depth against future
     *     reducer-arm drift.
     *  2. **Self-clear:** the recording leaves `Paused` while an event
     *     is recorded ⇒ [Action.InterruptionAction.ClearInterruption]
     *     (self-cascade — allowed since the KG-RSB-2 fix). Keeps the
     *     "non-null implies interruption-paused" invariant on
     *     [InterruptionState.lastInterruption].
     */
    override fun onCrossModuleStateChange(
        prev: DictateUiState,
        next: DictateUiState,
    ): List<Action> {
        val cascade = mutableListOf<Action>()

        val prevEvent = prev.interruption.lastInterruption
        val nextEvent = next.interruption.lastInterruption

        // Arm 1 — Interruption × Recording: fresh event ⇒ pause.
        if (nextEvent != null && nextEvent != prevEvent &&
            next.recording is RecordingState.Active
        ) {
            cascade += Action.RecordingAction.PauseRecording
        }

        // Arm 2 — recording left Paused ⇒ the interruption is resolved.
        if (nextEvent != null &&
            prev.recording is RecordingState.Paused &&
            next.recording !is RecordingState.Paused
        ) {
            cascade += Action.InterruptionAction.ClearInterruption
        }

        return cascade
    }
}
