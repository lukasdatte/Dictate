package net.devemperor.dictate.state.insertion

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import net.devemperor.dictate.core.FakeInputConnection
import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.testutil.FakeHostTextReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for [PendingPartsFlusher]. The real [InsertionService] is
 * wired with hand-rolled collaborator fakes (the [InsertionServiceTest]
 * pattern) so the flush spine — order, space-prefix policy, stop-on-failure,
 * per-part consume dispatch, and the PENDING_PART audit — is exercised
 * without Android/DB.
 */
class PendingPartsFlusherTest {

    private class FakeIc : FakeInputConnection() {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = true
    }

    private data class Audit(
        val text: String,
        val source: InsertionSource,
        val sessionIdOverride: String?,
    )

    /**
     * Collaborator fakes + the real service. `rejectText` makes the
     * committer fail for one exact committed string (space-prefix included)
     * so stop-on-failure can be exercised deterministically.
     */
    private class Fixture(var rejectText: String? = null) {
        val committed = mutableListOf<String>()
        val audits = mutableListOf<Audit>()
        var autoEnterScheduled = false
        val dispatched = mutableListOf<Action>()

        val service = InsertionService(
            ic = { HostTarget(FakeIc(), EditorInfo()) },
            guard = { true },
            committer = { _, text ->
                committed += text
                text != rejectText
            },
            controlExecutor = { _, _ -> true },
            autoEnter = object : AutoEnterScheduler {
                override fun isActive() = true
                override fun schedule(text: String) { autoEnterScheduled = true }
            },
            audit = object : InsertionAuditLog {
                override fun captureReplaced(ic: InputConnection): String? = null
                override fun record(
                    text: String, replaced: String?, editor: EditorInfo?,
                    source: InsertionSource, sessionIdOverride: String?,
                ) { audits += Audit(text, source, sessionIdOverride) }
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
    fun `flush inserts parts in order with single-space prefix on all but the first`() {
        val f = Fixture()
        val n = f.flusher.flush(
            listOf(
                PendingPart("s1", "alpha"),
                PendingPart("s2", "beta"),
                PendingPart("s3", "gamma"),
            ),
        )
        assertEquals(3, n)
        assertEquals(listOf("alpha", " beta", " gamma"), f.committed)
    }

    @Test
    fun `flush dispatches one AcceptAndInsert per part in order with the right sessionId`() {
        val f = Fixture()
        f.flusher.flush(listOf(PendingPart("s1", "a"), PendingPart("s2", "b")))
        assertEquals(
            listOf(
                Action.PendingSessionsAction.AcceptAndInsert("s1"),
                Action.PendingSessionsAction.AcceptAndInsert("s2"),
            ),
            f.dispatched,
        )
    }

    @Test
    fun `each part inserts with PENDING_PART source and its own sessionIdOverride`() {
        val f = Fixture()
        f.flusher.flush(listOf(PendingPart("s1", "a"), PendingPart("s2", "b")))
        assertEquals(
            listOf(
                Audit("a", InsertionSource.PENDING_PART, "s1"),
                Audit(" b", InsertionSource.PENDING_PART, "s2"),
            ),
            f.audits,
        )
    }

    @Test
    fun `PENDING_PART policy does not schedule auto-enter`() {
        val f = Fixture()
        f.flusher.flush(listOf(PendingPart("s1", "a")))
        assertFalse("catch-up parts must not auto-enter", f.autoEnterScheduled)
    }

    @Test
    fun `flush stops at the first failed commit — later parts unconsumed`() {
        // Reject the second part's committed text (space-prefixed).
        val f = Fixture(rejectText = " beta")
        val n = f.flusher.flush(
            listOf(
                PendingPart("s1", "alpha"),
                PendingPart("s2", "beta"),
                PendingPart("s3", "gamma"),
            ),
        )
        assertEquals("only the first part committed successfully", 1, n)
        // "gamma" is never attempted (flush broke on the failure).
        assertEquals(listOf("alpha", " beta"), f.committed)
        // Only the successful part is consumed — the failed and later parts
        // stay pending (nothing consumed without a commit).
        assertEquals(
            listOf(Action.PendingSessionsAction.AcceptAndInsert("s1")),
            f.dispatched,
        )
        assertTrue(f.dispatched.none { it == Action.PendingSessionsAction.AcceptAndInsert("s2") })
        assertTrue(f.dispatched.none { it == Action.PendingSessionsAction.AcceptAndInsert("s3") })
    }

    @Test
    fun `empty batch is a no-op returning zero`() {
        val f = Fixture()
        assertEquals(0, f.flusher.flush(emptyList()))
        assertTrue(f.committed.isEmpty())
        assertTrue(f.dispatched.isEmpty())
    }
}
