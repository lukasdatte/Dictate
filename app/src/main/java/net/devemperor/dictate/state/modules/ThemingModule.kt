// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.put
import kotlin.reflect.KClass

/**
 * Owns the [ThemingState] axis — `theme`, `accentColor`,
 * `overlayCharacters`, `outputSpeed`, and `widgetOpacity`. All five are
 * Pref-mirrored via `PipelinePrefMirror` (C7); the SP mirror is the
 * **sole production update path** for this axis (the Settings Activity
 * writes SharedPreferences, the mirror pushes the change into the
 * store).
 *
 * The former dead setters (`SetTheme` / `SetAccentColor` /
 * `SetOverlayCharacters` / `SetOutputSpeed`) were deleted per F-037
 * (widget-transparency spec 2026-07-02) — they had no dispatch sites,
 * and dispatching one would have mutated state without an SP write,
 * silently reverting on the next mirror sync. The remaining
 * [Action.ThemingAction.SetWidgetOpacity] closes that trap by emitting
 * [Effect.PersistWidgetOpacity] alongside the state write.
 *
 * **No cross-module observer:** Spec 1 §15.1 lists this module as
 * observer-free. Theming changes are observed by the rendering side
 * (KeyboardLayoutManager via `state.collect`); other modules do not
 * react to theming.
 *
 * @see net.devemperor.dictate.state.ThemingState
 * @see net.devemperor.dictate.state.Action.ThemingAction
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §15.1
 * @see docs/research/2026-07-02 - overlay-widget-transparency.md §3.2
 */
object ThemingModule : DictateModule<ThemingState, Action.ThemingAction, ThemingModule.Effect> {

    override val id: ModuleId = ModuleId.Theming
    override val actionClass: KClass<Action.ThemingAction> = Action.ThemingAction::class

    override fun read(global: DictateUiState): ThemingState = global.theming
    override fun write(global: DictateUiState, sub: ThemingState): DictateUiState =
        global.copy(theming = sub)

    override fun initialState(): ThemingState = ThemingState()

    sealed interface Effect : SideEffect {
        /**
         * Belt-and-suspenders SP write for [Action.ThemingAction.SetWidgetOpacity]
         * — mirrors the `OverlayModule.Effect.PersistOverlayPosition`
         * pattern. Without it, a dispatched setter would be silently
         * reverted the next time the C7 mirror syncs the key (the exact
         * F-037 failure mode).
         */
        data class PersistWidgetOpacity(val opacityPercent: Int) : Effect
    }

    override fun reduce(
        state: ThemingState,
        action: Action.ThemingAction,
        ctx: ReducerContext,
    ): TransitionResult<ThemingState, Effect>? = when (action) {

        is Action.ThemingAction.SetWidgetOpacity ->
            if (action.opacityPercent != state.widgetOpacity) {
                TransitionResult(
                    nextState = state.copy(widgetOpacity = action.opacityPercent),
                    sideEffects = listOf(Effect.PersistWidgetOpacity(action.opacityPercent)),
                )
            } else null
    }

    override fun runEffect(effect: Effect, services: ModuleServices): Unit = when (effect) {
        is Effect.PersistWidgetOpacity -> {
            // SharedPreferences are the canonical persistence mirror;
            // the C7 PrefMirror hears this write and re-applies the
            // (value-equal, hence no-op) state copy.
            services.sharedPrefs.edit()
                .put(Pref.WidgetOpacity, effect.opacityPercent)
                .apply()
        }
    }
}
