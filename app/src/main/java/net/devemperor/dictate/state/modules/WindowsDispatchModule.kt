// Lives in the `modules/` sub-directory but in the parent package
// `net.devemperor.dictate.state` because `DictateModule` is a `sealed
// interface` (Kotlin restricts implementations to the same package).
package net.devemperor.dictate.state

import kotlinx.coroutines.launch
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.shared.protocol.InsertionOutcomeWire
import kotlin.reflect.KClass

/**
 * Owns the [WindowsDispatchState] axis (ADR-0019): sessions whose final text is in flight to the
 * paired Windows companion. Terminal in the pipeline FSM (the run-queue has drained) but NOT yet
 * acknowledged — exactly one acknowledge happens once the HTTP call returns, or on IME teardown.
 *
 * **The acknowledge rule is state-dependent, never unconditional.** A success acknowledges via
 * [Effect.DismissPendingPart] when a pending part already exists (teardown cascade, or a re-sent
 * still-pending history row), else via [Effect.MarkAcknowledged]. **Never `AcceptAndInsert`** —
 * that action is intercepted by the IME side-channel and would ADDITIONALLY commit the text into
 * the Android host field, the exact double delivery this module prevents. Whether a part exists is
 * read from THIS axis ([InFlightDispatch.surfacedAsPending]), not from a cross-axis read of
 * `pendingSessions` — with a second producer (the headless sink) firing off the pipeline thread,
 * "has the cascade's AddOne been reduced yet?" is timing-dependent and not worth defending.
 *
 * **The in-flight state lives here, NOT in the pipeline FSM.** The FSM goes Idle the instant
 * `PipelineDone(awaitingDispatch=true)` reduces (V2/ADR-0009 — the queue must drain). That is also
 * the second layer that excludes the ADR-0011 bind-reconciliation: it only acts on `Preparing`/
 * `Running`, so an Idle FSM leaves it nothing to reconcile while the dispatch is in flight. Keeping
 * the FSM in `Running` to "show" the dispatch would reopen exactly that window — hence the axis.
 *
 * @see net.devemperor.dictate.state.WindowsDispatchState
 * @see net.devemperor.dictate.state.Action.WindowsDispatchAction
 * @see net.devemperor.dictate.state.ReviewPanelModule
 */
object WindowsDispatchModule :
    DictateModule<WindowsDispatchState, Action.WindowsDispatchAction, WindowsDispatchModule.Effect> {

    override val id: ModuleId = ModuleId.WindowsDispatch
    override val actionClass: KClass<Action.WindowsDispatchAction> = Action.WindowsDispatchAction::class

    override fun read(global: DictateUiState): WindowsDispatchState = global.windowsDispatch
    override fun write(global: DictateUiState, sub: WindowsDispatchState): DictateUiState =
        global.copy(windowsDispatch = sub)

    override fun initialState(): WindowsDispatchState = WindowsDispatchState()

    sealed interface Effect : SideEffect {
        /** Acknowledge only — no pending part exists for this session. */
        data class MarkAcknowledged(val sessionId: String, val at: Long) : Effect

        /**
         * A pending part exists (teardown cascade, or an older uninserted history row just
         * re-sent). Remove it AND acknowledge in one hop through the shared markInserted channel:
         * `PendingSessionsAction.Dismiss` → `Effect.PersistDismissal` → `sessionRepo.markInserted`.
         *
         * NOT `AcceptAndInsert`: that is intercepted by the IME side-channel and ADDITIONALLY
         * commits the text into the Android host field — the double delivery this branch prevents.
         */
        data class DismissPendingPart(val sessionId: String) : Effect

        /** Failure → surface the text as a pending part (the existing ADR-0011 fallback). */
        data class SurfacePendingPart(val sessionId: String, val text: String, val createdAt: Long) : Effect
    }

    override fun reduce(
        state: WindowsDispatchState,
        action: Action.WindowsDispatchAction,
        ctx: ReducerContext,
    ): TransitionResult<WindowsDispatchState, Effect>? = when (action) {

        is Action.WindowsDispatchAction.Started ->
            if (state.inFlight.any { it.sessionId == action.sessionId }) {
                null // dedup — a session is dispatched exactly once at a time
            } else {
                TransitionResult(
                    state.copy(
                        inFlight = state.inFlight.add(
                            InFlightDispatch(
                                sessionId = action.sessionId,
                                text = action.text,
                                createdAt = action.createdAt,
                                acknowledgeOnSuccess = action.acknowledgeOnSuccess,
                                surfacedAsPending = action.surfacedAsPending,
                            ),
                        ),
                        notice = null, // a fresh send clears the previous notice
                    ),
                    emptyList(),
                )
            }

        is Action.WindowsDispatchAction.MarkSurfaced -> {
            // The cascade surfaced the text as a pending part — note it in OUR axis so a later
            // Succeeded knows it must remove the part again (not leave a "Tap to paste" ghost).
            val idx = state.inFlight.indexOfFirst { it.sessionId == action.sessionId }
            if (idx < 0 || state.inFlight[idx].surfacedAsPending) {
                null // unknown session, or already flagged → idempotent no-op
            } else {
                TransitionResult(
                    state.copy(
                        inFlight = state.inFlight.set(idx, state.inFlight[idx].copy(surfacedAsPending = true)),
                    ),
                    emptyList(),
                )
            }
        }

        is Action.WindowsDispatchAction.Succeeded -> {
            val f = state.inFlight.firstOrNull { it.sessionId == action.sessionId }
            // P1 — deterministic from OUR axis. A pending part exists iff surfacedAsPending is set:
            // (a) the teardown cascade created it (→ MarkSurfaced), or (b) it was a re-sent
            // still-pending history row (→ Started(surfacedAsPending = true)). In BOTH cases the
            // acknowledge must remove the part too, else a "Tap to paste" ghost survives
            // (pendingFlow() is emptyFlow(), no DB-driven refresh of the axis).
            val ack: Effect? = when {
                f == null -> null // unknown → nothing to do
                f.surfacedAsPending -> Effect.DismissPendingPart(f.sessionId) // removes + acknowledges
                f.acknowledgeOnSuccess -> Effect.MarkAcknowledged(f.sessionId, ctx.now)
                else -> null // pure re-send of an already-acknowledged row → nothing to do
            }
            TransitionResult(
                state.copy(
                    inFlight = state.inFlight.removeAll { it.sessionId == action.sessionId },
                    // P2 — CLIPBOARD_ONLY is delivered but NOT typed → visible notice.
                    notice = if (action.outcome == InsertionOutcomeWire.CLIPBOARD_ONLY) {
                        DispatchNotice.ClipboardOnly
                    } else {
                        null
                    },
                ),
                listOfNotNull(ack),
            )
        }

        is Action.WindowsDispatchAction.Failed -> {
            val f = state.inFlight.firstOrNull { it.sessionId == action.sessionId } ?: return null
            TransitionResult(
                state.copy(
                    inFlight = state.inFlight.removeAll { it.sessionId == action.sessionId },
                    notice = DispatchNotice.Error(action.errorKind),
                ),
                // A part already exists (cascade / re-sent pending row) → do nothing. Otherwise
                // surface the text as a pending part (the existing ADR-0011 fallback). AddOne also
                // dedups by sessionId — belt AND braces: exactly one part, never two.
                if (f.surfacedAsPending) {
                    emptyList()
                } else {
                    listOf(Effect.SurfacePendingPart(f.sessionId, f.text, f.createdAt))
                },
            )
        }

        Action.WindowsDispatchAction.DismissNotice ->
            if (state.notice != null) TransitionResult(state.copy(notice = null), emptyList()) else null
    }

    override fun runEffect(effect: Effect, services: ModuleServices) {
        when (effect) {
            is Effect.MarkAcknowledged ->
                services.scope.launch { services.sessionRepo.markInserted(effect.sessionId, effect.at) }

            is Effect.DismissPendingPart ->
                services.emitAction(Action.PendingSessionsAction.Dismiss(effect.sessionId))

            is Effect.SurfacePendingPart ->
                // createdAt comes from the InFlightDispatch — NO DB lookup, NO clock port needed
                // (unlike ReviewPanelModule, which must look the createdAt up).
                services.emitAction(
                    Action.PendingSessionsAction.AddOne(
                        PendingSession(
                            sessionId = effect.sessionId,
                            status = SessionStatus.COMPLETED,
                            transcribedText = effect.text,
                            createdAt = effect.createdAt,
                        ),
                    ),
                )
        }
    }

    /**
     * Teardown safety net (ADR-0002 cascade; mirrors ReviewPanelModule.ConvertToPendingAndClose).
     * The IME view is going away while a dispatch is in flight → surface every in-flight text as a
     * pending part NOW, so it cannot be lost if the process dies before the HTTP call returns. The
     * dispatch itself KEEPS RUNNING — the coordinator lives in the service, not the IME.
     *
     * Emits TWO actions per in-flight session: the pending part itself, AND `MarkSurfaced` so this
     * axis records that a part now exists. The orchestrator reduces actions serially, so both land
     * before any later Succeeded/Failed for that session.
     *
     * `inFlight` is deliberately NOT cleared: the entries stay so a later Succeeded/Failed can
     * resolve them (Succeeded then sees surfacedAsPending → Dismiss → part removed AND acknowledged;
     * Failed sees it → no second part). A stranded entry after process death costs nothing — the
     * pending part is durably recoverable via findPendingInsertion (final_output_text is written
     * in-transaction, ADR-0013 §3).
     *
     * Never fires in the pure headless case (no IME view was ever visible → no true→false edge);
     * that is correct — the durable net there is the cold-boot findPendingInsertion recovery.
     */
    override fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> =
        if (prev.imeViewVisible && !next.imeViewVisible && next.windowsDispatch.inFlight.isNotEmpty()) {
            next.windowsDispatch.inFlight.flatMap {
                listOf(
                    Action.PendingSessionsAction.AddOne(
                        PendingSession(
                            sessionId = it.sessionId,
                            status = SessionStatus.COMPLETED,
                            transcribedText = it.text,
                            createdAt = it.createdAt,
                        ),
                    ),
                    Action.WindowsDispatchAction.MarkSurfaced(it.sessionId),
                )
            }
        } else {
            emptyList()
        }
}
