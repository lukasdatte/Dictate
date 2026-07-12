// Lives in the `modules/` sub-directory but in the parent package
// `net.devemperor.dictate.state` because `DictateModule` is a `sealed
// interface` (Kotlin restricts implementations to the same package).
package net.devemperor.dictate.state

import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * Owns the [HistoryPanelState] axis (ADR-0014): the in-keyboard history panel
 * that lists the full history (pending-first) and lets the user re-insert a
 * result without leaving the keyboard.
 *
 * **Minimal by design.** The only state is `open`; the paged list is an
 * IME-owned Paging stream, not part of the snapshot. An `object` (no injected
 * port) suffices — unlike `ReviewPanelModule`, teardown loses no data (the panel
 * is read-only), so there is nothing to convert to a pending part and no clock
 * is needed (the acknowledge effect uses `ctx.now`).
 *
 * **Insert (DRY).** The host commit is an IME side-channel (the reducer cannot
 * reach the `InputConnection`). For a pending row NOT tracked in the
 * `pendingSessions` axis, the IME dispatches [Action.HistoryPanelAction.AcknowledgeInsert],
 * which routes through the SAME `sessionRepo.markInserted` channel that the
 * review panel and pending-parts dismissal use, so recovery's
 * `findPendingInsertion` never re-surfaces it. Rows already in the axis go
 * through `PendingSessionsAction.AcceptAndInsert` instead.
 *
 * **Auto-close cascade.** [onCrossModuleStateChange] closes the panel when the
 * IME view goes away (nothing to preserve) or when a recording starts (external
 * triggers — widget / QS tile / shortcut — must not leave the panel stranded
 * over a live recording).
 *
 * @see net.devemperor.dictate.state.HistoryPanelState
 * @see net.devemperor.dictate.state.Action.HistoryPanelAction
 * @see docs/decisions/0014-in-keyboard-history-panel.md
 */
object HistoryPanelModule
    : DictateModule<HistoryPanelState, Action.HistoryPanelAction, HistoryPanelModule.Effect> {

    override val id: ModuleId = ModuleId.HistoryPanel
    override val actionClass: KClass<Action.HistoryPanelAction> = Action.HistoryPanelAction::class

    override fun read(global: DictateUiState): HistoryPanelState = global.historyPanel
    override fun write(global: DictateUiState, sub: HistoryPanelState): DictateUiState =
        global.copy(historyPanel = sub)

    override fun initialState(): HistoryPanelState = HistoryPanelState()

    sealed interface Effect : SideEffect {
        /** Mark the session "user acknowledged" (shared markInserted channel). */
        data class MarkAcknowledged(val sessionId: String, val at: Long) : Effect
    }

    override fun reduce(
        state: HistoryPanelState,
        action: Action.HistoryPanelAction,
        ctx: ReducerContext,
    ): TransitionResult<HistoryPanelState, Effect>? = when (action) {

        Action.HistoryPanelAction.Open ->
            if (!state.open) TransitionResult(HistoryPanelState(open = true), emptyList()) else null

        Action.HistoryPanelAction.Close ->
            if (state.open) TransitionResult(HistoryPanelState(), emptyList()) else null

        is Action.HistoryPanelAction.AcknowledgeInsert ->
            // State unchanged (open/close is orthogonal); acknowledge only.
            TransitionResult(state, listOf(Effect.MarkAcknowledged(action.sessionId, ctx.now)))
    }

    override fun runEffect(effect: Effect, services: ModuleServices) {
        when (effect) {
            is Effect.MarkAcknowledged ->
                services.scope.launch { services.sessionRepo.markInserted(effect.sessionId, effect.at) }
        }
    }

    /**
     * Auto-close the panel on IME-view teardown OR when a recording starts
     * (ADR-0014). No data is lost either way (the panel is a read-only list).
     */
    override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> {
        if (!next.historyPanel.open) return emptyList()
        val imeTornDown = prev.imeViewVisible && !next.imeViewVisible
        val recordingStarted = prev.recording is RecordingState.Idle && next.recording !is RecordingState.Idle
        return if (imeTornDown || recordingStarted) {
            listOf(Action.HistoryPanelAction.Close)
        } else {
            emptyList()
        }
    }
}
