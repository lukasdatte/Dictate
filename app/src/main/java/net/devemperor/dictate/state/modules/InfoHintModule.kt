// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import net.devemperor.dictate.BuildConfig
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.put
import kotlin.reflect.KClass

/**
 * Owns the [InfoHintState] axis — the transient pipeline-error hint +
 * the Update/Rate/Donate engagement hint that the state-derived info
 * bar renders (`InfoBarSelector` producers).
 *
 * Completes the ADR-0006 migration: the legacy imperative
 * info-bar controller (nine string-keyed `showInfo` cases, deleted
 * 2026-07-02) is replaced by this trigger axis. Error info now
 * *surfaces as state*; the selector derives the bar; force-expand
 * (`LayoutCatalog.forKeyboard`) and the prompts-mutex
 * (`PromptVisibilityController`) apply automatically because both key
 * on `InfoBarSelector.select(state)`.
 *
 * **Trigger seams (who dispatches into this module):**
 *
 *  - `DictateInputMethodService.onPipelineError` →
 *    [Action.InfoHintAction.PipelineErrorOccurred] (after
 *    [PipelineErrorKind.fromInfoKey] parsing — cancellation / unknown
 *    keys never reach the reducer).
 *  - `DictateInputMethodService.onStartInputView` →
 *    [Action.InfoHintAction.ShowEngagementHint] (the pref +
 *    usage-DB trigger conditions need Android reads a pure reducer
 *    cannot perform; the service evaluates, the state carries the
 *    result — the Gap-1 fallback of the consolidation research:
 *    mirror the legacy `showInfo("update"/"rate"/"donate")` sites).
 *  - `InfoBarRenderer` button clicks → the Confirm/Dismiss actions.
 *
 * **Cross-module cascades (this module's observer):** all in-RAM
 * hints are cleared (no pref writes) when
 *
 *  1. a new recording starts (`recording` leaves Idle) — mirrors the
 *     legacy `onRecordClicked` dismiss;
 *  2. a new pipeline run starts (`pipeline` leaves Idle) — mirrors
 *     the legacy send/reprocess-path dismisses;
 *  3. the IME view hides while recording + pipeline are idle —
 *     mirrors the legacy `onFinishInputView` "full cleanup" dismiss
 *     (states A/B of that branch — recording or pipeline still live —
 *     deliberately kept hints alive, hence the idle guard).
 *
 * **Effect surface:** the engagement dismiss/confirm arms persist the
 * matching `Pref.*` flags (the "natural source" that stops the
 * service-side trigger from re-firing). The pipeline-error hint is
 * in-RAM by definition — no persistence effect.
 *
 * @see net.devemperor.dictate.state.InfoHintState
 * @see net.devemperor.dictate.state.Action.InfoHintAction
 * @see net.devemperor.dictate.state.infobar.InfoBarSelector
 * @see docs/decisions/0006-ui-info-bar-state-derived-items.md
 */
object InfoHintModule : DictateModule<InfoHintState, Action.InfoHintAction, InfoHintModule.Effect> {

    override val id: ModuleId = ModuleId.InfoHint
    override val actionClass: KClass<Action.InfoHintAction> = Action.InfoHintAction::class

    override fun read(global: DictateUiState): InfoHintState = global.infoHints
    override fun write(global: DictateUiState, sub: InfoHintState): DictateUiState =
        global.copy(infoHints = sub)

    override fun initialState(): InfoHintState = InfoHintState()

    /**
     * Module-local side-effect surface — pref persistence per
     * engagement-hint resolution. Legacy semantics carried over
     * verbatim from the deleted legacy info-bar controller:
     *
     *  - Update "No" → [PersistSeenVersionCode] (Update "Yes" persists
     *    nothing — the hint re-fires next keyboard-open until "No").
     *  - Rate "Yes"/"No" → [PersistRatedFlag].
     *  - Donate "Yes"/"No" → [PersistDonatedFlags] (donated implies
     *    rated — the legacy path wrote both flags).
     */
    sealed interface Effect : SideEffect {
        /** Write `Pref.LastVersionCode = BuildConfig.VERSION_CODE`. */
        data object PersistSeenVersionCode : Effect

        /** Write `Pref.FlagHasRated = true`. */
        data object PersistRatedFlag : Effect

        /** Write `Pref.FlagHasDonated = true` + `Pref.FlagHasRated = true`. */
        data object PersistDonatedFlags : Effect
    }

    override fun reduce(
        state: InfoHintState,
        action: Action.InfoHintAction,
        ctx: ReducerContext,
    ): TransitionResult<InfoHintState, Effect>? = when (action) {

        is Action.InfoHintAction.PipelineErrorOccurred -> TransitionResult(
            // A newer error replaces an older one unconditionally — the
            // renderer shows one bar; the most recent failure is the
            // actionable one.
            nextState = state.copy(
                pipelineError = PipelineErrorHint(
                    kind = action.kind,
                    providerKey = action.providerKey,
                    occurredAt = ctx.now,
                ),
            ),
            sideEffects = emptyList(),
        )

        is Action.InfoHintAction.ConfirmPipelineError,
        Action.InfoHintAction.DismissPipelineError ->
            // Both buttons clear the hint; Confirm's Activity launch is
            // the IME-side side-channel (see Action KDoc). Stale click
            // (hint already gone) → null (Rejected, no re-emit).
            if (state.pipelineError != null) {
                TransitionResult(
                    nextState = state.copy(pipelineError = null),
                    sideEffects = emptyList(),
                )
            } else null

        is Action.InfoHintAction.ShowEngagementHint ->
            // Idempotent — re-dispatch of the already-shown hint is a
            // no-op; the trigger sites fire on every onStartInputView.
            if (state.engagementHint != action.hint) {
                TransitionResult(
                    nextState = state.copy(engagementHint = action.hint),
                    sideEffects = emptyList(),
                )
            } else null

        is Action.InfoHintAction.ConfirmEngagementHint ->
            if (state.engagementHint == action.hint) {
                TransitionResult(
                    nextState = state.copy(engagementHint = null),
                    sideEffects = when (action.hint) {
                        // Legacy parity: Update-"Yes" opened the settings
                        // WITHOUT persisting — only "No" writes the pref.
                        EngagementHint.UPDATE -> emptyList()
                        EngagementHint.RATE -> listOf(Effect.PersistRatedFlag)
                        EngagementHint.DONATE -> listOf(Effect.PersistDonatedFlags)
                    },
                )
            } else null

        is Action.InfoHintAction.DismissEngagementHint ->
            if (state.engagementHint == action.hint) {
                TransitionResult(
                    nextState = state.copy(engagementHint = null),
                    sideEffects = when (action.hint) {
                        EngagementHint.UPDATE -> listOf(Effect.PersistSeenVersionCode)
                        EngagementHint.RATE -> listOf(Effect.PersistRatedFlag)
                        EngagementHint.DONATE -> listOf(Effect.PersistDonatedFlags)
                    },
                )
            } else null

        Action.InfoHintAction.PipelineCancelled -> TransitionResult(
            // A newer cancellation replaces an older one — one notice,
            // the most recent event's timestamp (mirrors the
            // pipeline-error replace semantics above).
            nextState = state.copy(
                cancellation = CancellationHint(occurredAt = ctx.now),
            ),
            sideEffects = emptyList(),
        )

        Action.InfoHintAction.DismissCancellationHint ->
            // Stale click (notice already gone) → null (Rejected, no
            // re-emit) — same contract as DismissPipelineError.
            if (state.cancellation != null) {
                TransitionResult(
                    nextState = state.copy(cancellation = null),
                    sideEffects = emptyList(),
                )
            } else null

        Action.InfoHintAction.ClearTransientHints ->
            // In-RAM clear only — no pref writes: the hints may
            // legitimately re-fire later (that is exactly the legacy
            // `dismiss()` semantic these cascades replace).
            if (state != InfoHintState()) {
                TransitionResult(
                    nextState = InfoHintState(),
                    sideEffects = emptyList(),
                )
            } else null
    }

    override fun runEffect(effect: Effect, services: ModuleServices): Unit = when (effect) {
        Effect.PersistSeenVersionCode -> {
            services.sharedPrefs.edit()
                .put(Pref.LastVersionCode, BuildConfig.VERSION_CODE)
                .apply()
        }
        Effect.PersistRatedFlag -> {
            services.sharedPrefs.edit()
                .put(Pref.FlagHasRated, true)
                .apply()
        }
        Effect.PersistDonatedFlags -> {
            services.sharedPrefs.edit()
                .put(Pref.FlagHasDonated, true)
                .put(Pref.FlagHasRated, true)
                .apply()
        }
    }

    /**
     * Cross-module observer — the state-driven replacement for the
     * scattered legacy `infoBarController.dismiss()` call sites (see
     * the module KDoc for the site-by-site mapping).
     */
    override fun onCrossModuleStateChange(
        prev: DictateUiState,
        next: DictateUiState,
    ): List<Action> {
        // Nothing to clear — never cascade (avoids dispatch noise on
        // every unrelated transition).
        if (next.infoHints == InfoHintState()) return emptyList()

        val recordingStarted =
            prev.recording is RecordingState.Idle && next.recording !is RecordingState.Idle
        val pipelineStarted =
            prev.pipeline is PipelineUiState.Idle && next.pipeline !is PipelineUiState.Idle
        val imeHiddenWhileIdle =
            prev.imeViewVisible && !next.imeViewVisible &&
                next.recording is RecordingState.Idle &&
                next.pipeline is PipelineUiState.Idle

        return if (recordingStarted || pipelineStarted || imeHiddenWhileIdle) {
            listOf(Action.InfoHintAction.ClearTransientHints)
        } else {
            emptyList()
        }
    }
}
