package net.devemperor.dictate.state

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.shared.protocol.InsertionOutcomeWire
import net.devemperor.dictate.testutil.fakeModuleServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-reducer + effect + cascade regression tests for [WindowsDispatchModule] (ADR-0019).
 *
 * Pins the P1 (no ghost pending part after a success-post-teardown), P2 (CLIPBOARD_ONLY notice),
 * and surfacedAsPending-determinism invariants from plan §3.5.
 */
class WindowsDispatchModuleTest {

    private val module = WindowsDispatchModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial(), now = 42L)

    private fun inFlight(
        sessionId: String = "s1",
        acknowledgeOnSuccess: Boolean = true,
        surfacedAsPending: Boolean = false,
        suppressPendingFallback: Boolean = false,
    ) = InFlightDispatch(
        sessionId = sessionId,
        text = "text-$sessionId",
        createdAt = 1_000L,
        acknowledgeOnSuccess = acknowledgeOnSuccess,
        surfacedAsPending = surfacedAsPending,
        suppressPendingFallback = suppressPendingFallback,
    )

    private fun state(vararg entries: InFlightDispatch, notice: DispatchNotice? = null) =
        WindowsDispatchState(inFlight = persistentListOf(*entries), notice = notice)

    // ── Started ──

    @Test
    fun `Started adds an in-flight entry and clears any notice`() {
        val r = module.reduce(
            state(notice = DispatchNotice.ClipboardOnly),
            Action.WindowsDispatchAction.Started("s1", "hi", 1_000L, acknowledgeOnSuccess = true, surfacedAsPending = false),
            ctx(),
        )!!
        assertEquals(1, r.nextState.inFlight.size)
        assertEquals("s1", r.nextState.inFlight[0].sessionId)
        assertNull(r.nextState.notice)
        assertTrue(r.sideEffects.isEmpty())
    }

    @Test
    fun `Started dedups an already in-flight session`() {
        assertNull(
            module.reduce(
                state(inFlight("s1")),
                Action.WindowsDispatchAction.Started("s1", "hi", 1_000L, acknowledgeOnSuccess = true, surfacedAsPending = false),
                ctx(),
            ),
        )
    }

    // ── Succeeded ──

    @Test
    fun `succeeded_withoutPendingPart_acknowledgesOnly`() {
        val r = module.reduce(
            state(inFlight("s1", surfacedAsPending = false)),
            Action.WindowsDispatchAction.Succeeded("s1", InsertionOutcomeWire.TYPED_CTRL_V),
            ctx(),
        )!!
        assertEquals(
            listOf(WindowsDispatchModule.Effect.MarkAcknowledged("s1", 42L)),
            r.sideEffects,
        )
        assertTrue(r.nextState.inFlight.isEmpty())
        assertNull(r.nextState.notice)
    }

    @Test
    fun `succeeded_afterTeardown_leavesNoGhostPendingPart`() {
        // The cascade already surfaced a part (surfacedAsPending = true).
        val r = module.reduce(
            state(inFlight("s1", surfacedAsPending = true)),
            Action.WindowsDispatchAction.Succeeded("s1", InsertionOutcomeWire.TYPED_CTRL_V),
            ctx(),
        )!!
        // The effect is Dismiss (removes the part AND acknowledges), NOT a bare MarkAcknowledged.
        assertEquals(
            listOf(WindowsDispatchModule.Effect.DismissPendingPart("s1")),
            r.sideEffects,
        )

        // Downstream: the PendingSessionsModule reduces the Dismiss the effect will emit — the part
        // is removed AND exactly one PersistDismissal (→ one markInserted) fires. No ghost.
        val pending = persistentListOf(
            PendingSession("s1", SessionStatus.COMPLETED, "text-s1", 1_000L),
        )
        val pendingResult = PendingSessionsModule.reduce(
            pending, Action.PendingSessionsAction.Dismiss("s1"), ctx(),
        )!!
        assertTrue(pendingResult.nextState.none { it.sessionId == "s1" })
        assertEquals(
            listOf(PendingSessionsModule.Effect.PersistDismissal("s1")),
            pendingResult.sideEffects,
        )
    }

    @Test
    fun `succeeded_neverEmitsAcceptAndInsert`() {
        // Across every success shape, the effect stream must never route through AcceptAndInsert
        // (the IME side-channel would double-commit into the host field).
        for (surfaced in listOf(true, false)) {
            for (ack in listOf(true, false)) {
                val r = module.reduce(
                    state(inFlight("s1", acknowledgeOnSuccess = ack, surfacedAsPending = surfaced)),
                    Action.WindowsDispatchAction.Succeeded("s1", InsertionOutcomeWire.TYPED_CTRL_V),
                    ctx(),
                )!!
                // The module's own effects only — none of them is AcceptAndInsert (a foreign action);
                // this is a structural guarantee, asserted as "no effect is the Dismiss-that-inserts".
                assertFalse(
                    r.sideEffects.any { it !is WindowsDispatchModule.Effect },
                )
            }
        }
    }

    @Test
    fun `reSendOfAcknowledgedRow_succeeded_doesNotTouchInsertedAt`() {
        val r = module.reduce(
            state(inFlight("s1", acknowledgeOnSuccess = false, surfacedAsPending = false)),
            Action.WindowsDispatchAction.Succeeded("s1", InsertionOutcomeWire.TYPED_CTRL_V),
            ctx(),
        )!!
        assertTrue(r.sideEffects.isEmpty())
        assertTrue(r.nextState.inFlight.isEmpty())
    }

    @Test
    fun `succeeded_clipboardOnly_acknowledgesAndSetsNotice`() {
        val r = module.reduce(
            state(inFlight("s1", surfacedAsPending = false)),
            Action.WindowsDispatchAction.Succeeded("s1", InsertionOutcomeWire.CLIPBOARD_ONLY),
            ctx(),
        )!!
        assertEquals(
            listOf(WindowsDispatchModule.Effect.MarkAcknowledged("s1", 42L)),
            r.sideEffects,
        )
        assertEquals(DispatchNotice.ClipboardOnly, r.nextState.notice)
    }

    @Test
    fun `succeeded_typedCtrlV_setsNoNotice`() {
        val r = module.reduce(
            state(inFlight("s1")),
            Action.WindowsDispatchAction.Succeeded("s1", InsertionOutcomeWire.TYPED_CTRL_V),
            ctx(),
        )!!
        assertNull(r.nextState.notice)
    }

    @Test
    fun `succeeded_forUnknownSession_removesNothing_noEffect`() {
        val r = module.reduce(
            state(inFlight("s1")),
            Action.WindowsDispatchAction.Succeeded("other", InsertionOutcomeWire.TYPED_CTRL_V),
            ctx(),
        )!!
        // Unknown session → no ack effect, and s1 is untouched.
        assertTrue(r.sideEffects.isEmpty())
        assertEquals(1, r.nextState.inFlight.size)
    }

    // ── Failed ──

    @Test
    fun `failed_withLiveView_yieldsOnePendingPartAndNoAcknowledge`() {
        val r = module.reduce(
            state(inFlight("s1", surfacedAsPending = false)),
            Action.WindowsDispatchAction.Failed("s1", PipelineErrorKind.WINDOWS_UNREACHABLE),
            ctx(),
        )!!
        assertEquals(
            listOf(WindowsDispatchModule.Effect.SurfacePendingPart("s1", "text-s1", 1_000L)),
            r.sideEffects,
        )
        assertEquals(
            DispatchNotice.Error(PipelineErrorKind.WINDOWS_UNREACHABLE, "s1"),
            r.nextState.notice,
        )
        assertTrue(r.nextState.inFlight.isEmpty())
    }

    @Test
    fun `failed_inPcOnlyMode_suppressesPendingPartButKeepsErrorNoticeWithSessionId`() {
        // PC-only mode (pc-dictation-activity): no IME host → no "Tap to paste" part. The Activity
        // surfaces the error + a retry keyed on the sessionId carried in the notice.
        val r = module.reduce(
            state(inFlight("s1", surfacedAsPending = false, suppressPendingFallback = true)),
            Action.WindowsDispatchAction.Failed("s1", PipelineErrorKind.WINDOWS_UNREACHABLE),
            ctx(),
        )!!
        assertTrue("no pending part in PC-only mode", r.sideEffects.isEmpty())
        assertEquals(
            DispatchNotice.Error(PipelineErrorKind.WINDOWS_UNREACHABLE, "s1"),
            r.nextState.notice,
        )
        assertTrue(r.nextState.inFlight.isEmpty())
    }

    @Test
    fun `failed_afterTeardown_yieldsExactlyOnePendingPart`() {
        // The cascade already created the part → Failed must NOT surface a second one.
        val r = module.reduce(
            state(inFlight("s1", surfacedAsPending = true)),
            Action.WindowsDispatchAction.Failed("s1", PipelineErrorKind.WINDOWS_UNREACHABLE),
            ctx(),
        )!!
        assertTrue(r.sideEffects.isEmpty())
        assertTrue(r.nextState.inFlight.isEmpty())
    }

    @Test
    fun `failed_forUnknownSession_isNoOp`() {
        assertNull(
            module.reduce(
                state(inFlight("s1")),
                Action.WindowsDispatchAction.Failed("other", PipelineErrorKind.WINDOWS_UNREACHABLE),
                ctx(),
            ),
        )
    }

    // ── MarkSurfaced ──

    @Test
    fun `markSurfaced_setsFlag_idempotent`() {
        val first = module.reduce(
            state(inFlight("s1", surfacedAsPending = false)),
            Action.WindowsDispatchAction.MarkSurfaced("s1"),
            ctx(),
        )!!
        assertTrue(first.nextState.inFlight[0].surfacedAsPending)

        // Second MarkSurfaced on an already-flagged entry is a no-op.
        assertNull(
            module.reduce(first.nextState, Action.WindowsDispatchAction.MarkSurfaced("s1"), ctx()),
        )
    }

    // ── DismissNotice ──

    @Test
    fun `dismissNotice_clearsNotice`() {
        val r = module.reduce(
            state(notice = DispatchNotice.ClipboardOnly),
            Action.WindowsDispatchAction.DismissNotice,
            ctx(),
        )!!
        assertNull(r.nextState.notice)
    }

    @Test
    fun `dismissNotice_withoutNotice_isNoOp`() {
        assertNull(module.reduce(state(), Action.WindowsDispatchAction.DismissNotice, ctx()))
    }

    // ── OpenPairing (ADR-0019 §3.2.2 — confirm on an unauthorized notice) ──

    @Test
    fun `openPairing_clearsNotice`() {
        val r = module.reduce(
            state(notice = DispatchNotice.Error(PipelineErrorKind.WINDOWS_UNAUTHORIZED)),
            Action.WindowsDispatchAction.OpenPairing,
            ctx(),
        )!!
        // Reducer only clears the notice; the Activity launch is the IME side-channel's job.
        assertNull(r.nextState.notice)
        assertTrue(r.sideEffects.isEmpty())
    }

    @Test
    fun `openPairing_withoutNotice_isNoOp`() {
        assertNull(module.reduce(state(), Action.WindowsDispatchAction.OpenPairing, ctx()))
    }

    // ── Teardown cascade ──

    @Test
    fun `teardownCascade_emitsAddOneAndMarkSurfaced_perInFlight`() {
        val prev = DictateUiState.initial().copy(imeViewVisible = true)
        val next = prev.copy(
            imeViewVisible = false,
            windowsDispatch = state(inFlight("s1"), inFlight("s2")),
        )
        val actions = module.onCrossModuleStateChange(prev, next)
        // Two actions per in-flight session: the pending part AND the MarkSurfaced note.
        assertEquals(4, actions.size)
        val addOnes = actions.filterIsInstance<Action.PendingSessionsAction.AddOne>()
        assertEquals(listOf("s1", "s2"), addOnes.map { it.session.sessionId })
        // createdAt comes from the InFlightDispatch, not a DB lookup.
        assertEquals(1_000L, addOnes[0].session.createdAt)
        val surfaced = actions.filterIsInstance<Action.WindowsDispatchAction.MarkSurfaced>()
        assertEquals(listOf("s1", "s2"), surfaced.map { it.sessionId })
    }

    @Test
    fun `teardownCascade_doesNotFireWhenInFlightEmpty`() {
        val prev = DictateUiState.initial().copy(imeViewVisible = true)
        val next = prev.copy(imeViewVisible = false)
        assertTrue(module.onCrossModuleStateChange(prev, next).isEmpty())
    }

    @Test
    fun `teardownCascade_doesNotClearInFlight`() {
        // The observer only READS; inFlight stays so a later Succeeded/Failed can resolve the ghost.
        val next = DictateUiState.initial().copy(
            imeViewVisible = false, windowsDispatch = state(inFlight("s1")),
        )
        assertEquals(1, next.windowsDispatch.inFlight.size)
    }

    // ── runEffect forwarding ──

    @Test
    fun `runEffect MarkAcknowledged calls markInserted exactly once`() {
        val repo = CountingSessionRepo()
        val services = fakeModuleServices(
            scope = CoroutineScope(Dispatchers.Unconfined),
            sessionRepo = repo,
        )
        module.runEffect(WindowsDispatchModule.Effect.MarkAcknowledged("s1", 99L), services)
        assertEquals(listOf("s1" to 99L), repo.marked)
    }

    /** Minimal repo fake counting [markInserted] — the interface's other members are no-ops. */
    private class CountingSessionRepo : PipelineSessionRepoSubsystem {
        val marked = mutableListOf<Pair<String, Long>>()
        override suspend fun loadPending(): List<PendingSession> = emptyList()
        override suspend fun markInserted(sessionId: String, at: Long) { marked += sessionId to at }
        override suspend fun markFailed(sessionId: String, reason: String) = Unit
        override fun pendingFlow() = emptyFlow<List<PendingSession>>()
        override suspend fun syncAudioFilePaths(sessionId: String): Int = 0
    }

    @Test
    fun `runEffect DismissPendingPart emits PendingSessionsAction Dismiss`() {
        val emitted = mutableListOf<Action>()
        val services = fakeModuleServices(emitAction = { emitted += it })
        module.runEffect(WindowsDispatchModule.Effect.DismissPendingPart("s1"), services)
        assertEquals(listOf(Action.PendingSessionsAction.Dismiss("s1")), emitted)
    }

    @Test
    fun `runEffect SurfacePendingPart emits an AddOne with the carried createdAt`() {
        val emitted = mutableListOf<Action>()
        val services = fakeModuleServices(emitAction = { emitted += it })
        module.runEffect(
            WindowsDispatchModule.Effect.SurfacePendingPart("s1", "text-s1", 1_000L), services,
        )
        val addOne = emitted.single() as Action.PendingSessionsAction.AddOne
        assertEquals("s1", addOne.session.sessionId)
        assertEquals("text-s1", addOne.session.transcribedText)
        assertEquals(1_000L, addOne.session.createdAt)
        assertEquals(SessionStatus.COMPLETED, addOne.session.status)
    }
}
