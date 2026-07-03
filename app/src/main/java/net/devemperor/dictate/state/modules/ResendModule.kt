// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * Owns the [ResendState] axis — `lastAudioExists` (audio file from the
 * last successful pipeline is on disk), `resendEnabled` (user pref
 * mirror for the Resend-button), the short `resendCooldown` window
 * after a click, and `lastResultNeedsManualPaste` (IME-service-death
 * recovery UI hint, F-1).
 *
 * **Cross-module cascade (Coupling-Matrix §15.1.x):**
 *
 * - `Pipeline → Resend`: PipelineModule cascades
 *   [Action.ResendAction.MarkLastAudio]`(exists = true)` after a
 *   successful `PipelineDone`. The cascade lives in PipelineModule, not
 *   here — ResendModule only **owns** the resulting state mutation.
 * - `Recovery → Resend` (B3, planned): B3's recovery path dispatches
 *   [Action.ResendAction.NotifyManualPasteNeeded] directly when a
 *   completed session's result couldn't be inserted via InputConnection
 *   (service-death window). The flag is cleared by
 *   [Action.ResendAction.ClearManualPasteFlag] when the user pastes
 *   (or dismisses). See `research/manual-paste-field-architecture.md`.
 * - `Resend → Pipeline`: clicking Resend (or long-press) dispatches
 *   [Action.PipelineAction.TriggerPipeline] / `StartReprocessStaging`
 *   from the UI resolver path. ResendModule itself emits no Pipeline
 *   cascades — the click resolver already has the audio-file ref.
 *
 * **Cooldown timer is module-owned (F-029 fix, 2026-07-03).** Both
 * arm-actions ([Action.ResendAction.ResendLastAudio] /
 * [Action.ResendAction.ResendLastAudioLong]) emit
 * [Effect.ScheduleCooldownExpiry], whose handler launches a
 * `services.scope`-scoped 500 ms timer that dispatches
 * [Action.ResendAction.ResendCooldownExpired] back through
 * [ModuleServices.emitAction]. Previously the *clear* half was
 * scheduled UI-side (`Handler.postDelayed` inside
 * `DictateInputMethodService.onResendClicked`) and only on the
 * short-press path — a long-press armed the cooldown but nothing ever
 * cleared it, latching the RESEND button disabled until service restart
 * (the `enabledResolver { !resendCooldown }` disables the view, disabled
 * views receive no clicks, so the sole UI-side scheduler could never run
 * again). Owning the timer in the module makes the arm→expiry round-trip
 * a single reducer→effect invariant that holds for *every* arming path,
 * present and future — no per-call-site scheduling to forget.
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
     * The pref mirror lives in
     * [net.devemperor.dictate.preferences.Pref.ResendButton] and is
     * synced by `PipelinePrefMirror` (C7); ResendModule reads from the
     * mirrored state — no pref-write effect needed here.
     */
    sealed interface Effect : SideEffect {

        /**
         * Schedule the cooldown-clear (F-029). Emitted whenever an arm
         * action flips `resendCooldown` false → true. The handler waits
         * [COOLDOWN_MS] on [ModuleServices.scope] and then emits
         * [Action.ResendAction.ResendCooldownExpired] — the reducer arm
         * for that action is idempotent (no-op when already cleared), so
         * a redundant expiry (e.g. from a re-armed cooldown) is harmless.
         */
        data object ScheduleCooldownExpiry : Effect
    }

    override fun reduce(
        state: ResendState,
        action: Action.ResendAction,
        ctx: ReducerContext,
    ): TransitionResult<ResendState, Effect>? = when (action) {

        // ResendLastAudio click — the actual pipeline trigger is emitted by
        // the UI resolver path (it carries the audio-file reference).
        // Reducer's job is only to arm the cooldown window so the button
        // doesn't double-fire, AND schedule the matching clear (F-029) so
        // the button re-enables after the window.
        Action.ResendAction.ResendLastAudio ->
            if (!state.resendCooldown) {
                TransitionResult(
                    nextState = state.copy(resendCooldown = true),
                    sideEffects = listOf(Effect.ScheduleCooldownExpiry),
                )
            } else null  // already in cooldown — second click is silent no-op

        // Long-press → ReprocessStaging entry. Same cooldown arming + clear
        // scheduling (F-029 — the long-press path previously armed the
        // cooldown but never scheduled the clear, latching the button).
        Action.ResendAction.ResendLastAudioLong ->
            if (!state.resendCooldown) {
                TransitionResult(
                    nextState = state.copy(resendCooldown = true),
                    sideEffects = listOf(Effect.ScheduleCooldownExpiry),
                )
            } else null

        // Cooldown timer expired (dispatched by the module's own
        // ScheduleCooldownExpiry effect, F-029). Clear the cooldown bit.
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

        // F-1 — IME-service-death recovery hint. Dispatched by B3's
        // recovery path when a completed session's result couldn't be
        // inserted via InputConnection (the result is on the clipboard;
        // the user must tap to paste). Idempotent — re-dispatch is a
        // no-op once the session is already in the set.
        //
        // B3-VAL-W1 F-14: add the sessionId to pendingPasteSessionIds.
        // The Boolean alias `lastResultNeedsManualPaste` mirrors
        // "set non-empty" so existing IME consumers keep working
        // (per-session UI consumer wiring lands in B5/B6).
        is Action.ResendAction.NotifyManualPasteNeeded ->
            if (action.sessionId !in state.pendingPasteSessionIds) {
                val updated = state.pendingPasteSessionIds + action.sessionId
                TransitionResult(
                    nextState = state.copy(
                        pendingPasteSessionIds = updated,
                        lastResultNeedsManualPaste = updated.isNotEmpty(),
                    ),
                    sideEffects = emptyList(),
                )
            } else null

        // F-1 — user pasted (or dismissed). Clears the whole set
        // (current single-Boolean UI semantic). Idempotent —
        // re-dispatch is a no-op once already cleared.
        Action.ResendAction.ClearManualPasteFlag ->
            if (state.pendingPasteSessionIds.isNotEmpty() || state.lastResultNeedsManualPaste) {
                TransitionResult(
                    nextState = state.copy(
                        pendingPasteSessionIds = emptySet(),
                        lastResultNeedsManualPaste = false,
                    ),
                    sideEffects = emptyList(),
                )
            } else null
    }

    override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
        // F-029 — module-owned cooldown timer. Launch on the FGS-scoped
        // coroutine context; on service teardown the scope is cancelled
        // and the pending `delay` is dropped (the cooldown bit is
        // transient UI state that resets to false on the next store
        // rebuild anyway). `emitAction` posts the clear back through the
        // orchestrator's main-thread dispatch (ADR-0001 — effects never
        // call `dispatch` directly).
        Effect.ScheduleCooldownExpiry -> {
            services.scope.launch {
                delay(COOLDOWN_MS)
                services.emitAction(Action.ResendAction.ResendCooldownExpired)
            }
            Unit
        }
    }

    /**
     * Cooldown window length in ms. Matches the legacy UI-side
     * `Handler.postDelayed(…, 500)` the module timer replaced (F-029).
     */
    const val COOLDOWN_MS: Long = 500L
}
