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
 * | W2 | `CloseWidget`                                 | `Visible`         | `Hidden` + suppressBit=true + (Active→Paused iff WIDGET_BUTTON & !imeViewVisible) |
 * | W3 | `OnImeViewHidden` + (rec/pipe active)         | `Hidden`          | `Visible(PIPELINE)`                       |
 * | W4 | `OnImeViewShown`                              | `Visible(any)`    | **stays — sticky (both origins)**          |
 * | W5 | `OnImeViewShown`                              | `Visible(USER)`   | stays — sticky (subsumed by W4)            |
 * | W6 | `OnPipelineDone`                              | `Visible(any)`    | **stays — no auto-close**                  |
 * | W7 | rec `Idle→Preparing`                          | suppressBit       | suppressBit=false (via RecordingModule observer) |
 * | W8 | rec `Paused→Active`                           | suppressBit       | suppressBit=false (via observer below)    |
 *
 * # Sticky-widget lifecycle (2026-05-23)
 *
 * **Once the widget is shown — whether by user-toggle (W1) or by the
 * IME-View vanishing mid-pipeline (W3) — it stays visible until the
 * user explicitly closes it via [Action.WidgetAction.CloseWidget]
 * (W2).** Earlier semantics auto-closed a `Visible(PIPELINE)` widget
 * on `OnImeViewShown` (W4) and on pipeline-end (W6); both were
 * removed at the user's request — the "transient PIPELINE" lifecycle
 * surprised users by snapping the surface away the moment they
 * brought the keyboard back, even when they wanted to keep observing
 * the pipeline. The [WidgetOrigin] enum still carries the open-source
 * (USER vs PIPELINE) so other render paths can discriminate (e.g.
 * `ContentAreaController` only hides the IME for `Visible(USER)`),
 * but it no longer drives an auto-close decision.
 *
 * # Cross-module cascade emissions
 *
 * - **W2** — `CloseWidget` emits [Effect.DispatchCloseWidgetCascade]
 *   which fans into `OverlayAction.SuppressAutoOverlayUntilNextSession`
 *   (suppress-bit on) plus an optional `RecordingAction.PauseRecording`
 *   when the current recording is `Active` **and** no IME-View is
 *   visible to take it over (WIDGET_BUTTON close in HOVER — 2026-07-11
 *   close-handoff). A WIDGET-mode close (IME-View still on screen) keeps
 *   the recording running so the returning keyboard continues it. The
 *   pipeline is deliberately NOT cancelled — it keeps running in the
 *   FGS and the result surfaces as a Pending-Insert info-bar (B4).
 * - **W8** observed via [onCrossModuleStateChange] — emits
 *   [Action.OverlayAction.ResetSuppressBit] on `Paused → Active`.
 *   The complementary `Idle → Preparing` (W7) is emitted by
 *   `RecordingModule`'s own observer; both edges clear the bit so
 *   the next IME-hide can re-auto-show the PIPELINE widget.
 * - **W6 is no longer emitted.** The reducer arm + observer for
 *   `OnPipelineDone` are retained as no-ops so callers and existing
 *   tests still compile; new code should not dispatch this action.
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

        // ── W2: widget closed ───────────────────────────────────────────
        // widget = Hidden + suppressBit = true. Pause is gated on the
        // close source (2026-05-22 user-req) AND on whether a keyboard is
        // there to take the recording over (2026-07-11 close-handoff):
        //  - KEYBOARD_TOGGLE (edit-bar btn) → recording keeps running;
        //    the IME-View stays on screen so the user can keep dictating.
        //  - WIDGET_BUTTON (overlay X) → pause ONLY when no IME-View is
        //    visible to take over (HOVER: `imeViewVisible == false`, the
        //    user is in another app). In WIDGET mode the IME-View is still
        //    on screen (only its content collapsed to a strip while the
        //    overlay was open — see `ContentAreaController`), so closing
        //    the overlay pops the keyboard back and the recording must
        //    keep running Active so the keyboard shows the live recording
        //    controls instead of a frozen Paused timer.
        // Pipeline keeps running in the FGS regardless; its result will
        // surface as a Pending-Insert info-bar (B4) once it completes.
        is Action.WidgetAction.CloseWidget ->
            if (state.widget is WidgetState.Visible) {
                val activeRecording = ctx.global.recording is RecordingState.Active
                val pause = action.source == WidgetCloseSource.WIDGET_BUTTON &&
                    activeRecording &&
                    !state.imeViewVisible
                TransitionResult(
                    nextState = state.copy(widget = WidgetState.Hidden),
                    sideEffects = listOf(
                        Effect.DispatchCloseWidgetCascade(
                            shouldPauseRecording = pause,
                        ),
                    ),
                )
            } else {
                null
            }

        // ── W3: IME-View hidden ─────────────────────────────────────────
        // Always flips imeViewVisible. Auto-shows a PIPELINE widget iff:
        //   widget == Hidden
        //   AND (recording.isActiveOrPaused || pipeline !is Idle)
        //
        // 2026-05-23 sticky-widget refactor: the suppress-bit check
        // (`!overlay.suppressAutoOverlayUntilNextSession`) is gone.
        // Pre-refactor it existed to stop an IME-tear-down from
        // immediately re-showing a widget the user had just dismissed
        // — but with sticky-widget the user expects the inverse: "auto-
        // open whenever the keyboard goes away, only manual close ever
        // closes it". The bit still gets written by W2 (no caller
        // changes needed) but no longer gates this arm.
        Action.WidgetAction.OnImeViewHidden -> {
            val shouldAutoShow = state.widget == WidgetState.Hidden &&
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
        // Always flips imeViewVisible. The widget axis is left untouched
        // for every origin (2026-05-23 sticky-widget refactor): once
        // the widget is visible the user owns the close decision, so
        // bringing the keyboard back must not snap the widget away.
        // The PIPELINE → Hidden auto-release that lived here previously
        // is gone; W5 (sticky USER) is the only remaining semantic.
        Action.WidgetAction.OnImeViewShown -> TransitionResult(
            nextState = state.copy(imeViewVisible = true),
        )

        // ── W6: deprecated no-op (sticky-widget refactor 2026-05-23) ────
        // Pipeline-done used to auto-close a Visible(PIPELINE) widget
        // here. The reducer arm is retained so callers and tests still
        // compile; new code should not dispatch this action. The cross-
        // module observer no longer emits it either.
        Action.WidgetAction.OnPipelineDone -> null

        // ── W7 / W8: suppress-bit reset ─────────────────────────────────
        // Reserved no-op until the suppress-bit migrates from
        // OverlayState to WidgetSubState (B5 cleanup). The actual
        // mutation lives on `OverlayModule.reduce(ResetSuppressBit)`,
        // dispatched by RecordingModule's observer (W7) and by this
        // module's observer (W8 — see onCrossModuleStateChange).
        Action.WidgetAction.ResetSuppressBit -> null
    }

    /**
     * Cross-module observers — W8 (suppress-bit reset on resume) and
     * the viewMode-sync bridge for the direct CloseWidget path.
     *
     * **W6 trigger (removed 2026-05-23):** pipeline-done used to auto-
     * close a `Visible(PIPELINE)` widget via
     * [Action.WidgetAction.OnPipelineDone]. The user wants the widget
     * sticky once it's shown — see the module KDoc §"Sticky-widget
     * lifecycle". The emission is gone; the reducer arm survives as a
     * no-op for compile-compatibility.
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

        // W8 — Paused → Active resume ───────────────────────────────────
        if (prev.recording is RecordingState.Paused &&
            next.recording is RecordingState.Active
        ) {
            cascade += Action.OverlayAction.ResetSuppressBit
        }

        // 2026-05-22 — viewMode-sync for the direct CloseWidget path.
        // The overlay's X button dispatches CloseWidget(WIDGET_BUTTON)
        // straight into this module, flipping `widget` Visible → Hidden
        // without touching the legacy `viewMode` axis. Cascade
        // ToggleViewModeWidget so ViewModeModule follows WIDGET →
        // KEYBOARD. Guarded on `next.viewMode == WIDGET` so the
        // keyboard-toggle path (where the OverlayModule T2-bridge has
        // already driven viewMode to KEYBOARD before emitting
        // CloseWidget) does NOT re-toggle back to WIDGET.
        if (prev.widget is WidgetState.Visible &&
            next.widget == WidgetState.Hidden &&
            next.viewMode == ViewMode.WIDGET
        ) {
            cascade += Action.ViewModeAction.ToggleViewModeWidget
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
