// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import kotlin.reflect.KClass

/**
 * Owns the **floating-overlay axis** ([WidgetState]) and the
 * **IME-View-visibility axis** ([DictateUiState.imeViewVisible]) per
 * ADR-0008 (B3 / Plan §4 Block 3). Replaces the legacy
 * [ViewModeModule] once B3.3 — B3.5 finish migrating reducers,
 * resolvers, layout predicates, and the Java IME service.
 *
 * # Why two axes in one module
 *
 * Both axes are written by the same set of triggers — user widget-
 * toggle, user close-button, IME-view-show, IME-view-hide,
 * pipeline-end. Splitting them across two modules would require
 * cross-module cascades for every transition, re-introducing the
 * race-window the consolidation was designed to remove. The
 * `WidgetSubState` projection lets a single reducer mutate both
 * axes atomically.
 *
 * # Transition table (W1-W8 per plan §3)
 *
 * | ID | Trigger                                       | Pre               | Result                                    |
 * |----|-----------------------------------------------|-------------------|-------------------------------------------|
 * | W1 | `ToggleWidget`                                | `Hidden`          | `Visible(USER)`                           |
 * | W2 | `CloseWidget`                                 | `Visible`         | `Hidden` + suppressBit=true + (Active→Paused) |
 * | W3 | `OnImeViewHidden` + (rec/pipe active)         | `Hidden && !supp` | `Visible(PIPELINE)`                       |
 * | W4 | `OnImeViewShown`                              | `Visible(PIPELINE)` | `Hidden`                                |
 * | W5 | `OnImeViewShown`                              | `Visible(USER)`   | stays — sticky                            |
 * | W6 | `OnPipelineDone` (rec=Idle && pipe=Idle)      | `Visible(PIPELINE)` | `Hidden`                                |
 * | W7 | rec `Idle→Preparing`                          | suppressBit       | suppressBit=false (via RecordingModule observer) |
 * | W8 | rec `Paused→Active`                           | suppressBit       | suppressBit=false (via observer below)    |
 *
 * # Cross-module cascade emissions
 *
 * - **W2** — `CloseWidget` emits [Effect.DispatchCloseWidgetCascade]
 *   which fans into `OverlayAction.SuppressAutoOverlayUntilNextSession`
 *   (suppress-bit on) plus an optional `RecordingAction.PauseRecording`
 *   when the current recording is `Active`. The pipeline is
 *   deliberately NOT cancelled — it keeps running in the FGS and the
 *   result surfaces as a Pending-Insert info-bar (B4).
 * - **W6** observed via [onCrossModuleStateChange] — emits
 *   [Action.WidgetAction.OnPipelineDone] only when the state-diff
 *   shows the boundary from any non-Idle pipeline/recording state to
 *   simultaneous Idle.
 * - **W8** observed via [onCrossModuleStateChange] — emits
 *   [Action.OverlayAction.ResetSuppressBit] on `Paused → Active`.
 *   The complementary `Idle → Preparing` (W7) is emitted by
 *   `RecordingModule`'s own observer; both edges clear the bit so
 *   the next IME-hide can re-auto-show the PIPELINE widget.
 *
 * # Migration safety
 *
 * Both [ViewModeModule] and [WidgetModule] are registered in parallel
 * during the B3 rollout. The new axes ([DictateUiState.widget] /
 * [DictateUiState.imeViewVisible]) carry the same information as
 * `viewMode + overlay.userPrefersWidget`; consumers migrate one by
 * one in B3.3.
 *
 * @see net.devemperor.dictate.state.WidgetState
 * @see net.devemperor.dictate.state.WidgetOrigin
 * @see docs/decisions/0008-ui-surface-axes-widget-state-and-ime-view.md
 * @see docs/plans/2026-05-21 - dictate-widget-state-and-recovery/dictate-widget-state-and-recovery.md §3 (W1-W8)
 */
object WidgetModule :
    DictateModule<WidgetModule.WidgetSubState, Action.WidgetAction, WidgetModule.Effect> {

    override val id: ModuleId = ModuleId.Widget
    override val actionClass: KClass<Action.WidgetAction> = Action.WidgetAction::class

    /**
     * Two-axis projection of [DictateUiState] for the lens.
     *
     * Holding both axes together makes the reducer atomic: a single
     * Action.WidgetAction can flip [widget] and [imeViewVisible] in
     * one [DictateUiState.copy] write, no observable intermediate
     * state. The projection is **module-internal** — the rest of the
     * codebase reads / writes the two flat fields on [DictateUiState].
     */
    data class WidgetSubState(
        val widget: WidgetState,
        val imeViewVisible: Boolean,
    )

    override fun read(global: DictateUiState): WidgetSubState = WidgetSubState(
        widget = global.widget,
        imeViewVisible = global.imeViewVisible,
    )

    override fun write(global: DictateUiState, sub: WidgetSubState): DictateUiState =
        global.copy(
            widget = sub.widget,
            imeViewVisible = sub.imeViewVisible,
        )

    override fun initialState(): WidgetSubState = WidgetSubState(
        widget = WidgetState.Hidden,
        imeViewVisible = true,
    )

    /**
     * Effects emitted by [WidgetModule].
     *
     * **Why an effect for W2 and not direct cascade-observer:** the
     * user-close cascade carries a runtime decision (whether the
     * Active-recording arm needs `PauseRecording`) that depends on
     * the **pre-transition** state. `onCrossModuleStateChange` only
     * sees `(prev, next)` *after* the reducer ran — by which time
     * the action that triggered W2 has no remaining trace except
     * `widget=Hidden`. An explicit effect emitted from the reducer
     * arm captures the pre-state snapshot and dispatches at
     * `depth+1` (Spec 1 §4.3) with the right action shape.
     */
    sealed interface Effect : SideEffect {
        /**
         * Emitted by the `CloseWidget` reducer arm (W2). Re-dispatches
         * the two cascade actions in order: the suppress-bit setter
         * (always), then [Action.RecordingAction.PauseRecording] when
         * the snapshot saw `Recording.Active`. Pipeline is **not**
         * touched — it keeps running per the user-requirement
         * "Pipeline läuft im FGS fertig" (plan §3 W2).
         *
         * @property shouldPauseRecording snapshot of `recording.Active`
         *   captured at reducer-time; the effect handler dispatches
         *   [Action.RecordingAction.PauseRecording] iff true.
         */
        data class DispatchCloseWidgetCascade(
            val shouldPauseRecording: Boolean,
        ) : Effect
    }

    override fun reduce(
        state: WidgetSubState,
        action: Action.WidgetAction,
        ctx: ReducerContext,
    ): TransitionResult<WidgetSubState, Effect>? = when (action) {

        // ── W1: user clicks Widget-Toggle ───────────────────────────────
        // Only fires from Hidden. From Visible the user would close (W2),
        // not re-toggle — the legacy ToggleViewModeWidget action conflated
        // both directions, the new surface is a strict one-way trigger.
        Action.WidgetAction.ToggleWidget ->
            if (state.widget == WidgetState.Hidden) {
                TransitionResult(
                    nextState = state.copy(
                        widget = WidgetState.Visible(WidgetOrigin.USER),
                    ),
                )
            } else {
                // Already visible — the close-button (CloseWidget) is the
                // path. Returning null logs Rejected("reducer-null") which
                // is the correct semantic outcome.
                null
            }

        // ── W2: user clicks Close-Btn while widget visible ──────────────
        // widget = Hidden + suppressBit = true + (Active → Paused).
        // Pipeline keeps running in the FGS; its result will surface as a
        // Pending-Insert info-bar (B4) once it completes.
        Action.WidgetAction.CloseWidget ->
            if (state.widget is WidgetState.Visible) {
                val activeRecording = ctx.global.recording is RecordingState.Active
                TransitionResult(
                    nextState = state.copy(widget = WidgetState.Hidden),
                    sideEffects = listOf(
                        Effect.DispatchCloseWidgetCascade(
                            shouldPauseRecording = activeRecording,
                        ),
                    ),
                )
            } else {
                null
            }

        // ── W3: IME-View hidden ─────────────────────────────────────────
        // Always flips imeViewVisible. Auto-shows a PIPELINE widget iff:
        //   widget == Hidden
        //   AND !overlay.suppressAutoOverlayUntilNextSession
        //   AND (recording.isActiveOrPaused || pipeline !is Idle)
        //
        // The suppress-bit guard is what makes W2 stick: after a user
        // close-during-recording, the IME-tear-down would otherwise
        // immediately re-show the PIPELINE widget the user just dismissed.
        // Once the next session starts (RecordingModule's Idle→Preparing
        // cascade emits OverlayAction.ResetSuppressBit), the guard
        // releases.
        Action.WidgetAction.OnImeViewHidden -> {
            val shouldAutoShow = state.widget == WidgetState.Hidden &&
                !ctx.global.overlay.suppressAutoOverlayUntilNextSession &&
                (ctx.global.recording.isActiveOrPaused ||
                    ctx.global.pipeline !is PipelineUiState.Idle)
            val nextWidget = if (shouldAutoShow) {
                WidgetState.Visible(WidgetOrigin.PIPELINE)
            } else {
                state.widget
            }
            TransitionResult(
                nextState = state.copy(
                    widget = nextWidget,
                    imeViewVisible = false,
                ),
            )
        }

        // ── W4 / W5: IME-View shown ─────────────────────────────────────
        // Always flips imeViewVisible. The widget axis depends on
        // its current origin:
        //   - Visible(PIPELINE) → Hidden (W4: the IME is back, the
        //     auto-shown overlay is no longer needed)
        //   - Visible(USER) → stays (W5: sticky — user explicitly wants
        //     both surfaces visible side-by-side until they close one)
        //   - Hidden → stays Hidden (default; no auto-show on IME-show,
        //     the user didn't ask for the widget)
        Action.WidgetAction.OnImeViewShown -> {
            val nextWidget = when (val w = state.widget) {
                is WidgetState.Visible ->
                    if (w.origin == WidgetOrigin.PIPELINE) WidgetState.Hidden else w
                WidgetState.Hidden -> WidgetState.Hidden
            }
            TransitionResult(
                nextState = state.copy(
                    widget = nextWidget,
                    imeViewVisible = true,
                ),
            )
        }

        // ── W6: pipeline / recording both Idle ──────────────────────────
        // Cross-module cascade target — emitted by onCrossModuleStateChange
        // when (prev: any non-Idle) → (next: both Idle). Closes a
        // PIPELINE-origin widget; leaves USER-origin sticky (the user
        // still wants their widget).
        Action.WidgetAction.OnPipelineDone -> {
            val w = state.widget
            if (w is WidgetState.Visible && w.origin == WidgetOrigin.PIPELINE) {
                TransitionResult(nextState = state.copy(widget = WidgetState.Hidden))
            } else {
                null
            }
        }

        // ── W7 / W8: suppress-bit reset ─────────────────────────────────
        // Reserved no-op until the suppress-bit migrates from
        // OverlayState to WidgetSubState (B5 cleanup). The actual
        // mutation lives on `OverlayModule.reduce(ResetSuppressBit)`,
        // dispatched by RecordingModule's observer (W7) and by this
        // module's observer (W8 — see onCrossModuleStateChange).
        Action.WidgetAction.ResetSuppressBit -> null
    }

    /**
     * Cross-module observers — W6 (pipeline-done widget auto-close) and
     * W8 (suppress-bit reset on resume).
     *
     * **W6 trigger:** the boundary from any non-Idle pipeline / recording
     * state to simultaneous Idle. Emits
     * [Action.WidgetAction.OnPipelineDone] so the reducer's W6 arm runs
     * with the post-Idle ctx and the auto-close decision sees the right
     * `widget.origin`.
     *
     * **W8 trigger:** `Paused → Active` recording-FSM edge. Emits
     * [Action.OverlayAction.ResetSuppressBit] to mirror the W7 cascade
     * that `RecordingModule.onCrossModuleStateChange` already emits on
     * `Idle → Preparing`. Together W7 + W8 ensure the suppress-bit
     * releases at every meaningful "fresh recording activity" boundary.
     */
    override fun onCrossModuleStateChange(
        prev: DictateUiState,
        next: DictateUiState,
    ): List<Action> {
        val cascade = mutableListOf<Action>()

        // W6 — pipeline+recording quiesced ──────────────────────────────
        val prevActive = prev.pipeline !is PipelineUiState.Idle ||
            prev.recording.isActiveOrPaused ||
            prev.recording is RecordingState.Preparing
        val nextQuiesced = next.pipeline is PipelineUiState.Idle &&
            next.recording is RecordingState.Idle
        if (prevActive && nextQuiesced) {
            cascade += Action.WidgetAction.OnPipelineDone
        }

        // W8 — Paused → Active resume ───────────────────────────────────
        if (prev.recording is RecordingState.Paused &&
            next.recording is RecordingState.Active
        ) {
            cascade += Action.OverlayAction.ResetSuppressBit
        }

        return cascade
    }

    override fun runEffect(effect: Effect, services: ModuleServices) {
        when (effect) {
            is Effect.DispatchCloseWidgetCascade -> {
                // Suppress-bit first — sets the gate that blocks W3's
                // auto-show until the next recording starts. Order
                // matters: PauseRecording's reducer arm may indirectly
                // re-emit cross-module actions that read the suppress
                // bit (rare path, but the dispatch-order guarantee
                // keeps the invariant).
                services.emitAction(
                    Action.OverlayAction.SuppressAutoOverlayUntilNextSession
                )
                if (effect.shouldPauseRecording) {
                    services.emitAction(Action.RecordingAction.PauseRecording)
                }
            }
        }
    }
}
