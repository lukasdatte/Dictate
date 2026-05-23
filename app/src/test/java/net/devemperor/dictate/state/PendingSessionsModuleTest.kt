package net.devemperor.dictate.state

import kotlinx.collections.immutable.persistentListOf
import net.devemperor.dictate.database.entity.SessionStatus
import org.junit.Assert.assertEquals
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
