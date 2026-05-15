package net.devemperor.dictate.state

import java.io.File

/**
 * Root of the state-mutation action hierarchy.
 *
 * Every state mutation in the Dictate IME goes through
 * `DictateOrchestrator.dispatch(action: Action)` — the **only** mutation
 * entry point (F-8 Single Dispatch). Each inner `sealed class` per module
 * groups the actions that module's reducer handles; the orchestrator
 * routes via `KClass<out Action>`-Lookup against the module registry.
 *
 * **Why one inner sealed class per module?**
 *
 * - **Type-safe routing.** Each [DictateModule] declares
 *   `actionClass: KClass<A>` and the orchestrator's
 *   `moduleByLeafClass: Map<KClass<out Action>, DictateModule<*, *, *>>`
 *   is built at init time from `KClass.sealedSubclasses`. Lookup is O(1).
 * - **Compile-time exhaustivity.** Reducer-`when` blocks over the inner
 *   sealed class (e.g. `when (action: Action.RecordingAction)`) are
 *   compile-error-on-missing-branch.
 * - **OCP.** Adding a new action variant = a new `data class`/`data object`
 *   in the inner sealed; other modules untouched.
 *
 * **What an Action MUST NOT carry:**
 *
 * - Methods or logic — actions are pure data containers.
 * - Hardware references (no `MediaRecorder` field) — only the data the
 *   reducer needs to compute the next state and side-effect plan.
 * - Cross-module mutations — an Action targets exactly one module.
 *
 * **The five sources of actions** (Spec 1 §4.0.1.2):
 *
 * 1. UI click — `slot.actionResolver(state, services) -> Action?` →
 *    `onAction?.invoke(it)`; `null` is a silent no-op.
 * 2. Android lifecycle hook — `onFinishInputView` →
 *    `dispatch(ViewModeAction.OnImeViewHidden)`.
 * 3. Cross-module cascade (Mode 2) — `onCrossModuleStateChange(prev, next)`
 *    returns `List<Action>`; orchestrator dispatches recursively at depth+1.
 * 4. Effect completion — `services.emitAction(action)` from inside
 *    `runEffect` (async via `scope.launch { dispatch(action) }`).
 * 5. Effect failure (automatic) — orchestrator wraps any `runEffect` throw
 *    as [EffectFailure].
 *
 * @see net.devemperor.dictate.state.DictateModule
 * @see net.devemperor.dictate.state.DictateUiState
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"Action sealed hierarchy"
 * @see docs/decisions/0002-state-cross-module-cascade.md §"EffectFailure routing"
 * @see docs/architecture/state-architecture/state-and-actions.md §4
 */
sealed class Action {

    // ════════════════════════════════════════════════════════════════
    // Failure channel (top-level — not module-scoped)
    // ════════════════════════════════════════════════════════════════

    /**
     * Failure-channel action emitted by the orchestrator when a
     * `module.runEffect(effect, services)` throws.
     *
     * **Why a top-level `data class` (not per module)?** All modules can
     * fail; making `EffectFailure` an inner of every module's sealed
     * hierarchy would force every reducer to handle it. Top-level
     * placement plus origin-routing keeps the failure pipe single-typed.
     *
     * **Routing:** The orchestrator routes an [EffectFailure] back to the
     * module identified by [originModuleId] (NOT by KClass — all
     * EffectFailures share the same Kotlin class). The target module's
     * [DictateModule.reduceFailure] hook decides whether to roll back
     * its sub-state. Default `reduceFailure` returns `null` →
     * `DispatchOutcome.Rejected("reducer-null")`, which is semantically
     * correct ("no failure path defined").
     *
     * **`effect` is a string, not a typed effect.** The orchestrator
     * captures `effect.toString()` because the effect type
     * `E : SideEffect` is module-local — there's no top-level Effect
     * union. Module `reduceFailure` implementations match either by
     * exact-string (for `object`-effects, simple-name) or by
     * `startsWith("EffectName(")` (for `data class`-effects, which
     * include their args in `toString()`). See [SideEffect] KDoc.
     *
     * @property originModuleId which module emitted the effect that threw.
     * @property effect `effect.toString()` of the offending effect.
     * @property reason `throwable.message ?: throwable.javaClass.simpleName`.
     */
    data class EffectFailure(
        val originModuleId: ModuleId,
        val effect: String,
        val reason: String,
    ) : Action()

    // ════════════════════════════════════════════════════════════════
    // Recording-axis actions (RecordingModule)
    // ════════════════════════════════════════════════════════════════

    /** Lifecycle actions for the [RecordingState] FSM. */
    sealed class RecordingAction : Action() {
        /**
         * Begin a new recording session. The `audioFile` is pre-allocated
         * by the caller (Pre-Dispatch-Allocator pattern, Spec 1 §4.11) so
         * the reducer stays pure (no `cacheDir`-IO from inside `reduce`).
         */
        data class StartRecording(val target: InsertionTarget, val audioFile: File) : RecordingAction()

        /**
         * Hardware callback — `MediaRecorder.prepare()` returned. Drives
         * the `Preparing → Active` transition. Carries the **actual**
         * allocated file (may differ from the requested one if the
         * hardware adapter substituted a fallback path).
         */
        data class MediaRecorderReady(val audioFile: File) : RecordingAction()

        data object PauseRecording : RecordingAction()
        data object ResumeRecording : RecordingAction()
        data object StopRecording : RecordingAction()
        data object CancelRecording : RecordingAction()

        /**
         * "Send" click — stop recording AND trigger the pipeline. The
         * `PipelineAction.Submit` is emitted by `RecordingModule`'s
         * cross-module cascade on `Active/Paused → Idle` transitions
         * where the trigger was this action.
         */
        data object StopRecordingAndSend : RecordingAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Pipeline-axis actions (PipelineModule)
    // ════════════════════════════════════════════════════════════════

    /** Lifecycle + progress actions for the [PipelineUiState] FSM. */
    sealed class PipelineAction : Action() {
        /** Initiate a pipeline run for the just-recorded audio. */
        data class TriggerPipeline(val sessionId: String, val audioFile: File) : PipelineAction()

        /** Pipeline runner reports start — sets `Preparing → Running`. */
        data class StartPipeline(
            val sessionId: String,
            val totalSteps: Int,
            val autoEnterActive: Boolean,
        ) : PipelineAction()

        data class StepStarted(val sessionId: String, val stepName: String) : PipelineAction()
        data class StepCompleted(val sessionId: String) : PipelineAction()
        data class StepFailed(val sessionId: String, val reason: String) : PipelineAction()

        /** Pipeline successfully produced [finalText]. */
        data class PipelineDone(val sessionId: String, val finalText: String) : PipelineAction()

        data class PipelineFailed(val sessionId: String, val reason: String) : PipelineAction()

        /** `sessionId == null` cancels the **currently-active** pipeline (UI-slot use). */
        data class CancelPipeline(val sessionId: String? = null) : PipelineAction()

        // ─── Reprocess-Staging sub-FSM ───
        data class StartReprocessStaging(val sessionId: String) : PipelineAction()
        data class UpdateReprocessQueue(val sessionId: String, val newQueue: List<Int>) : PipelineAction()
        data class UpdateReprocessLanguage(val sessionId: String, val code: String?) : PipelineAction()
        data class SendStaging(val sessionId: String) : PipelineAction()
        data class CancelReprocessStaging(val sessionId: String) : PipelineAction()

        // ─── Result handling (post-Done) ───
        data class ConfirmInsertion(val sessionId: String) : PipelineAction()
        data class DismissResult(val sessionId: String) : PipelineAction()

        /** DB-write failed (R.17 / Issue 2.1.21). */
        data class PersistenceError(val sessionId: String, val reason: String) : PipelineAction()

        /**
         * `JobExecutor.start` returned `false` — a parallel job is already
         * active. Reducer rolls Pipeline back to `Idle` (state-first race
         * mitigation, R.17).
         */
        data class RejectedJobAlreadyActive(val sessionId: String) : PipelineAction()

        /** Service-death recovery — tell the user to paste from clipboard. */
        data class NotifyResultNeedsManualPaste(val sessionId: String) : PipelineAction()

        /** User pasted — clear the recovery flag. */
        data object ClearManualPasteFlag : PipelineAction()
    }

    // ════════════════════════════════════════════════════════════════
    // ViewMode-axis actions (ViewModeModule — Triangle-FSM, ADR-0005)
    // ════════════════════════════════════════════════════════════════

    sealed class ViewModeAction : Action() {
        /** User toggled the widget preference (T1/T2 in Spec 3 §7.3). */
        data object ToggleViewModeWidget : ViewModeAction()

        data object OnImeViewShown : ViewModeAction()
        data object OnImeViewHidden : ViewModeAction()

        /** User clicked the overlay-close button. */
        data object CloseOverlay : ViewModeAction()

        /** Permission-loss + other cross-module cascades drive this directly. */
        data class SetViewMode(val mode: ViewMode) : ViewModeAction()

        /**
         * Cross-module cascade target — emitted by PipelineModule's observer
         * when the pipeline settles to [PipelineUiState.Idle] from any
         * non-Idle state. ViewModeModule re-runs `computeViewMode` with
         * `pipelineActive=false` and (in HOVER) falls back to KEYBOARD —
         * the T7 "Geist-Widget" structural protection (Spec 3 §7.3 T7).
         */
        data object OnPipelineDone : ViewModeAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Layout-axis actions (LayoutModule)
    // ════════════════════════════════════════════════════════════════

    sealed class LayoutAction : Action() {
        data object ToggleSingleRowMode : LayoutAction()
        data object ToggleSmallMode : LayoutAction()

        /** Cross-module cascade target — ViewModeModule sets small-mode on T2. */
        data class SetSmallMode(val enabled: Boolean) : LayoutAction()

        data class SetContentArea(val area: net.devemperor.dictate.core.ContentArea) : LayoutAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Audio-axis actions (AudioModule)
    // ════════════════════════════════════════════════════════════════

    sealed class AudioAction : Action() {
        data object ToggleAudioFocusPref : AudioAction()
        data class OnAudioFocusGrantChanged(val granted: Boolean) : AudioAction()
        data class OnBluetoothScoStateChanged(val phase: ScoPhase, val reason: String? = null) : AudioAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Resend-axis actions (ResendModule)
    // ════════════════════════════════════════════════════════════════

    sealed class ResendAction : Action() {
        data object ResendLastAudio : ResendAction()

        /** Long-press → ReprocessStaging entry. */
        data object ResendLastAudioLong : ResendAction()

        data object ResendCooldownExpired : ResendAction()

        /** Cross-module cascade target — emitted after PipelineDone. */
        data class MarkLastAudio(val exists: Boolean) : ResendAction()
    }

    // ════════════════════════════════════════════════════════════════
    // LivePrompt-axis actions (LivePromptModule)
    // ════════════════════════════════════════════════════════════════

    sealed class LivePromptAction : Action() {
        data object EnableLivePrompt : LivePromptAction()
        data object DisableLivePrompt : LivePromptAction()
        data class ChainNext(val text: String) : LivePromptAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Language-axis actions (LanguageModule)
    // ════════════════════════════════════════════════════════════════

    sealed class LanguageAction : Action() {
        /** Reprocess-Staging override; `null` clears the override. */
        data class SetOverride(val code: String?) : LanguageAction()
        data object RefreshFromPref : LanguageAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Overlay-axis actions (OverlayModule)
    // ════════════════════════════════════════════════════════════════

    sealed class OverlayAction : Action() {
        /**
         * Drag-end position update. Coordinates are normalised [0..1]
         * relative to the screen dimension for the given orientation.
         */
        data class UpdateOverlayPosition(
            val portrait: Boolean,
            val x: Float,
            val y: Float,
        ) : OverlayAction()

        // ─── Onboarding (Spec 3 GAP-2) ───
        data object MarkOverlayOnboardingShown : OverlayAction()
        data object DismissOverlayOnboarding : OverlayAction()

        /** User toggled the widget preference (T1/T2). */
        data class SetUserPrefersWidget(val prefers: Boolean) : OverlayAction()

        /**
         * Set after `CloseOverlay` cascade; blocks auto-reopen for the
         * current recording session. Cleared by [ResetSuppressBit]
         * on `Recording.Idle → Preparing` boundary.
         */
        data object SuppressAutoOverlayUntilNextSession : OverlayAction()

        /**
         * Idempotent reset of the suppress bit. Emitted by `RecordingModule`'s
         * cross-module observer on `Idle → Preparing` (session start).
         * `data object` (not `data class`) — singleton identity is optimal
         * for sealed-leaves routing.
         */
        data object ResetSuppressBit : OverlayAction()

        // ─── Permission axis (Issue 3.1.3) ───
        data class OnOverlayPermissionChanged(val granted: Boolean) : OverlayAction()
        data object RequestOverlayPermission : OverlayAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Feature-toggle actions (FeatureToggleModule)
    // ════════════════════════════════════════════════════════════════

    sealed class FeatureToggleAction : Action() {
        data object ToggleRewording : FeatureToggleAction()
        data object ToggleAutoFormatting : FeatureToggleAction()
        data object ToggleInstantOutput : FeatureToggleAction()
        data object ToggleAutoEnter : FeatureToggleAction()
        data object ToggleVibration : FeatureToggleAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Theming-axis actions (ThemingModule)
    // ════════════════════════════════════════════════════════════════

    /**
     * Theme, accent-colour, overlay-characters, output-speed setters.
     * All four mirror `Pref.Theme` / `Pref.AccentColor` /
     * `Pref.OverlayCharacters` / `Pref.OutputSpeed`; SP writes are
     * performed by `PipelinePrefMirror` (C7) on state changes.
     */
    sealed class ThemingAction : Action() {
        data class SetTheme(val theme: String) : ThemingAction()
        data class SetAccentColor(val color: Int) : ThemingAction()
        data class SetOverlayCharacters(val chars: String) : ThemingAction()
        data class SetOutputSpeed(val speed: Int) : ThemingAction()
    }

    // ════════════════════════════════════════════════════════════════
    // PendingSessions-axis actions (PendingSessionsModule)
    // ════════════════════════════════════════════════════════════════

    sealed class PendingSessionsAction : Action() {
        data class Refresh(val sessions: List<PendingSession>) : PendingSessionsAction()
        data class Dismiss(val sessionId: String) : PendingSessionsAction()
    }

    // ════════════════════════════════════════════════════════════════
    // KeyboardInput-axis actions (KeyboardInputModule — Unit state, Spec 1 §15.6)
    // ════════════════════════════════════════════════════════════════

    /**
     * Direct IME-input actions. Owned by `KeyboardInputModule`, which has
     * no sub-state axis (`Unit`) — these actions are pure effect-producers
     * that operate on the `InputConnection` and system clipboard.
     *
     * The module exists so every Dictate IME mutation flows through
     * `dispatch(action)` (F-8 invariant) — without it, Backspace/Enter/Space
     * clicks would have no module and be silently `DispatchOutcome.Unrouted`.
     */
    sealed class KeyboardInputAction : Action() {
        data object Backspace : KeyboardInputAction()
        data object EnterKey : KeyboardInputAction()
        data object SpaceKey : KeyboardInputAction()
        data class CopyToClipboard(val text: String) : KeyboardInputAction()
    }

    // ════════════════════════════════════════════════════════════════
    // Interruption-axis actions (InterruptionModule — Phase 2)
    // ════════════════════════════════════════════════════════════════

    sealed class InterruptionAction : Action() {
        data class PhoneCallStateChanged(val incoming: Boolean) : InterruptionAction()
        data class HeadsetPlugChanged(val plugged: Boolean) : InterruptionAction()
        data class ScreenStateChanged(val awake: Boolean) : InterruptionAction()
    }
}
