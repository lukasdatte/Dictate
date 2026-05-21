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
 * [ViewModeModule] once B3.2 — B3.5 finish migrating reducers,
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
 * # B3.1 — skeleton only
 *
 * This commit introduces the module class, lens, and `initialState`
 * so the orchestrator can register it alongside [ViewModeModule]
 * without conflict. The reducer returns `null` for every action
 * (default Rejected — no state mutation). B3.2 fills in the W1-W8
 * transitions defined in the plan §3 transition table.
 *
 * # Migration safety
 *
 * Both [ViewModeModule] and [WidgetModule] are registered in parallel
 * during the B3 rollout. The new axes ([DictateUiState.widget] /
 * [DictateUiState.imeViewVisible]) ship with stable defaults
 * (`Hidden` / `true`) so consumers that don't yet read them see no
 * change. The legacy [DictateUiState.viewMode] keeps its existing
 * truth-table semantics. Once B3.3 — B3.5 retire the resolvers /
 * layout predicates that read `viewMode`, [ViewModeModule] is
 * removed and `viewMode` is dropped from the state class.
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
     * No effects in B3.1. The reducer is pure state-mutation and emits
     * no `SideEffect`s. B3.2 may introduce explicit cascade-trigger
     * effects (symmetric to [ViewModeModule.Effect.DispatchCloseOverlayCascade])
     * if a transition needs to fan an external action; for the W1-W8
     * core table no such cascade is required.
     *
     * Kept as a sealed marker so the module signature remains stable
     * across the B3.1 → B3.2 expansion.
     */
    sealed interface Effect : SideEffect

    override fun reduce(
        state: WidgetSubState,
        action: Action.WidgetAction,
        ctx: ReducerContext,
    ): TransitionResult<WidgetSubState, Effect>? {
        // B3.1 — skeleton: every action returns null (Rejected). B3.2
        // fills in W1-W8 transitions. Until then, all WidgetAction.*
        // dispatches no-op; the legacy ViewModeAction.* path continues
        // to drive the existing ViewMode axis.
        return null
    }

    override fun runEffect(effect: Effect, services: ModuleServices) {
        // B3.1 — no effects defined; the sealed Effect marker is empty.
        // B3.2 may add cascade-trigger effects (e.g. for a Trash-cascade
        // on user-CloseWidget); until then this is unreachable.
        //
        // The narrow Nothing-cast plays into Kotlin's exhaustiveness
        // check: Effect has no leaves yet so `effect as Nothing` proves
        // to the compiler that this branch is unreachable, and adding
        // a future leaf forces a real `when` body without an `else`.
        @Suppress("CAST_NEVER_SUCCEEDS")
        effect as Nothing
    }
}
