// Lives in the `modules/` sub-directory but in the parent package
// `net.devemperor.dictate.state` because `DictateModule` is a `sealed
// interface` (Kotlin restricts implementations to the same package).
package net.devemperor.dictate.state

import kotlinx.coroutines.launch
import net.devemperor.dictate.database.entity.SessionStatus
import kotlin.reflect.KClass

/**
 * Owns the [ReviewPanelState] axis (ADR-0013): the in-keyboard review panel that
 * shows an ambiguous post-processing turn's output + explanation and lets the
 * user refine it by dictation, insert it, or discard it.
 *
 * **SRP:** a dedicated axis, NOT a dressed-up pending part — so it never
 * entangles with the pending-parts flush ordering. The pipeline FSM has already
 * gone Idle via `PipelineDone(heldForReview=true)` before the panel opens.
 *
 * **Insert vs Discard (DRY):** both route the session through the SAME
 * `sessionRepo.markInserted` "user acknowledged" channel that
 * `PendingSessionsModule` dismissal uses, so recovery's `findPendingInsertion`
 * never re-surfaces an acknowledged session. Insert additionally commits the
 * text into the host imperatively in the IME (side-channel, like
 * `PendingSessionsAction.AcceptAndInsert`) — the reducer cannot reach the
 * `InputConnection`.
 *
 * **Teardown safety net:** if the IME view goes away while the panel is open,
 * [onCrossModuleStateChange] cascades [Action.ReviewPanelAction.ConvertToPendingAndClose]
 * so the held text becomes a pending part (no data loss) instead of vanishing.
 *
 * @property clock injected time source (ADR-0013 nachschärfung b) — deterministic
 *   in tests; used only as the fallback `createdAt` when the DB row lookup misses.
 *
 * @see net.devemperor.dictate.state.ReviewPanelState
 * @see net.devemperor.dictate.state.Action.ReviewPanelAction
 * @see docs/decisions/0013-review-panel-and-ambiguity-modes.md
 */
class ReviewPanelModule(
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DictateModule<ReviewPanelState, Action.ReviewPanelAction, ReviewPanelModule.Effect> {

    override val id: ModuleId = ModuleId.ReviewPanel
    override val actionClass: KClass<Action.ReviewPanelAction> = Action.ReviewPanelAction::class

    override fun read(global: DictateUiState): ReviewPanelState = global.reviewPanel
    override fun write(global: DictateUiState, sub: ReviewPanelState): DictateUiState =
        global.copy(reviewPanel = sub)

    override fun initialState(): ReviewPanelState = ReviewPanelState()

    sealed interface Effect : SideEffect {
        /** Mark the session "user acknowledged" (shared markInserted channel). */
        data class MarkAcknowledged(val sessionId: String, val at: Long) : Effect

        /** Convert the held output into a pending part on IME teardown. */
        data class SurfacePendingPart(val sessionId: String, val output: String) : Effect
    }

    override fun reduce(
        state: ReviewPanelState,
        action: Action.ReviewPanelAction,
        ctx: ReducerContext,
    ): TransitionResult<ReviewPanelState, Effect>? = when (action) {

        is Action.ReviewPanelAction.Show ->
            TransitionResult(
                nextState = ReviewPanelState(
                    open = true,
                    sessionId = action.sessionId,
                    output = action.output,
                    message = action.message,
                    refining = false,
                ),
                sideEffects = emptyList(),
            )

        is Action.ReviewPanelAction.Update ->
            if (state.open) {
                TransitionResult(
                    state.copy(output = action.output, message = action.message, refining = false),
                    emptyList(),
                )
            } else null

        Action.ReviewPanelAction.MarkRefining ->
            if (state.open && !state.refining) {
                TransitionResult(state.copy(refining = true), emptyList())
            } else null

        Action.ReviewPanelAction.CancelRefinement ->
            if (state.open && state.refining) {
                // Return to the prior output (Update never fired on cancel).
                TransitionResult(state.copy(refining = false), emptyList())
            } else null

        Action.ReviewPanelAction.Insert ->
            if (state.open && state.sessionId != null) {
                TransitionResult(
                    ReviewPanelState(),
                    listOf(Effect.MarkAcknowledged(state.sessionId, ctx.now)),
                )
            } else null

        Action.ReviewPanelAction.Discard ->
            if (state.open && state.sessionId != null) {
                TransitionResult(
                    ReviewPanelState(),
                    listOf(Effect.MarkAcknowledged(state.sessionId, ctx.now)),
                )
            } else null

        Action.ReviewPanelAction.ConvertToPendingAndClose ->
            if (state.open && state.sessionId != null) {
                TransitionResult(
                    ReviewPanelState(),
                    listOf(Effect.SurfacePendingPart(state.sessionId, state.output)),
                )
            } else null
    }

    override fun runEffect(effect: Effect, services: ModuleServices) {
        when (effect) {
            is Effect.MarkAcknowledged ->
                services.scope.launch { services.sessionRepo.markInserted(effect.sessionId, effect.at) }

            is Effect.SurfacePendingPart ->
                // Mirror PipelineModule.AddPendingInsertSession: created_at from
                // the DB row (recording order), clock() only as fallback.
                services.scope.launch {
                    val createdAt = services.sessionRepo.findCreatedAt(effect.sessionId) ?: clock()
                    services.emitAction(
                        Action.PendingSessionsAction.AddOne(
                            PendingSession(
                                sessionId = effect.sessionId,
                                status = SessionStatus.COMPLETED,
                                transcribedText = effect.output,
                                createdAt = createdAt,
                            ),
                        ),
                    )
                }
        }
    }

    /**
     * Teardown safety net: IME view disappeared with the panel open → convert
     * the held text to a pending part and close (ADR-0013 §3.4).
     */
    override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
        if (next.reviewPanel.open && prev.imeViewVisible && !next.imeViewVisible) {
            listOf(Action.ReviewPanelAction.ConvertToPendingAndClose)
        } else {
            emptyList()
        }
}
