package net.devemperor.dictate.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.devemperor.dictate.testutil.fakeModuleServices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wiring test for the headless-fallback contract (ADR-0011): the action
 * the fallback dispatches — `PipelineDone(committed = false)` — must,
 * through the real module registry, land a pending-paste entry in
 * `state.pendingSessions` (`PipelineModule` AddPendingInsertSession →
 * `PendingSessionsModule` AddOne).
 *
 * This documents the end-to-end guarantee the service-side headless sink
 * relies on: it never commits text (no IME), it only asks for a pending
 * entry the user pastes on the next bind.
 *
 * Pure JVM (K-1 / K-4). `Dispatchers.Unconfined` makes the effect's
 * `scope.launch { … emitAction(AddOne) }` run synchronously.
 */
class HeadlessFallbackPendingWiringTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `PipelineDone(committed=false) surfaces a pending-paste session`() {
        val store = DictateUiStateStore(
            DictateUiState.initial().copy(pipeline = PipelineUiState.Preparing("sess-1")),
        )
        lateinit var orchestrator: DictateOrchestrator
        val services = fakeModuleServices(
            scope = scope,
            emitAction = { orchestrator.emitAction(it) },
        )
        orchestrator = DictateOrchestrator(
            scope = scope,
            store = store,
            services = services,
            registry = DictateModuleRegistry,
        )

        orchestrator.dispatch(
            Action.PipelineAction.PipelineDone("sess-1", "hello world", committed = false),
        )

        val pending = store.snapshot.pendingSessions
        assertEquals("exactly one pending-paste entry expected", 1, pending.size)
        assertEquals("sess-1", pending[0].sessionId)
        assertEquals("hello world", pending[0].transcribedText)
    }
}
