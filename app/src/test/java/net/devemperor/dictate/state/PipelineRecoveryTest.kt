package net.devemperor.dictate.state

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.testutil.FakePipelineSessionRepo
import net.devemperor.dictate.testutil.testPipelineRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [PipelineRecovery].
 *
 * Covers Spec 1 §4.6 baseline behaviour — load pending sessions from
 * the repo, write into `state.pendingSessions`. The full Spec 1 §6.3
 * recovery algorithm (status promotion, ghost-session cleanup) is
 * Block 3 scope; those tests will extend this class.
 *
 * **F-22 (2026-05-15):** the inline `FakeSessionRepo` was lifted to
 * `testutil/FakePipelineSessionRepo.kt` (shared with
 * `DictateOrchestratorInitOrderTest`).
 *
 * @see net.devemperor.dictate.state.PipelineRecovery
 * @see net.devemperor.dictate.testutil.FakePipelineSessionRepo
 */
class PipelineRecoveryTest {

    private fun session(id: String, status: SessionStatus = SessionStatus.RECORDED) =
        PendingSession(sessionId = id, status = status, transcribedText = null, createdAt = 0L)

    @Test
    fun `recover with empty repo leaves pendingSessions empty`() {
        val repo = FakePipelineSessionRepo(emptyList())
        val store = DictateUiStateStore(DictateUiState.initial())

        runBlocking { testPipelineRecovery(repo).recover(store) }

        assertTrue(store.snapshot.pendingSessions.isEmpty())
    }

    @Test
    fun `recover writes loadPending result into store as a PersistentList`() {
        val repo = FakePipelineSessionRepo(listOf(session("a"), session("b"), session("c")))
        val store = DictateUiStateStore(DictateUiState.initial())

        runBlocking { testPipelineRecovery(repo).recover(store) }

        val ids = store.snapshot.pendingSessions.map { it.sessionId }
        assertEquals(listOf("a", "b", "c"), ids)
    }

    @Test
    fun `recover preserves order from the repo`() {
        val repo = FakePipelineSessionRepo(listOf(session("z"), session("a"), session("m")))
        val store = DictateUiStateStore(DictateUiState.initial())

        runBlocking { testPipelineRecovery(repo).recover(store) }

        // Same order as the repo handed back — no re-sort.
        assertEquals(listOf("z", "a", "m"), store.snapshot.pendingSessions.map { it.sessionId })
    }

    @Test
    fun `recover is idempotent across repeated calls`() {
        val repo = FakePipelineSessionRepo(listOf(session("only")))
        val store = DictateUiStateStore(DictateUiState.initial())
        val recovery = testPipelineRecovery(repo)

        runBlocking {
            recovery.recover(store)
            recovery.recover(store)
        }

        // Second recover overwrites with the same list — count stays 1.
        assertEquals(1, store.snapshot.pendingSessions.size)
        assertEquals("only", store.snapshot.pendingSessions.first().sessionId)
    }

    @Test
    fun `recover merges new repo entries on re-run without dropping prior in-memory sessions`() {
        // Per Spec 1 §6.3 Z. 3433-3437 the recovery uses **MERGE**, not
        // override — a parallel recording that started during recovery
        // must not be erased. The C10 refactor changed the C7 baseline
        // (which used override) to the spec-conform merge semantics.
        //
        // This test verifies the merge contract: first recover()
        // hydrates with [first, second]. The repo then "rotates" to
        // [third] (simulating a follow-up boot where 'first'/'second'
        // were dismissed). A second recover() does NOT erase the
        // already-loaded sessions; it adds 'third' if it wasn't seen.
        var emit = listOf(session("first"), session("second"))
        val repo = object : PipelineSessionRepoSubsystem {
            override suspend fun loadPending(): List<PendingSession> = emit
            override suspend fun markInserted(sessionId: String, at: Long) = Unit
            override suspend fun markFailed(sessionId: String, reason: String) = Unit
            override fun pendingFlow(): Flow<List<PendingSession>> = emptyFlow()
        }
        val store = DictateUiStateStore(DictateUiState.initial())
        val recovery = testPipelineRecovery(repo)

        runBlocking {
            recovery.recover(store)
            emit = listOf(session("third"))
            recovery.recover(store)
        }

        // After both runs: in-memory still has 'first' and 'second', and
        // 'third' was appended on the merge.
        val ids = store.snapshot.pendingSessions.map { it.sessionId }
        assertEquals(listOf("first", "second", "third"), ids)
    }

    @Test
    fun `recover does not mutate other sub-states`() {
        val repo = FakePipelineSessionRepo(listOf(session("x")))
        val initial = DictateUiState.initial()
        val store = DictateUiStateStore(initial)

        runBlocking { testPipelineRecovery(repo).recover(store) }

        val after = store.snapshot
        assertEquals(initial.recording, after.recording)
        assertEquals(initial.pipeline, after.pipeline)
        assertEquals(initial.viewMode, after.viewMode)
        assertEquals(initial.audio, after.audio)
        assertEquals(initial.layout, after.layout)
        assertEquals(initial.overlay, after.overlay)
        assertEquals(initial.features, after.features)
        assertEquals(initial.theming, after.theming)
    }
}
