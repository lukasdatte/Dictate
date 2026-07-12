package net.devemperor.dictate.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import net.devemperor.dictate.core.PipelineTerminalDispatchGuard
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [PipelineBindReconciliation] — the per-bind
 * in-memory FSM healing safety net (ADR-0011 Decision 2).
 *
 * All collaborators are narrow lambdas, so no Android / Room dependency:
 * a fixed snapshot provider drives the pipeline sub-state, a map-backed
 * `loadSession` lambda plays the DB, and a recording `emitAction`
 * captures the dispatched terminal action. The real
 * [PipelineTerminalDispatchGuard] is used so the once-semantics are
 * exercised end-to-end.
 *
 * `ioContext = Dispatchers.Unconfined` keeps `withContext` inline with
 * `runTest` scheduling (mirrors PipelineRecoveryTest).
 */
class PipelineBindReconciliationTest {

    private val dispatched = mutableListOf<Action>()
    private val guard = PipelineTerminalDispatchGuard()

    private fun row(
        id: String,
        status: SessionStatus,
        finalOutputText: String? = null,
        lastErrorType: String? = null,
        lastErrorMessage: String? = null,
    ) = SessionEntity(
        id = id,
        type = "RECORDING",
        createdAt = 0L,
        targetAppPackage = null,
        language = null,
        audioFilePath = null,
        audioFilePaths = emptyList(),
        status = status.name,
        finalOutputText = finalOutputText,
        insertedAt = null,
        lastErrorType = lastErrorType,
        lastErrorMessage = lastErrorMessage,
    )

    private fun running(sid: String) = DictateUiState.initial().copy(
        pipeline = PipelineUiState.Running(
            sessionId = sid,
            target = InsertionTarget.INPUT_CONNECTION,
        ),
    )

    private fun reconciliation(
        snapshot: DictateUiState,
        rows: Map<String, SessionEntity>,
        finalOutput: (String) -> String? = { rows[it]?.finalOutputText },
    ) = PipelineBindReconciliation(
        loadSession = { rows[it] },
        getFinalOutput = finalOutput,
        guard = guard,
        emitAction = { dispatched.add(it) },
        snapshotProvider = { snapshot },
        ioContext = Dispatchers.Unconfined,
    )

    @Test
    fun `Running + DB COMPLETED dispatches one PipelineDone with text from getFinalOutput`() = runTest {
        // finalOutputText column is null on the row, but the getFinalOutput
        // lambda (the step-chain / transcription source) resolves the text —
        // proving the reconciler uses the lambda, not the raw column.
        val rows = mapOf("S" to row("S", SessionStatus.COMPLETED, finalOutputText = null))
        reconciliation(running("S"), rows, finalOutput = { "chain-text" }).reconcile()

        assertEquals(
            listOf<Action>(
                Action.PipelineAction.PipelineDone("S", "chain-text", committed = false),
            ),
            dispatched,
        )
        assertFalse("guard must be consumed", guard.tryConsume("S"))
    }

    @Test
    fun `Running + DB COMPLETED but guard pre-consumed dispatches nothing`() = runTest {
        // Race Order B: the bridge already delivered to the delegate and
        // consumed the guard before the bind reconcile ran.
        guard.tryConsume("S")
        val rows = mapOf("S" to row("S", SessionStatus.COMPLETED, finalOutputText = "x"))
        reconciliation(running("S"), rows).reconcile()

        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `reconcile consumes the guard so a later producer loses the race`() = runTest {
        // Race Order A inverse: reconcile wins, the later fallback / delivery
        // for the same session must find the guard already consumed.
        val rows = mapOf("S" to row("S", SessionStatus.COMPLETED, finalOutputText = "x"))
        reconciliation(running("S"), rows).reconcile()

        assertFalse(guard.tryConsume("S"))
    }

    @Test
    fun `Running + DB FAILED dispatches one PipelineFailed with reason from row`() = runTest {
        val rows = mapOf(
            "S" to row(
                "S", SessionStatus.FAILED,
                lastErrorType = "UNKNOWN", lastErrorMessage = "boom",
            ),
        )
        reconciliation(running("S"), rows).reconcile()

        assertEquals(
            listOf<Action>(Action.PipelineAction.PipelineFailed("S", "boom")),
            dispatched,
        )
    }

    @Test
    fun `Running + DB CANCELLED dispatches one CancelPipeline`() = runTest {
        val rows = mapOf("S" to row("S", SessionStatus.CANCELLED))
        reconciliation(running("S"), rows).reconcile()

        assertEquals(
            listOf<Action>(Action.PipelineAction.CancelPipeline("S")),
            dispatched,
        )
    }

    @Test
    fun `Running + DB non-terminal TRANSCRIBING dispatches nothing and leaves guard`() = runTest {
        // The run may still genuinely be in flight — reconciliation must
        // never preempt a live run.
        val rows = mapOf("S" to row("S", SessionStatus.TRANSCRIBING))
        reconciliation(running("S"), rows).reconcile()

        assertTrue(dispatched.isEmpty())
        assertTrue("guard must NOT be consumed", guard.tryConsume("S"))
    }

    @Test
    fun `Idle snapshot dispatches nothing`() = runTest {
        val snapshot = DictateUiState.initial().copy(pipeline = PipelineUiState.Idle)
        reconciliation(snapshot, mapOf("S" to row("S", SessionStatus.COMPLETED))).reconcile()

        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `Running with missing DB row dispatches nothing`() = runTest {
        reconciliation(running("S"), emptyMap()).reconcile()

        assertTrue(dispatched.isEmpty())
        assertTrue("guard must NOT be consumed", guard.tryConsume("S"))
    }

    @Test
    fun `ReprocessStaging snapshot dispatches nothing`() = runTest {
        // A COMPLETED DB row under ReprocessStaging is the expected resting
        // state of the session being re-edited — NOT a dropped terminal.
        val snapshot = DictateUiState.initial().copy(
            pipeline = PipelineUiState.ReprocessStaging(sessionId = "S", transcript = "t"),
        )
        reconciliation(snapshot, mapOf("S" to row("S", SessionStatus.COMPLETED))).reconcile()

        assertTrue(dispatched.isEmpty())
        assertTrue("guard must NOT be consumed", guard.tryConsume("S"))
    }

    @Test
    fun `reconcile twice dispatches only once`() = runTest {
        val rows = mapOf("S" to row("S", SessionStatus.COMPLETED, finalOutputText = "x"))
        val reconciler = reconciliation(running("S"), rows)
        reconciler.reconcile()
        reconciler.reconcile()

        assertEquals(1, dispatched.size)
    }
}
