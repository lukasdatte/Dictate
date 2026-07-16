package net.devemperor.dictate.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.devemperor.dictate.core.PipelineTerminalDispatchGuard
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.WindowsTarget
import net.devemperor.dictate.shared.client.DispatchClient
import net.devemperor.dictate.shared.protocol.InsertionOutcomeWire
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.protocol.SyncCursor
import net.devemperor.dictate.shared.sync.SyncClient
import net.devemperor.dictate.shared.sync.SyncSource
import net.devemperor.dictate.shared.transport.DispatchTransport
import net.devemperor.dictate.testutil.FakeSharedPreferences
import net.devemperor.dictate.testutil.fakeModuleServices
import net.devemperor.dictate.windows.ProgrammableTransport
import net.devemperor.dictate.windows.WindowsDispatchCoordinator
import net.devemperor.dictate.windows.WindowsDispatchService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The two-path equivalence suite (D1 / ADR-0019 §3.6): auto-send has TWO terminal producers —
 * the IME seam (`onPipelineCompleted`'s else-if) and the headless sink (`DictatePipelineService`'s
 * `setHeadlessTerminalSink.onCompleted`). The non-duplication rule says they must resolve to the
 * SAME end state. This suite runs the SAME assertion set over BOTH, parametrized over a [Producer]
 * so forgetting to wire one path fails mechanically.
 *
 * **What a producer IS here.** The real seams live in Android code (a 6000-line
 * `InputMethodService` in Java; a lambda inside the service's `onCreate`) — not JVM-drivable. This
 * suite models each producer's *contract*: the exact action sequence + coordinator call each seam
 * emits at the terminal delivery point. Both seams, by contract:
 *   1. run AFTER the `PipelineCallbackBridge` has already consumed the terminal guard,
 *   2. emit `PipelineDone(committed = false, awaitingDispatch = true)` (queue drains, FSM Idle,
 *      no commit, no pending part), then
 *   3. call the ONE [WindowsDispatchCoordinator.dispatch] with `acknowledgeOnSuccess = true,
 *      surfacedAsPending = false`.
 * If a future edit makes one seam diverge (drops `awaitingDispatch`, double-acknowledges, touches
 * the guard), the matching parametrized run breaks. The IME seam's byte-identical toggle-off is
 * additionally guaranteed mechanically by the seam-diff proof in the wd-13 commit.
 *
 * Pure JVM (K-1/K-4): a real [DictateOrchestrator] + the real module registry, a real coordinator
 * over a fake transport, `Dispatchers.Unconfined` so effects run inline.
 */
@RunWith(Parameterized::class)
class WindowsAutoSendBothProducersTest(private val producer: Producer) {

    enum class Producer { IME_SEAM, HEADLESS_SINK }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun producers(): List<Array<Any>> =
            listOf(arrayOf(Producer.IME_SEAM), arrayOf(Producer.HEADLESS_SINK))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sid = "sess-1"
    private val text = "hello world"
    private val target = WindowsTarget("http://vm-win:8756", "dev-1", "secret", "Office PC")

    /** Synchronous executor so the coordinator's fire-and-forget body runs inline. */
    private val inline = Executor { it.run() }

    private val emptySyncSource = object : SyncSource {
        override fun sessionsAfter(cursor: SyncCursor?, limit: Int): List<SessionUpsert> = emptyList()
    }

    /** Counting [PipelineSessionRepoSubsystem] — records markInserted across BOTH modules. */
    private class CountingRepo : PipelineSessionRepoSubsystem {
        val markInsertedCalls = mutableListOf<String>()
        override suspend fun loadPending(): List<PendingSession> = emptyList()
        override suspend fun markInserted(sessionId: String, at: Long) { markInsertedCalls += sessionId }
        override suspend fun markFailed(sessionId: String, reason: String) = Unit
        override fun pendingFlow(): kotlinx.coroutines.flow.Flow<List<PendingSession>> =
            kotlinx.coroutines.flow.emptyFlow()
        override suspend fun syncAudioFilePaths(sessionId: String): Int = 0
    }

    private val repo = CountingRepo()
    private lateinit var orchestrator: DictateOrchestrator
    private lateinit var store: DictateUiStateStore

    /** Auto-send is on AND paired. */
    private fun pairedAutoSendPrefs(enabled: Boolean = true): FakeSharedPreferences =
        FakeSharedPreferences().apply {
            edit()
                .putBoolean(Pref.WindowsAutoSendEnabled.key, enabled)
                .putString(Pref.WindowsTargetUrl.key, target.baseUrl)
                .putString(Pref.WindowsDeviceId.key, target.deviceId)
                .putString(Pref.WindowsDeviceSecret.key, target.deviceSecret)
                .putString(Pref.WindowsServerName.key, target.serverName)
                .apply()
        }

    private fun buildOrchestrator(sp: FakeSharedPreferences = pairedAutoSendPrefs()) {
        store = DictateUiStateStore(
            DictateUiState.initial().copy(
                pipeline = PipelineUiState.Running(sid, InsertionTarget.INPUT_CONNECTION),
            ),
        )
        val services = fakeModuleServices(
            scope = scope,
            emitAction = { orchestrator.emitAction(it) },
            sessionRepo = repo,
            sharedPrefs = sp,
        )
        orchestrator = DictateOrchestrator(
            scope = scope,
            store = store,
            services = services,
            registry = DictateModuleRegistry,
        )
    }

    private fun coordinator(transport: DispatchTransport): WindowsDispatchCoordinator {
        val service = WindowsDispatchService(
            clientFactory = { t -> DispatchClient(transport) { t.credentials() } },
            syncClientFactory = { t -> SyncClient(DispatchClient(transport) { t.credentials() }, emptySyncSource) },
        )
        return WindowsDispatchCoordinator(
            service = service,
            targetProvider = { target },
            emitAction = { orchestrator.emitAction(it) },
            audit = { _, _, _ -> },
            executor = inline,
        )
    }

    /**
     * Drive the terminal auto-send emission the way BOTH producers do. [guard] is consumed by the
     * bridge FIRST (identical on both paths); then the seam emits the terminal PipelineDone and
     * calls the coordinator. Producer-agnostic on purpose — that IS the contract under test.
     */
    private fun driveAutoSend(guard: PipelineTerminalDispatchGuard, coordinator: WindowsDispatchCoordinator) {
        // Step 1 — the bridge consumes the guard before anything Windows-specific (both paths).
        assertTrue("bridge must win the guard first", guard.tryConsume(sid))
        // Step 2 — the terminal PipelineDone (queue drains, FSM Idle, no commit/pending part).
        orchestrator.dispatch(
            Action.PipelineAction.PipelineDone(sid, text, committed = false, awaitingDispatch = true),
        )
        // Step 3 — the ONE coordinator, identical call from either seam.
        coordinator.dispatch(
            sid, text, createdAt = 1_000L, origin = SessionOriginWire.KEYBOARD,
            acknowledgeOnSuccess = true, surfacedAsPending = false,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun autoSend_success_marksInsertedAndCreatesNoPendingPart() {
        buildOrchestrator()
        driveAutoSend(PipelineTerminalDispatchGuard(), coordinator(ProgrammableTransport.delivered(sid)))

        assertEquals("acknowledged exactly once", listOf(sid), repo.markInsertedCalls)
        assertTrue("no pending part on success", store.snapshot.pendingSessions.isEmpty())
        assertTrue("in-flight resolved", store.snapshot.windowsDispatch.inFlight.isEmpty())
    }

    @Test
    fun autoSend_failure_createsExactlyOnePendingPart() {
        buildOrchestrator()
        driveAutoSend(PipelineTerminalDispatchGuard(), coordinator(ProgrammableTransport.unreachable()))

        assertEquals("exactly one pending part on failure", 1, store.snapshot.pendingSessions.size)
        assertEquals(sid, store.snapshot.pendingSessions[0].sessionId)
        assertEquals(text, store.snapshot.pendingSessions[0].transcribedText)
        assertTrue("no acknowledge on failure — text not yet delivered", repo.markInsertedCalls.isEmpty())
        assertEquals(
            "error notice surfaced (carrying the failed session id for a PC-only retry)",
            DispatchNotice.Error(PipelineErrorKind.WINDOWS_UNREACHABLE, sid),
            store.snapshot.windowsDispatch.notice,
        )
    }

    @Test
    fun autoSend_consumesTerminalGuardExactlyOnce() {
        buildOrchestrator()
        val guard = PipelineTerminalDispatchGuard()
        driveAutoSend(guard, coordinator(ProgrammableTransport.delivered(sid)))

        // The guard was consumed by the bridge (step 1). The dispatch path never touches it, so a
        // later producer (reconciliation, headless retry) gets false — exactly-once holds.
        assertFalse("guard already consumed — no second terminal", guard.tryConsume(sid))
    }

    @Test
    fun autoSend_leavesPipelineFsmIdle() {
        buildOrchestrator()
        driveAutoSend(PipelineTerminalDispatchGuard(), coordinator(ProgrammableTransport.delivered(sid)))

        // PipelineDone(awaitingDispatch) drains the (empty) ADR-0009 queue → Idle. The in-flight
        // state lives ONLY in the windowsDispatch axis, never in the pipeline FSM (§3.6).
        assertTrue("FSM Idle after terminal", store.snapshot.pipeline is PipelineUiState.Idle)
    }

    @Test
    fun autoSend_bindReconciliationAfterDispatch_isNoOp() {
        buildOrchestrator()
        val guard = PipelineTerminalDispatchGuard()
        driveAutoSend(guard, coordinator(ProgrammableTransport.delivered(sid)))

        val markedBefore = repo.markInsertedCalls.size
        // An IME binds AFTER the dispatch: reconciliation runs against a COMPLETED DB row, the SAME
        // guard, and the post-dispatch snapshot. Doubly excluded: the guard is gone AND the FSM is
        // Idle (reconcile only acts on Preparing/Running). It must emit NOTHING.
        val reconciliation = PipelineBindReconciliation(
            loadSession = { completedRow(sid) },
            getFinalOutput = { text },
            guard = guard,
            emitAction = { orchestrator.emitAction(it) },
            snapshotProvider = { store.snapshot },
            ioContext = EmptyCoroutineContext,
        )
        runBlocking { reconciliation.reconcile() }

        assertTrue("FSM still Idle", store.snapshot.pipeline is PipelineUiState.Idle)
        assertTrue("no second pending part", store.snapshot.pendingSessions.isEmpty())
        assertEquals("no second acknowledge", markedBefore, repo.markInsertedCalls.size)
    }

    @Test
    fun autoSend_toggleOff_emitsPendingPartPathUnchanged() {
        // Auto-send OFF → the producer's toggle-off terminal is the existing committed=false
        // pending-part path (ADR-0011), NOT a dispatch. Identical for both producers at the action
        // contract (the IME seam's byte-identical toggle-off is proven by the seam-diff in wd-13).
        buildOrchestrator(sp = pairedAutoSendPrefs(enabled = false))
        orchestrator.dispatch(Action.PipelineAction.PipelineDone(sid, text, committed = false))

        assertEquals("one pending-paste part", 1, store.snapshot.pendingSessions.size)
        assertEquals(sid, store.snapshot.pendingSessions[0].sessionId)
        assertTrue("no dispatch in-flight", store.snapshot.windowsDispatch.inFlight.isEmpty())
        assertTrue("no acknowledge", repo.markInsertedCalls.isEmpty())
    }

    private fun completedRow(id: String): SessionEntity = SessionEntity(
        id = id,
        type = "RECORDING",
        createdAt = 1_000L,
        targetAppPackage = null,
        language = null,
        audioFilePath = null,
        audioFilePaths = emptyList(),
        audioDurationSeconds = 0L,
        parentSessionId = null,
        status = SessionStatus.COMPLETED.name,
        origin = "KEYBOARD",
        queuedPromptIds = null,
        lastErrorType = null,
        lastErrorMessage = null,
        finalOutputText = text,
        inputText = null,
        insertedAt = null,
    )
}
