// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * Owns the `pendingSessions` axis — a [PersistentList] of
 * [PendingSession] entries shown in the restart-button UI.
 *
 * **DB-subscriber-driven (Spec 1 §15.1 — "DB-Subscriber (kein
 * Reducer)"):** the canonical state for this axis lives in the Room
 * database; an observer on
 * [ModuleServices.sessionRepo.pendingFlow] turns each DB snapshot into
 * an [Action.PendingSessionsAction.Refresh] dispatch. The reducer is
 * therefore mostly a passive sink — it copies the DB snapshot into the
 * state field. The Dismiss path mutates only the in-memory state; the
 * DB write follows via a side-effect.
 *
 * **PersistentList contract (F-9, forbidden pattern (e)):** never
 * round-trip through `toMutableList()` — structural sharing across
 * mutations is the whole point of using a persistent list. Use the
 * `add` / `remove` / `removeAll` operators from
 * `kotlinx.collections.immutable`, which preserve structural sharing.
 *
 * **No cross-module observer:** Spec 1 §15.1 lists this module as
 * observer-free.
 *
 * **Effect surface (Phase 1):** the Dismiss path emits a
 * [Effect.PersistDismissal] to mark the session as user-dismissed in
 * the DB. The B3 wiring supplies a real `sessionRepo` implementation;
 * in C7's hook-up the existing DB layer takes the dismissal as a
 * "markInserted/markFailed"-style write.
 *
 * @see net.devemperor.dictate.state.PendingSession
 * @see net.devemperor.dictate.state.Action.PendingSessionsAction
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §15.1
 */
object PendingSessionsModule : DictateModule<PersistentList<PendingSession>, Action.PendingSessionsAction, PendingSessionsModule.Effect> {

    override val id: ModuleId = ModuleId.PendingSessions
    override val actionClass: KClass<Action.PendingSessionsAction> = Action.PendingSessionsAction::class

    override fun read(global: DictateUiState): PersistentList<PendingSession> = global.pendingSessions
    override fun write(global: DictateUiState, sub: PersistentList<PendingSession>): DictateUiState =
        global.copy(pendingSessions = sub)

    override fun initialState(): PersistentList<PendingSession> = persistentListOf()

    /**
     * One effect: persist the user's Dismiss click to the DB so the
     * session doesn't re-appear after a re-bind. The DB layer is the
     * source of truth — the in-memory list is a mirror; the
     * `pendingFlow()` subscriber will eventually emit an updated
     * snapshot via Refresh, but mutating the local list first keeps
     * the UI responsive (no flicker waiting for the DB round-trip).
     */
    sealed interface Effect : SideEffect {
        data class PersistDismissal(val sessionId: String) : Effect
    }

    override fun reduce(
        state: PersistentList<PendingSession>,
        action: Action.PendingSessionsAction,
        ctx: ReducerContext,
    ): TransitionResult<PersistentList<PendingSession>, Effect>? = when (action) {

        is Action.PendingSessionsAction.Refresh -> {
            // DB snapshot — replace the whole list. Equality check
            // avoids re-emit when the snapshot is identical (the
            // upstream Flow may emit duplicate values during recovery).
            val next = action.sessions.toPersistentList()
            if (next != state) {
                TransitionResult(nextState = next, sideEffects = emptyList())
            } else null
        }

        is Action.PendingSessionsAction.Dismiss -> {
            // Optimistic local removal + DB-persist effect. If the
            // session isn't in the list (already dismissed), no-op.
            val idx = state.indexOfFirst { it.sessionId == action.sessionId }
            if (idx >= 0) {
                TransitionResult(
                    nextState = state.removeAt(idx),
                    sideEffects = listOf(Effect.PersistDismissal(action.sessionId)),
                )
            } else null
        }
    }

    override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
        is Effect.PersistDismissal -> {
            // Phase 1: route Dismiss through the existing repo
            // `markInserted` channel as a "user acknowledged this
            // session" semantic. B3 may wire a dedicated dismissal
            // surface if the DB layer adds one; until then this is the
            // closest existing op. Launches on the services scope so
            // the IO doesn't block the dispatch thread.
            services.scope.launch {
                services.sessionRepo.markInserted(effect.sessionId, System.currentTimeMillis())
            }
            Unit
        }
    }
}
