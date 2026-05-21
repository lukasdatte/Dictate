// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import android.util.Log
import kotlin.reflect.KClass
import net.devemperor.dictate.core.ContentArea
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.preferences.put

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
 * **Pref-persistence via owned Effects (2026-05-21 indirection-cleanup):**
 * The `ToggleSmallMode` and `ToggleSingleRowMode` reducer arms emit
 * [Effect.PersistSmallMode] / [Effect.PersistSingleRowMode] so the
 * dispatch path is the **single source of truth**: click → dispatch →
 * reducer → effect-write. The legacy "click writes SP, PipelinePrefMirror
 * reflects it back" round-trip is retired (7 stages → 3). `PipelinePrefMirror`
 * still mirrors *external* SP changes (e.g. settings activity) — the new
 * Effect-write is a no-op for the mirror (StateFlow distinct-emit absorbs
 * the re-applied value).
 *
 * @see net.devemperor.dictate.state.LayoutState
 * @see net.devemperor.dictate.state.Action.LayoutAction
 * @see net.devemperor.dictate.core.ContentArea
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §15.1
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.md §4.1
 * @see docs/plans/2026-05-21 - dictate-indirection-cleanup/dictate-indirection-cleanup.md A-1
 */
object LayoutModule : DictateModule<LayoutState, Action.LayoutAction, LayoutModule.Effect> {

    override val id: ModuleId = ModuleId.Layout
    override val actionClass: KClass<Action.LayoutAction> = Action.LayoutAction::class

    override fun read(global: DictateUiState): LayoutState = global.layout
    override fun write(global: DictateUiState, sub: LayoutState): DictateUiState =
        global.copy(layout = sub)

    override fun initialState(): LayoutState = LayoutState()

    /**
     * Module-local effects for Pref persistence (2026-05-21). The toggle
     * arms emit one of these to keep `SharedPreferences` in sync with the
     * in-memory `LayoutState`. `PipelinePrefMirror` still listens to SP
     * changes from external writers (settings activity) — but the click
     * path no longer round-trips through it.
     */
    sealed interface Effect : SideEffect {
        data class PersistSmallMode(val value: Boolean) : Effect
        data class PersistSingleRowMode(val value: Boolean) : Effect
    }

    override fun reduce(
        state: LayoutState,
        action: Action.LayoutAction,
        ctx: ReducerContext,
    ): TransitionResult<LayoutState, Effect>? = when (action) {

        Action.LayoutAction.ToggleSingleRowMode -> {
            val next = !state.singleRowMode
            TransitionResult(
                nextState = state.copy(singleRowMode = next),
                sideEffects = listOf(Effect.PersistSingleRowMode(next)),
            )
        }

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
                sideEffects = listOf(Effect.PersistSmallMode(nextSmall)),
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
                    sideEffects = listOf(Effect.PersistSmallMode(true)),
                )
            } else {
                TransitionResult(
                    nextState = state.copy(smallMode = false),
                    sideEffects = listOf(Effect.PersistSmallMode(false)),
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

    override fun runEffect(effect: Effect, services: ModuleServices): Unit = when (effect) {
        // Pref-persistence Effects (2026-05-21). `apply()` is async-disk
        // but the in-memory SP value is visible to the next reader on the
        // same thread. `PipelinePrefMirror.OnSharedPreferenceChangeListener`
        // fires synchronously on the main-thread → it dispatches an
        // `applyChange` whose `state.copy(...)` writes the same boolean we
        // just emitted; MutableStateFlow's distinct-emission contract
        // absorbs the no-op (no feedback-loop).
        is Effect.PersistSmallMode ->
            services.sharedPrefs.edit().put(Pref.SmallMode, effect.value).apply()

        is Effect.PersistSingleRowMode ->
            services.sharedPrefs.edit().put(Pref.SingleRowMode, effect.value).apply()
    }

    private const val TAG: String = "LayoutModule"
}
