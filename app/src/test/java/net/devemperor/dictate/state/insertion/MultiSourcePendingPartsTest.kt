package net.devemperor.dictate.state.insertion

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import net.devemperor.dictate.core.FakeInputConnection
import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.PendingSession
import net.devemperor.dictate.state.PendingSessionsModule
import net.devemperor.dictate.state.ReducerContext
import net.devemperor.dictate.testutil.FakeHostTextReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-cutting robustness tests for MULTI-source pending parts
 * (2026-07-12 pending-mechanics completion).
 *
 * With the headless completion fallback (ADR-0011) and hover-send
 * (widget-mode-parity spec) there are now three independent inflows into
 * `state.pendingSessions`, all funnelling through the same two reducer
 * arms:
 *
 *  1. **Hover-send / widget host-block** — IME insert returns
 *     `DeferredToPending` → `PipelineDone(committed=false)` →
 *     `Effect.AddPendingInsertSession` → [Action.PendingSessionsAction.AddOne].
 *  2. **Headless fallback** (no IME delegate at completion, ADR-0011) —
 *     service-side `PipelineDone(committed=false)` → same AddOne path.
 *  3. **Bind-reconciliation / recovery replay** — synthetic
 *     `PipelineDone(committed=false)` on IME bind (AddOne), plus
 *     [Action.PendingSessionsAction.Refresh] DB snapshots from the
 *     pending-flow subscriber.
 *
 * These tests pin the guarantees the mixed inflows must not break
 * (ADR-0009 / concurrent-recording spec §3.5):
 *
 *  - ordering is by `createdAt` (recording order), NOT arrival order;
 *  - `AddOne` is idempotent per sessionId across sources;
 *  - `AcceptAndInsertAll` (via [PendingPartsFlusher]) inserts every part
 *    in recording order and consumes each exactly once;
 *  - `DismissAll` clears the whole COMPLETED set regardless of source.
 *
 * @see net.devemperor.dictate.state.insertion.PendingPartsFlusher
 * @see docs/decisions/0009-pipeline-run-queue-serialized-concurrency.md
 */
class MultiSourcePendingPartsTest {

    private val module = PendingSessionsModule
    private fun ctx() = ReducerContext(global = DictateUiState.initial())

    /** COMPLETED pending part as produced by any committed=false inflow. */
    private fun part(id: String, createdAt: Long, text: String = "text-$id") = PendingSession(
        sessionId = id,
        status = SessionStatus.COMPLETED,
        transcribedText = text,
        createdAt = createdAt,
    )

    private fun addOne(
        state: PersistentList<PendingSession>,
        session: PendingSession,
    ): PersistentList<PendingSession> =
        module.reduce(state, Action.PendingSessionsAction.AddOne(session), ctx())
            ?.nextState ?: state

    // ── Ordering across mixed sources ────────────────────────────────

    @Test
    fun `arrival order from mixed sources does not break recording order`() {
        // Recording order: A (100) < B (200) < C (300). Arrival order is
        // scrambled: C lands first (reconciliation replay), then A
        // (headless fallback), then B (hover-send).
        var state = persistentListOf<PendingSession>()
        state = addOne(state, part("c", 300))
        state = addOne(state, part("a", 100))
        state = addOne(state, part("b", 200))

        val ordered = orderedCompletedParts(state)
        assertEquals(listOf("a", "b", "c"), ordered.map { it.sessionId })

        val flushable = pendingPartsToFlush(state)
        assertEquals(listOf("text-a", "text-b", "text-c"), flushable.map { it.text })
    }

    @Test
    fun `AddOne is idempotent for the same session arriving via two sources`() {
        // Race window: headless fallback dispatched PipelineDone AND the
        // bind-reconciliation replays the same session. The second AddOne
        // must be a reducer-null no-op (single entry keeps its text).
        var state = persistentListOf<PendingSession>()
        state = addOne(state, part("a", 100))

        val second = module.reduce(
            state,
            Action.PendingSessionsAction.AddOne(part("a", 100)),
            ctx(),
        )
        assertNull(second)
        assertEquals(1, state.size)
    }

    @Test
    fun `Refresh DB snapshot after live AddOnes keeps the canonical ordered set`() {
        // Recovery/pending-flow emits a full DB snapshot (Refresh replaces
        // the list). Parts previously added live must survive as long as
        // the DB snapshot carries them — order still by createdAt.
        var state = persistentListOf<PendingSession>()
        state = addOne(state, part("b", 200))
        state = addOne(state, part("a", 100))

        val snapshot = listOf(part("a", 100), part("b", 200), part("c", 300))
        val result = module.reduce(
            state,
            Action.PendingSessionsAction.Refresh(snapshot),
            ctx(),
        )
        val next = result!!.nextState
        assertEquals(3, next.size)
        assertEquals(
            listOf("a", "b", "c"),
            orderedCompletedParts(next).map { it.sessionId },
        )
    }

    // ── AcceptAndInsertAll spine over a mixed-source batch ───────────

    private class FakeIc : FakeInputConnection() {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = true
    }

    /** Minimal real-[InsertionService] fixture (PendingPartsFlusherTest pattern). */
    private class Fixture {
        val committed = mutableListOf<String>()
        val dispatched = mutableListOf<Action>()

        val service = InsertionService(
            ic = { HostTarget(FakeIc(), EditorInfo()) },
            guard = { true },
            committer = { _, text ->
                committed += text
                true
            },
            controlExecutor = { _, _ -> true },
            autoEnter = object : AutoEnterScheduler {
                override fun isActive() = false
                override fun schedule(text: String) = Unit
            },
            audit = object : InsertionAuditLog {
                override fun captureReplaced(ic: InputConnection): String? = null
                override fun record(
                    text: String, replaced: String?, editor: EditorInfo?,
                    source: InsertionSource, sessionIdOverride: String?,
                ) = Unit
            },
            recovery = object : RecoveryHandler {
                override fun notifyFocusLost() = Unit
                override fun resume(sessionId: String) = Unit
            },
            clipboard = object : ClipboardGateway {
                override fun performHostAction(ic: InputConnection, action: EditAction) = true
                override fun fallback(ic: InputConnection, action: EditAction) = Unit
            },
            textReader = FakeHostTextReader(),
        )

        val flusher = PendingPartsFlusher(service) { dispatched += it }
    }

    @Test
    fun `AcceptAndInsertAll flushes a mixed-source batch in recording order and consumes each part once`() {
        // State assembled from three sources, arrival scrambled.
        var state = persistentListOf<PendingSession>()
        state = addOne(state, part("c", 300))
        state = addOne(state, part("a", 100))
        state = addOne(state, part("b", 200))

        val f = Fixture()
        val inserted = f.flusher.flush(pendingPartsToFlush(state))

        assertEquals(3, inserted)
        // Recording order + D4 single-space joiner on parts 2..n.
        assertEquals(listOf("text-a", " text-b", " text-c"), f.committed)
        // Exactly one consume dispatch per part, in the same order.
        assertEquals(
            listOf("a", "b", "c"),
            f.dispatched.filterIsInstance<Action.PendingSessionsAction.AcceptAndInsert>()
                .map { it.sessionId },
        )

        // Applying the per-part consume dispatches empties the state
        // (the flusher's dispatch loop feeds back into this reducer arm).
        var after = state
        f.dispatched.filterIsInstance<Action.PendingSessionsAction.AcceptAndInsert>().forEach {
            after = module.reduce(after, it, ctx())?.nextState ?: after
        }
        assertTrue(orderedCompletedParts(after).isEmpty())
    }

    // ── DismissAll over a mixed-source batch ─────────────────────────

    @Test
    fun `DismissAll clears every COMPLETED part regardless of source and keeps resume entries`() {
        var state = persistentListOf<PendingSession>()
        state = addOne(state, part("a", 100))
        state = addOne(state, part("b", 200))
        state = addOne(state, part("c", 300))
        // A RECORDED resume-recording entry belongs to a different
        // producer surface and must survive DismissAll (spec §3.5).
        val recorded = PendingSession(
            sessionId = "r",
            status = SessionStatus.RECORDED,
            transcribedText = null,
            createdAt = 50,
        )
        state = state.add(recorded)

        val result = module.reduce(state, Action.PendingSessionsAction.DismissAll, ctx())
        val next = result!!.nextState
        assertEquals(listOf("r"), next.map { it.sessionId })
        // One persisted dismissal per removed COMPLETED part.
        assertEquals(
            setOf("a", "b", "c"),
            result.sideEffects
                .filterIsInstance<PendingSessionsModule.Effect.PersistDismissal>()
                .map { it.sessionId }
                .toSet(),
        )
    }
}
