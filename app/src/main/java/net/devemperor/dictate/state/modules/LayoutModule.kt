// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import android.util.Log
import kotlin.reflect.KClass
import net.devemperor.dictate.core.ContentArea

/**
 * Owns the [LayoutState] axis — `contentArea` (MAIN_BUTTONS / QWERTZ /
 * EMOJI_PICKER) plus three Pref-mirrored booleans (`singleRowMode`,
 * `smallMode`, `animationsEnabled`).
 *
 * **Migration from the legacy `KeyboardStateManager` (Issue 1.1.5 / R.5):**
 *
 * `contentArea` previously lived as a top-level field on
 * `KeyboardStateManager`. Folding it into [LayoutState] enables the
 * **atomic `setSmallMode` contract** (see below) — both fields are now
 * on the same sub-state axis, so a single `state.copy(smallMode = true,
 * contentArea = MAIN_BUTTONS)` is a legal Mode-1 reducer write.
 *
 * **Atomic `setSmallMode` contract (Spec 2 §4.1 / KSM-bug fix):**
 *
 * When `smallMode` is set to `true`, the reducer **also** clamps
 * `contentArea` back to [ContentArea.MAIN_BUTTONS] in the **same**
 * `state.copy(...)` call. The legacy code performed these as two
 * separate mutations on the manager, which left the view in a brief
 * inconsistent state (small + QWERTZ) every time the user toggled into
 * small mode while a sub-keyboard was open. The atomic write
 * structurally rules out that race.
 *
 * **Not Mode 3** (cross-axis atomic write — forbidden per ADR-0002): both
 * fields live in [LayoutState]; the reducer touches its own axis only.
 *
 * **No cross-module observer:** Spec 1 §15.1 explicitly lists LayoutModule
 * as observer-free (column "Cross-Module-Observer?" = "nein"). Layout
 * changes are observed by the rendering side (KeyboardLayoutManager via
 * `state.collect`), not by other modules.
 *
 * **No effects.** All three booleans are Pref-mirrored via
 * `PipelinePrefMirror` (C7) — the mirror handles the SharedPreferences
 * write. LayoutModule's reducer is purely state-only.
 *
 * @see net.devemperor.dictate.state.LayoutState
 * @see net.devemperor.dictate.state.Action.LayoutAction
 * @see net.devemperor.dictate.core.ContentArea
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §15.1
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.md §4.1
 */
object LayoutModule : DictateModule<LayoutState, Action.LayoutAction, LayoutModule.Effect> {

    override val id: ModuleId = ModuleId.Layout
    override val actionClass: KClass<Action.LayoutAction> = Action.LayoutAction::class

    override fun read(global: DictateUiState): LayoutState = global.layout
    override fun write(global: DictateUiState, sub: LayoutState): DictateUiState =
        global.copy(layout = sub)

    override fun initialState(): LayoutState = LayoutState()

    /**
     * No module-local effects — all three Pref-mirrored booleans are
     * written back to SharedPreferences by `PipelinePrefMirror` (C7),
     * the canonical Pref↔state mirror in Phase 1.
     */
    sealed interface Effect : SideEffect

    override fun reduce(
        state: LayoutState,
        action: Action.LayoutAction,
        ctx: ReducerContext,
    ): TransitionResult<LayoutState, Effect>? = when (action) {

        Action.LayoutAction.ToggleSingleRowMode -> TransitionResult(
            nextState = state.copy(singleRowMode = !state.singleRowMode),
            sideEffects = emptyList(),
        )

        Action.LayoutAction.ToggleSmallMode -> {
            // Atomic contract: enabling small-mode also clamps the
            // content area back to MAIN_BUTTONS so we don't end up in
            // "small + QWERTZ" (the KSM-bug). Disabling leaves
            // contentArea alone — the user may want to keep their
            // sub-keyboard open after un-shrinking.
            val nextSmall = !state.smallMode
            TransitionResult(
                nextState = if (nextSmall) {
                    state.copy(smallMode = true, contentArea = ContentArea.MAIN_BUTTONS)
                } else {
                    state.copy(smallMode = false)
                },
                sideEffects = emptyList(),
            )
        }

        is Action.LayoutAction.SetSmallMode -> {
            // Direct setter for cross-module cascade targets (e.g.
            // ViewModeModule may want to force small on a future T-x).
            // Same atomic clamp as the toggle path.
            if (action.enabled == state.smallMode) {
                null
            } else if (action.enabled) {
                TransitionResult(
                    nextState = state.copy(smallMode = true, contentArea = ContentArea.MAIN_BUTTONS),
                    sideEffects = emptyList(),
                )
            } else {
                TransitionResult(
                    nextState = state.copy(smallMode = false),
                    sideEffects = emptyList(),
                )
            }
        }

        is Action.LayoutAction.SetContentArea ->
            // Setting the content-area while in small-mode is a no-op —
            // the structural rule is "small + non-MAIN_BUTTONS" is
            // forbidden (KSM-bug fix, Issue 1.1.5). The UI side won't
            // surface non-MAIN_BUTTONS targets in small-mode anyway.
            // F-20 (2026-05-15) — emit a `Log.w` diagnostic so a
            // resolver-author bug that forgets the small-mode gate
            // surfaces in logcat instead of being silently absorbed.
            if (state.smallMode && action.area != ContentArea.MAIN_BUTTONS) {
                Log.w(
                    TAG,
                    "SetContentArea(${action.area}) rejected in small-mode — " +
                        "resolver MUST gate on state.smallMode before dispatch " +
                        "(KSM-bug structural-rejection, Issue 1.1.5).",
                )
                null
            } else if (action.area != state.contentArea) {
                TransitionResult(
                    nextState = state.copy(contentArea = action.area),
                    sideEffects = emptyList(),
                )
            } else null
    }

    override fun runEffect(effect: Effect, services: ModuleServices) {
        // No effects — see [Effect] KDoc. Empty sealed interface.
    }

    private const val TAG: String = "LayoutModule"
}
