package net.devemperor.dictate.state

import kotlinx.collections.immutable.persistentListOf
import net.devemperor.dictate.database.entity.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-reducer tests for [PendingSessionsModule].
 *
 * Coverage:
 * - Refresh replaces the list (and is idempotent on identical content)
 * - Dismiss removes the matching session and emits a PersistDismissal effect
 * - Dismiss on unknown session-id returns null
 * - Lens + id + initial state
 */
class PendingSessionsModuleTest {

    private val module = PendingSessionsModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial())

    private fun session(id: String) = PendingSession(
        sessionId = id,
        status = SessionStatus.RECORDED,
        transcribedText = null,
        createdAt = 0L,
    )

    @Test
    fun `Refresh replaces the list`() {
        val state = persistentListOf(session("a"))
        val sessions = listOf(session("b"), session("c"))
        val result = module.reduce(state, Action.PendingSessionsAction.Refresh(sessions), ctx())
        assertEquals(2, result!!.nextState.size)
        assertEquals("b", result.nextState[0].sessionId)
        assertEquals("c", result.nextState[1].sessionId)
    }

    @Test
    fun `Refresh with identical content returns null (idempotent)`() {
        val state = persistentListOf(session("a"), session("b"))
        val sessions = listOf(session("a"), session("b"))
        assertNull(module.reduce(state, Action.PendingSessionsAction.Refresh(sessions), ctx()))
    }

    @Test
    fun `Dismiss removes the matching session and emits PersistDismissal`() {
        val state = persistentListOf(session("a"), session("b"), session("c"))
        val result = module.reduce(state, Action.PendingSessionsAction.Dismiss("b"), ctx())
        assertEquals(2, result!!.nextState.size)
        assertEquals(listOf("a", "c"), result.nextState.map { it.sessionId })
        assertTrue(result.sideEffects.contains(PendingSessionsModule.Effect.PersistDismissal("b")))
    }

    @Test
    fun `Dismiss on unknown session-id returns null`() {
        val state = persistentListOf(session("a"))
        assertNull(module.reduce(state, Action.PendingSessionsAction.Dismiss("missing"), ctx()))
    }

    // ─── K4: refinement result replaces the stale pre-refinement pending part ─

    @Test
    fun `AddOrReplaceOne replaces an existing entry's text keeping its createdAt (K4)`() {
        val stale = PendingSession(
            sessionId = "s", status = SessionStatus.COMPLETED,
            transcribedText = "PRE-REFINEMENT", createdAt = 42L,
        )
        val state = persistentListOf(session("other"), stale)
        val refined = PendingSession(
            sessionId = "s", status = SessionStatus.COMPLETED,
            transcribedText = "REFINED", createdAt = 999L,
        )
        val result = module.reduce(state, Action.PendingSessionsAction.AddOrReplaceOne(refined), ctx())!!
        val updated = result.nextState.single { it.sessionId == "s" }
        assertEquals("REFINED", updated.transcribedText)
        assertEquals("createdAt/ordering preserved", 42L, updated.createdAt)
        assertEquals("no entry added or dropped", 2, result.nextState.size)
    }

    @Test
    fun `AddOrReplaceOne appends when the session is absent (K4)`() {
        val state = persistentListOf(session("other"))
        val refined = PendingSession(
            sessionId = "s", status = SessionStatus.COMPLETED,
            transcribedText = "REFINED", createdAt = 7L,
        )
        val result = module.reduce(state, Action.PendingSessionsAction.AddOrReplaceOne(refined), ctx())!!
        assertEquals(2, result.nextState.size)
        assertEquals("REFINED", result.nextState.single { it.sessionId == "s" }.transcribedText)
    }

    // ─── R4 aggregate arms (ADR-0009 / spec §3.5) ───────────────────────

    private fun completed(id: String) = PendingSession(
        sessionId = id,
        status = SessionStatus.COMPLETED,
        transcribedText = "text-$id",
        createdAt = 0L,
    )

    @Test
    fun `DismissAll removes only COMPLETED and emits one PersistDismissal each`() {
        val state = persistentListOf(
            completed("c1"),
            session("recorded"),           // RECORDED — must survive
            completed("c2"),
            interrupted("interrupted"),    // RECORDING_INTERRUPTED — must survive
        )
        val result = module.reduce(state, Action.PendingSessionsAction.DismissAll, ctx())
        assertEquals(
            listOf("recorded", "interrupted"),
            result!!.nextState.map { it.sessionId },
        )
        assertEquals(
            listOf(
                PendingSessionsModule.Effect.PersistDismissal("c1"),
                PendingSessionsModule.Effect.PersistDismissal("c2"),
            ),
            result.sideEffects,
        )
    }

    @Test
    fun `DismissAll with no COMPLETED entries returns null`() {
        val state = persistentListOf(session("recorded"), interrupted("i"))
        assertNull(module.reduce(state, Action.PendingSessionsAction.DismissAll, ctx()))
    }

    @Test
    fun `AcceptAndInsertAll is a state-unchanged no-op with no effects (side-channel marker)`() {
        val state = persistentListOf(completed("c1"), completed("c2"))
        val result = module.reduce(state, Action.PendingSessionsAction.AcceptAndInsertAll, ctx())
        assertNotNull("marker action must route (not reducer-null) to keep the log truthful", result)
        assertEquals(state, result!!.nextState)
        assertTrue(result.sideEffects.isEmpty())
    }

    private fun interrupted(id: String) = PendingSession(
        sessionId = id,
        status = SessionStatus.RECORDING_INTERRUPTED,
        transcribedText = null,
        createdAt = 0L,
    )

    @Test
    fun `module id is PendingSessions`() {
        assertEquals(ModuleId.PendingSessions, module.id)
    }

    @Test
    fun `lens round-trip preserves pendingSessions axis`() {
        val custom = persistentListOf(session("x"))
        val state = DictateUiState.initial().copy(pendingSessions = custom)
        assertEquals(custom, module.read(state))
        val back = module.write(state, persistentListOf())
        assertEquals(0, back.pendingSessions.size)
    }

    @Test
    fun `initial state is empty persistent list`() {
        assertEquals(0, module.initialState().size)
    }
}
