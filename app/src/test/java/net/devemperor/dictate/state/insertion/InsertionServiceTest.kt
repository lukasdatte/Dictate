package net.devemperor.dictate.state.insertion

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import net.devemperor.dictate.core.FakeInputConnection
import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.state.layout.EnterButtonRole
import net.devemperor.dictate.testutil.FakeHostTextReader
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
        var captureReplacedCalls = 0
        /** Ordered trace of capture/commit/record for ordering assertions. */
        val events = mutableListOf<String>()
        var focusLost = false
        var resumed: String? = null
        val controlOps = mutableListOf<ControlOp>()
        var controlAccept = true
        var hostActionHandled = true
        var fallbackRan = false
        val textReader = FakeHostTextReader()

        val service = InsertionService(
            ic = { live },
            guard = { canCommit },
            committer = { ic, text ->
                committed += ic to text
                events += "commit"
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
                override fun captureReplaced(ic: InputConnection): String? {
                    captureReplacedCalls += 1
                    events += "capture"
                    return null
                }
                override fun record(
                    text: String, replaced: String?, editor: EditorInfo?,
                    source: InsertionSource, sessionIdOverride: String?,
                ) { auditRecords += text; events += "record" }
            },
            recovery = object : RecoveryHandler {
                override fun notifyFocusLost() { focusLost = true }
                override fun resume(sessionId: String) { resumed = sessionId }
            },
            clipboard = object : ClipboardGateway {
                override fun performHostAction(ic: InputConnection, action: EditAction) = hostActionHandled
                override fun fallback(ic: InputConnection, action: EditAction) { fallbackRan = true }
            },
            textReader = textReader,
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

    @Test
    fun `editAction without live IC returns Failed, no host action, no fallback`() {
        val f = Fakes(live = null)
        val r = f.service.editAction(EditAction.COPY)
        assertEquals(InsertionResult.Failed, r)
        assertFalse(f.fallbackRan)
    }

    @Test
    fun `control returns Failed when executor rejects, op still attempted`() {
        val f = Fakes(live = HostTarget(FakeIc(), editor(1))).apply { controlAccept = false }
        val r = f.service.control(ControlOp.Backspace)
        assertEquals(InsertionResult.Failed, r)
        assertEquals(1, f.controlOps.size)
    }

    // ── DeleteGrapheme — the F-018 single home of backspace semantics ──

    @Test
    fun `DeleteGrapheme with active selection resolves to DeleteSelection`() {
        // User expectation: backspace with text selected deletes the selection —
        // the pre-fix raw deleteSurroundingText(1,0) deleted AROUND it instead.
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        f.textReader.selection = HostSelection(3, 7)

        f.service.control(ControlOp.DeleteGrapheme)

        assertEquals(listOf<ControlOp>(ControlOp.DeleteSelection), f.controlOps)
    }

    @Test
    fun `DeleteGrapheme with surrogate-pair emoji deletes both units`() {
        // Regression for F-018: the pre-fix ControlOp.Backspace deleted one
        // UTF-16 unit, leaving a corrupt lone high surrogate in the editor.
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        f.textReader.selection = HostSelection(5, 5)
        f.textReader.beforeCursor = "hi 😀" // "hi 😀"

        f.service.control(ControlOp.DeleteGrapheme)

        assertEquals(listOf<ControlOp>(ControlOp.DeleteSurrounding(2, 0)), f.controlOps)
    }

    @Test
    fun `DeleteGrapheme with ZWJ family emoji deletes the whole sequence`() {
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        f.textReader.selection = HostSelection(9, 9)
        f.textReader.beforeCursor = "x👨‍👩‍👧" // 8 UTF-16 units

        f.service.control(ControlOp.DeleteGrapheme)

        assertEquals(listOf<ControlOp>(ControlOp.DeleteSurrounding(8, 0)), f.controlOps)
    }

    @Test
    fun `DeleteGrapheme with ascii deletes exactly one unit`() {
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        f.textReader.selection = HostSelection(3, 3)
        f.textReader.beforeCursor = "abc"

        f.service.control(ControlOp.DeleteGrapheme)

        assertEquals(listOf<ControlOp>(ControlOp.DeleteSurrounding(1, 0)), f.controlOps)
    }

    @Test
    fun `DeleteGrapheme with no readable text degrades to raw Backspace`() {
        // Cursor at 0 / unreadable IC: fall back to the raw primitive so the
        // key is never a silent no-op.
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))

        f.service.control(ControlOp.DeleteGrapheme)

        assertEquals(listOf<ControlOp>(ControlOp.Backspace), f.controlOps)
    }

    @Test
    fun `DeleteGrapheme with unreadable selection still grapheme-deletes`() {
        // HostSelection.NONE is not a range — the grapheme path must still run.
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        f.textReader.selection = HostSelection.NONE
        f.textReader.beforeCursor = "x😀"

        f.service.control(ControlOp.DeleteGrapheme)

        assertEquals(listOf<ControlOp>(ControlOp.DeleteSurrounding(2, 0)), f.controlOps)
    }

    // ── CursorMove — the F-021 selection-safe, grapheme-clamped move ───

    @Test
    fun `CursorMove left with selection collapses to left edge instead of deleting it`() {
        // Pre-fix: commitText("", -1) DELETED the active selection.
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        f.textReader.selection = HostSelection(3, 7)

        f.service.control(ControlOp.CursorMove(-1))

        assertEquals(listOf<ControlOp>(ControlOp.SetSelection(3, 3)), f.controlOps)
    }

    @Test
    fun `CursorMove right with selection collapses to right edge`() {
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        f.textReader.selection = HostSelection(3, 7)

        f.service.control(ControlOp.CursorMove(1))

        assertEquals(listOf<ControlOp>(ControlOp.SetSelection(7, 7)), f.controlOps)
    }

    @Test
    fun `CursorMove left with reversed selection uses the true left edge`() {
        // start > end happens when the user dragged right-to-left.
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        f.textReader.selection = HostSelection(7, 3)

        f.service.control(ControlOp.CursorMove(-1))

        assertEquals(listOf<ControlOp>(ControlOp.SetSelection(3, 3)), f.controlOps)
    }

    @Test
    fun `CursorMove left steps over a whole emoji`() {
        // Pre-fix: one code unit per step — the caret landed inside the pair.
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        f.textReader.selection = HostSelection(5, 5)
        f.textReader.beforeCursor = "hi 😀"

        f.service.control(ControlOp.CursorMove(-1))

        assertEquals(listOf<ControlOp>(ControlOp.SetSelection(3, 3)), f.controlOps)
    }

    @Test
    fun `CursorMove left never goes below zero`() {
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        f.textReader.selection = HostSelection(1, 1)
        f.textReader.beforeCursor = "😀" // cluster larger than caret pos

        f.service.control(ControlOp.CursorMove(-1))

        assertEquals(listOf<ControlOp>(ControlOp.SetSelection(0, 0)), f.controlOps)
    }

    @Test
    fun `CursorMove right without selection keeps the legacy nudge`() {
        // Rightward grapheme scan needs text-after-cursor (not exposed);
        // documented fallback is the legacy relative commit (offset 2).
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        f.textReader.selection = HostSelection(5, 5)

        f.service.control(ControlOp.CursorMove(1))

        assertEquals(listOf<ControlOp>(ControlOp.CursorNudge(2)), f.controlOps)
    }

    @Test
    fun `CursorMove with unreadable selection falls back to the legacy nudge`() {
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        f.textReader.selection = HostSelection.NONE

        f.service.control(ControlOp.CursorMove(-1))

        assertEquals(listOf<ControlOp>(ControlOp.CursorNudge(-1)), f.controlOps)
    }

    @Test
    fun `CursorMove left with empty before-text falls back to the legacy nudge`() {
        // Caret readable but at position 0 with nothing before it: the host is
        // free to ignore the no-op nudge; a SetSelection(0,0) would be fine too
        // but the nudge preserves exact legacy behaviour.
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        f.textReader.selection = HostSelection(0, 0)
        f.textReader.beforeCursor = ""

        f.service.control(ControlOp.CursorMove(-1))

        assertEquals(listOf<ControlOp>(ControlOp.CursorNudge(-1)), f.controlOps)
    }

    // ── failure-path invariants (review edge-cases) ──

    @Test
    fun `auto-enter must not fire when the commit fails`() {
        // schedule lives inside the if(commit-succeeds) block — a refactor that
        // hoisted it would send Enter into a field that never received the text.
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)), autoEnterActive = true)
            .apply { rejectAll = true }

        val r = f.service.insert(
            InsertionRequest("x", InsertionSource.TRANSCRIPTION, InsertionPolicy.PIPELINE),
        )

        assertEquals(InsertionResult.Failed, r)
        assertFalse("auto-enter must not schedule on a failed commit", f.autoEnterScheduled)
    }

    @Test
    fun `resume failure with null session id surfaces focus-lost but does not resume`() {
        // The `sessionIdOverride?.let { resume(it) }` branch: no id ⇒ no resume.
        val f = Fakes(live = HostTarget(FakeIc(), editor(1))).apply { rejectAll = true }

        val r = f.service.insert(
            InsertionRequest(
                "hi", InsertionSource.TRANSCRIPTION, InsertionPolicy.RESEND,
                captured = HostTarget(FakeIc(), editor(1)), sessionIdOverride = null,
            ),
        )

        assertEquals(InsertionResult.ResumedAfterFailure, r)
        assertTrue(f.focusLost)
        assertNull("no session id ⇒ no resume job", f.resumed)
    }

    @Test
    fun `pipeline without live IC returns Failed, not Resumed`() {
        // PIPELINE has resumeOnFailure=false — the resend recovery must NOT kick in.
        val f = Fakes(live = null)

        val r = f.service.insert(
            InsertionRequest("hi", InsertionSource.TRANSCRIPTION, InsertionPolicy.PIPELINE),
        )

        assertEquals(InsertionResult.Failed, r)
        assertTrue(f.committed.isEmpty())
        assertFalse(f.focusLost)
    }

    @Test
    fun `resend with no live IC commits via the captured channel`() {
        // The typical real resend case: keyboard gone (live==null) but the
        // click-time captured handle still accepts writes.
        val captured = FakeIc()
        val f = Fakes(live = null)

        val r = f.service.insert(
            InsertionRequest(
                "hi", InsertionSource.TRANSCRIPTION, InsertionPolicy.RESEND,
                captured = HostTarget(captured, editor(1)), sessionIdOverride = "s1",
            ),
        )

        assertEquals(InsertionResult.Committed(Target.CAPTURED), r)
        assertSame(captured, f.committed.single().first)
    }

    // ── audit-suppression invariants ──

    @Test
    fun `empty text is not audited even with audit policy`() {
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        val r = f.service.insert(
            InsertionRequest("", InsertionSource.TRANSCRIPTION, InsertionPolicy.PIPELINE),
        )
        assertEquals(InsertionResult.Committed(Target.LIVE), r)
        assertTrue("empty text must not write an audit row", f.auditRecords.isEmpty())
    }

    @Test
    fun `null source disables auditing regardless of audit policy`() {
        // PIPELINE has audit=true, but source==null must skip capture AND record.
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        val r = f.service.insert(
            InsertionRequest("hi", null, InsertionPolicy.PIPELINE),
        )
        assertEquals(InsertionResult.Committed(Target.LIVE), r)
        assertTrue(f.auditRecords.isEmpty())
        assertEquals("captureReplaced must not run without a source", 0, f.captureReplacedCalls)
    }

    // ── PENDING_PART policy (R4, ADR-0009 / spec §3.5) ─────────────────

    @Test
    fun `PENDING_PART policy has the specced flags`() {
        val p = InsertionPolicy.PENDING_PART
        assertTrue("host-guard respected (never leak into a wrong window)", p.respectHostGuard)
        assertTrue("audited (one row per part)", p.audit)
        assertFalse("no auto-enter — catch-up parts, not live dictation", p.autoEnter)
        assertFalse("no resume-on-failure — a failure stops the flush", p.resumeOnFailure)
        assertFalse("not anchored to a captured IC", p.anchoredToCaptured)
        assertFalse("animation off — instant paste", p.animate)
    }

    @Test
    fun `selected text is captured before the commit`() {
        // Ordering matters: the replaced selection must be read before it is
        // overwritten by the commit.
        val f = Fakes(live = HostTarget(FakeIc(), editor(1)))
        f.service.insert(
            InsertionRequest("hi", InsertionSource.TRANSCRIPTION, InsertionPolicy.PIPELINE),
        )
        assertEquals(listOf("capture", "commit", "record"), f.events)
    }
}
