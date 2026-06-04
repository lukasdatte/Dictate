package net.devemperor.dictate.state.insertion

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import net.devemperor.dictate.core.FakeInputConnection
import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.state.layout.EnterButtonRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Target-selection + fallback-ladder tests for [InsertionService]. The service
 * is pure Kotlin; every collaborator is a hand-rolled fake so the spine
 * (live → captured → resume, host-guard, audit, auto-enter) is fully
 * exercised without Android/DB.
 */
class InsertionServiceTest {

    // ── fakes ──

    private class FakeIc(private val accept: Boolean = true) : FakeInputConnection() {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = accept
    }

    private fun editor(fieldId: Int, pkg: String = "com.example"): EditorInfo =
        EditorInfo().apply { this.fieldId = fieldId; packageName = pkg }

    private class Fakes(
        var live: HostTarget? = null,
        var canCommit: Boolean = true,
        var autoEnterActive: Boolean = false,
    ) {
        val committed = mutableListOf<Pair<InputConnection, String>>()
        var committerAcceptOn: InputConnection? = null // null = accept all
        var rejectAll = false

        var autoEnterScheduled = false
        val auditRecords = mutableListOf<String>()
        var focusLost = false
        var resumed: String? = null
        val controlOps = mutableListOf<ControlOp>()
        var controlAccept = true
        var hostActionHandled = true
        var fallbackRan = false

        val service = InsertionService(
            ic = { live },
            guard = { canCommit },
            committer = { ic, text ->
                committed += ic to text
                when {
                    rejectAll -> false
                    committerAcceptOn != null -> committerAcceptOn === ic
                    else -> true
                }
            },
            controlExecutor = { _, op -> controlOps += op; controlAccept },
            autoEnter = object : AutoEnterScheduler {
                override fun isActive() = autoEnterActive
                override fun schedule(text: String) { autoEnterScheduled = true }
            },
            audit = object : InsertionAuditLog {
                override fun captureReplaced(ic: InputConnection): String? = null
                override fun record(
                    text: String, replaced: String?, editor: EditorInfo?,
                    source: InsertionSource, sessionIdOverride: String?,
                ) { auditRecords += text }
            },
            recovery = object : RecoveryHandler {
                override fun notifyFocusLost() { focusLost = true }
                override fun resume(sessionId: String) { resumed = sessionId }
            },
            clipboard = object : ClipboardGateway {
                override fun performHostAction(ic: InputConnection, action: EditAction) = hostActionHandled
                override fun fallback(ic: InputConnection, action: EditAction) { fallbackRan = true }
            },
        )
    }

    // ── pipeline ──

    @Test
    fun `pipeline commits via live and audits and auto-enters`() {
        val ic = FakeIc()
        val f = Fakes(live = HostTarget(ic, editor(1)), autoEnterActive = true)

        val r = f.service.insert(
            InsertionRequest("hello", InsertionSource.TRANSCRIPTION, InsertionPolicy.PIPELINE, sessionIdOverride = "s1"),
        )

        assertEquals(InsertionResult.Committed(Target.LIVE), r)
        assertEquals(1, f.committed.size)
        assertSame(ic, f.committed[0].first)
        assertEquals(listOf("hello"), f.auditRecords)
        assertTrue(f.autoEnterScheduled)
    }

    @Test
    fun `pipeline host-guard blocked defers to pending, no commit`() {
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)), canCommit = false)

        val r = f.service.insert(
            InsertionRequest("hello", InsertionSource.TRANSCRIPTION, InsertionPolicy.PIPELINE, sessionIdOverride = "s1"),
        )

        assertEquals(InsertionResult.DeferredToPending, r)
        assertTrue(f.committed.isEmpty())
        assertTrue(f.auditRecords.isEmpty())
    }

    @Test
    fun `pipeline no auto-enter when inactive`() {
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)), autoEnterActive = false)
        f.service.insert(InsertionRequest("x", InsertionSource.TRANSCRIPTION, InsertionPolicy.PIPELINE))
        assertFalse(f.autoEnterScheduled)
    }

    // ── resend ──

    @Test
    fun `resend same editor commits via live`() {
        val live = FakeIc()
        val f = Fakes(live = HostTarget(live, editor(1)))

        val r = f.service.insert(
            InsertionRequest(
                "hi", InsertionSource.TRANSCRIPTION, InsertionPolicy.RESEND,
                captured = HostTarget(FakeIc(), editor(1)), sessionIdOverride = "s1",
            ),
        )

        assertEquals(InsertionResult.Committed(Target.LIVE), r)
        assertSame(live, f.committed.single().first)
    }

    @Test
    fun `resend different editor commits via captured`() {
        val live = FakeIc()
        val captured = FakeIc()
        val f = Fakes(live = HostTarget(live, editor(1)))

        val r = f.service.insert(
            InsertionRequest(
                "hi", InsertionSource.TRANSCRIPTION, InsertionPolicy.RESEND,
                captured = HostTarget(captured, editor(99)), sessionIdOverride = "s1",
            ),
        )

        assertEquals(InsertionResult.Committed(Target.CAPTURED), r)
        assertSame(captured, f.committed.single().first)
    }

    @Test
    fun `resend both ICs fail surfaces focus-lost and resumes`() {
        val live = FakeIc()
        val captured = FakeIc()
        val f = Fakes(live = HostTarget(live, editor(1))).apply { rejectAll = true }

        val r = f.service.insert(
            InsertionRequest(
                "hi", InsertionSource.TRANSCRIPTION, InsertionPolicy.RESEND,
                captured = HostTarget(captured, editor(1)), sessionIdOverride = "s1",
            ),
        )

        assertEquals(InsertionResult.ResumedAfterFailure, r)
        assertEquals(2, f.committed.size) // tried live then captured
        assertTrue(f.focusLost)
        assertEquals("s1", f.resumed)
    }

    @Test
    fun `resend without captured anchor skips live and resumes`() {
        // Anchored resend with no captured IC must NOT commit into whatever is
        // focused now — it skips straight to the resume fallback (preserves the
        // pre-refactor ResendInsertStrategy behaviour for null capturedIc).
        val live = FakeIc()
        val f = Fakes(live = HostTarget(live, editor(1)))

        val r = f.service.insert(
            InsertionRequest(
                "hi", InsertionSource.TRANSCRIPTION, InsertionPolicy.RESEND,
                captured = null, sessionIdOverride = "s1",
            ),
        )

        assertEquals(InsertionResult.ResumedAfterFailure, r)
        assertTrue("live IC must not be used without an anchor", f.committed.isEmpty())
        assertTrue(f.focusLost)
        assertEquals("s1", f.resumed)
    }

    // ── keystroke ──

    @Test
    fun `keystroke ignores host-guard, no audit, no resume`() {
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)), canCommit = false)

        val r = f.service.insert(InsertionRequest(" ", null, InsertionPolicy.KEYSTROKE))

        // respectHostGuard=false → commit proceeds despite canCommit=false
        assertEquals(InsertionResult.Committed(Target.LIVE), r)
        assertTrue(f.auditRecords.isEmpty())
    }

    @Test
    fun `keystroke dead IC returns Failed, no resume`() {
        val f = Fakes(live = HostTarget(FakeIc(), editor(1))).apply { rejectAll = true }
        val r = f.service.insert(InsertionRequest(" ", null, InsertionPolicy.KEYSTROKE))
        assertEquals(InsertionResult.Failed, r)
        assertNull(f.resumed)
    }

    @Test
    fun `no live IC and no captured returns Failed for keystroke`() {
        val f = Fakes(live = null)
        val r = f.service.insert(InsertionRequest(" ", null, InsertionPolicy.KEYSTROKE))
        assertEquals(InsertionResult.Failed, r)
    }

    // ── control + editAction ──

    @Test
    fun `control executes op via live IC`() {
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        val r = f.service.control(ControlOp.Enter(EnterButtonRole.NEWLINE, 0))
        assertEquals(InsertionResult.Committed(Target.LIVE), r)
        assertEquals(1, f.controlOps.size)
    }

    @Test
    fun `control without IC returns Failed`() {
        val f = Fakes(live = null)
        assertEquals(InsertionResult.Failed, f.service.control(ControlOp.Backspace))
    }

    @Test
    fun `editAction uses host soft-api, no fallback when handled`() {
        val f = Fakes(live = HostTarget(FakeIc(), editor(1))).apply { hostActionHandled = true }
        f.service.editAction(EditAction.COPY)
        assertFalse(f.fallbackRan)
    }

    @Test
    fun `editAction falls back when host rejects`() {
        val f = Fakes(live = HostTarget(FakeIc(), editor(1))).apply { hostActionHandled = false }
        f.service.editAction(EditAction.PASTE)
        assertTrue(f.fallbackRan)
    }
}
