// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import kotlin.reflect.KClass

/**
 * Owns the [ViewMode] enum-axis — the **Triangle-FSM**
 * (KEYBOARD / WIDGET / HOVER) defined by ADR-0005 + Spec 3 §7.1.
 *
 * **Reducer signature note (Phase-C C-5):** the module reduces on the
 * `ViewMode` **enum sub-state** directly (`state: ViewMode`), NOT on
 * `DictateUiState`. The lens [read] / [write] handles the cross-state
 * containment; the reducer is pure on `(ViewMode, Action, ctx) →
 * TransitionResult<ViewMode, _>?`. Cross-axis reads (`overlay.hasPermission`,
 * `pipeline`, `recording`) go through [ReducerContext.global].
 *
 * **Truth-table for [computeViewMode] (Spec 3 §7.1):**
 *
 * | imeViewVisible | userPrefersWidget | pipelineActive | result   |
 * |----------------|-------------------|----------------|----------|
 * | true           | true              | (any)          | WIDGET   |
 * | true           | false             | (any)          | KEYBOARD |
 * | false          | (any)             | true           | HOVER    |
 * | false          | (any)             | false          | KEYBOARD |
 *
 * The auto-paths feed off two facts:
 * - `imeViewVisible` — derived from the dispatching action (e.g.
 *   `OnImeViewShown` ⇒ true, `OnImeViewHidden` ⇒ false). For the
 *   `OnPipelineDone` cascade the IME visibility is **inferred from the
 *   current ViewMode** (`HOVER ⇒ false`, otherwise `true`) — the state
 *   has no separate `imeViewVisible` field to avoid drift.
 * - `pipelineActive` — `pipeline !is Idle || recording.isActiveOrPaused`.
 *
 * **Seven transitions T1–T7 (Spec 3 §7.3):**
 *
 * - T1 KEYBOARD→WIDGET — user toggles widget (Permission-gated).
 * - T2 WIDGET→KEYBOARD — user toggles widget back.
 * - T3 KEYBOARD→HOVER — IME hidden + pipelineActive (was KEYBOARD).
 * - T4 WIDGET→HOVER — IME hidden + pipelineActive (was WIDGET).
 * - T5 HOVER→KEYBOARD — IME shown, was-NOT-WIDGET (userPrefersWidget=false).
 * - T6 HOVER→WIDGET — IME shown + userPrefersWidget=true (persistence bit).
 * - T7 HOVER→KEYBOARD via Pipeline-Done cascade ("Geist-Widget"-bug
 *   structural protection): once pipeline ends, HOVER's auto-trigger
 *   condition (`pipelineActive`) is `false` → `computeViewMode` falls
 *   to KEYBOARD.
 *
 * **No effects** — ViewMode is a pure UI-mode axis; the
 * `KeyboardLayoutManager` reacts to `state.viewMode` changes via
 * [DictateUiStateStore.state.collect]. Effects belong to other axes.
 *
 * **No cross-module cascades emitted from this module.** Layout and
 * Overlay observe ViewMode transitions themselves
 * (`LayoutModule.onCrossModuleStateChange` and
 * `OverlayModule.onCrossModuleStateChange`) and emit their own
 * cascade-actions. This keeps ViewModeModule SRP-clean.
 *
 * @see net.devemperor.dictate.state.ViewMode
 * @see net.devemperor.dictate.state.Action.ViewModeAction
 * @see docs/decisions/0005-ui-triangle-fsm-keyboard-widget-hover.md
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md §7
 */
object ViewModeModule : DictateModule<ViewMode, Action.ViewModeAction, ViewModeModule.Effect> {

    override val id: ModuleId = ModuleId.ViewMode
    override val actionClass: KClass<Action.ViewModeAction> = Action.ViewModeAction::class

    override fun read(global: DictateUiState): ViewMode = global.viewMode
    override fun write(global: DictateUiState, sub: ViewMode): DictateUiState =
        global.copy(viewMode = sub)

    override fun initialState(): ViewMode = ViewMode.KEYBOARD

    /**
     * ViewModeModule emits **no** side-effects — the FSM mutation alone
     * is the entirety of its responsibility. The empty sealed interface
     * keeps the [DictateModule] contract type-parameterised correctly.
     */
    sealed interface Effect : SideEffect

    override fun reduce(
        state: ViewMode,
        action: Action.ViewModeAction,
        ctx: ReducerContext,
    ): TransitionResult<ViewMode, Effect>? = when (action) {

        // T3 / T4 / T7-equivalent (IME hidden boundary).
        Action.ViewModeAction.OnImeViewHidden -> {
            val next = computeViewMode(
                imeViewVisible = false,
                userPrefersWidget = ctx.global.overlay.userPrefersWidget,
                pipelineActive = isPipelineActive(ctx.global),
            )
            if (next != state) TransitionResult(nextState = next, sideEffects = emptyList()) else null
        }

        // T5 / T6 (IME shown boundary).
        Action.ViewModeAction.OnImeViewShown -> {
            val next = computeViewMode(
                imeViewVisible = true,
                userPrefersWidget = ctx.global.overlay.userPrefersWidget,
                pipelineActive = isPipelineActive(ctx.global),
            )
            if (next != state) TransitionResult(nextState = next, sideEffects = emptyList()) else null
        }

        // T1 (KEYBOARD→WIDGET) and T2 (WIDGET→KEYBOARD). User-toggle path.
        Action.ViewModeAction.ToggleViewModeWidget -> when (state) {
            ViewMode.KEYBOARD ->
                // T1 Permission-Gate (Spec 3 §5.4): without overlay
                // permission the toggle is a silent no-op; the
                // resolver/UI side triggers the onboarding flow via a
                // separate OverlayAction.
                if (!ctx.global.overlay.hasPermission) {
                    null
                } else {
                    TransitionResult(
                        nextState = ViewMode.WIDGET,
                        sideEffects = emptyList(),
                    )
                }
            ViewMode.WIDGET -> TransitionResult(
                nextState = ViewMode.KEYBOARD,
                sideEffects = emptyList(),
            )
            // HOVER doesn't toggle directly — user must reopen the IME
            // first (T5/T6 path), then toggle.
            ViewMode.HOVER -> null
        }

        // CloseOverlay — used by both the WIDGET-close button (acts like
        // ToggleViewModeWidget) and the HOVER-close button (drops back to
        // KEYBOARD). Recording / Pipeline cancellation is handled by
        // OverlayModule's onCrossModuleStateChange on the
        // HOVER → KEYBOARD boundary (Spec 3 §4.8 + §6.2).
        Action.ViewModeAction.CloseOverlay -> when (state) {
            ViewMode.WIDGET, ViewMode.HOVER -> TransitionResult(
                nextState = ViewMode.KEYBOARD,
                sideEffects = emptyList(),
            )
            ViewMode.KEYBOARD -> null
        }

        // Direct set — used by permission-loss cascade (OverlayModule
        // emits SetViewMode(KEYBOARD) when permission is revoked at
        // runtime). Idempotent.
        is Action.ViewModeAction.SetViewMode ->
            if (action.mode != state) {
                TransitionResult(nextState = action.mode, sideEffects = emptyList())
            } else null

        // T7 — Pipeline-Done cascade. The pipeline just settled (Idle).
        // Re-run computeViewMode with pipelineActive=false. IME-visibility
        // is inferred from current ViewMode (HOVER ⇒ hidden; otherwise
        // visible) — see Spec 3 §7.3 T7 explanation.
        Action.ViewModeAction.OnPipelineDone -> {
            val next = computeViewMode(
                imeViewVisible = state != ViewMode.HOVER,
                userPrefersWidget = ctx.global.overlay.userPrefersWidget,
                pipelineActive = false,
            )
            if (next != state) TransitionResult(nextState = next, sideEffects = emptyList()) else null
        }
    }

    override fun runEffect(effect: Effect, services: ModuleServices) {
        // No effects — see [Effect] KDoc. The when-block over a sealed
        // interface with no members is implicitly exhaustive; this body
        // is intentionally empty.
    }

    /**
     * Deterministic ViewMode-Truth-Table (Spec 3 §7.1, ADR-0005 §4).
     * Public for unit tests + the architecture-doc walkthroughs.
     */
    fun computeViewMode(
        imeViewVisible: Boolean,
        userPrefersWidget: Boolean,
        pipelineActive: Boolean,
    ): ViewMode = when {
        imeViewVisible && userPrefersWidget -> ViewMode.WIDGET
        imeViewVisible && !userPrefersWidget -> ViewMode.KEYBOARD
        !imeViewVisible && pipelineActive -> ViewMode.HOVER
        else -> ViewMode.KEYBOARD
    }

    /**
     * Pipeline-active predicate centralised here (Spec 3 §7.1).
     * "Active" means either the AI-pipeline is running OR a recording is
     * still in flight (Active / Paused). [RecordingState.Preparing] is
     * treated as not-yet-active by the recording predicate
     * ([isActiveOrPaused]) — Preparing is a sub-millisecond transient
     * and treating it as pipelineActive would briefly force HOVER on
     * every recording-start when the IME view is collapsed.
     */
    private fun isPipelineActive(global: DictateUiState): Boolean =
        global.pipeline !is PipelineUiState.Idle || global.recording.isActiveOrPaused
}
