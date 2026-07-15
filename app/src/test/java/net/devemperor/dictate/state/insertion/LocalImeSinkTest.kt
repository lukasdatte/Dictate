package net.devemperor.dictate.state.insertion

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import net.devemperor.dictate.core.FakeInputConnection
import net.devemperor.dictate.database.entity.InsertionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local sink delegates byte-for-byte to [InsertionService] (§4.2) — and reports the one op with
 * no local meaning ([ControlOp.SelectWord]) as Unsupported instead of silently dropping it.
 */
class LocalImeSinkTest {

    private val controlOps = mutableListOf<ControlOp>()
    private val edits = mutableListOf<EditAction>()
    private val inserts = mutableListOf<String>()
    private val live = HostTarget(FakeInputConnection(), EditorInfo())

    private val insertion = InsertionService(
        ic = { live },
        guard = { true },
        committer = { _, text -> inserts += text; true },
        controlExecutor = { _, op -> controlOps += op; true },
        autoEnter = object : AutoEnterScheduler {
            override fun isActive() = false
            override fun schedule(text: String) {}
        },
        audit = object : InsertionAuditLog {
            override fun captureReplaced(ic: InputConnection): String? = null
            override fun record(text: String, replaced: String?, editor: EditorInfo?, source: InsertionSource, sessionIdOverride: String?) {}
        },
        recovery = object : RecoveryHandler {
            override fun notifyFocusLost() {}
            override fun resume(sessionId: String) {}
        },
        clipboard = object : ClipboardGateway {
            override fun performHostAction(ic: InputConnection, action: EditAction): Boolean { edits += action; return true }
            override fun fallback(ic: InputConnection, action: EditAction) {}
        },
        textReader = object : HostTextReader {
            override fun selection(ic: InputConnection) = HostSelection.NONE
            override fun textBeforeCursor(ic: InputConnection, maxChars: Int) = ""
        },
    )

    private val sink = LocalImeSink(insertion)

    @Test
    fun typeText_delegatesToInsert() {
        val result = sink.submit(KeyboardAction.TypeText(InsertionRequest("hi", InsertionSource.STATIC_PROMPT, InsertionPolicy.KEYSTROKE)))

        assertTrue(result is SubmitResult.Done)
        assertEquals(listOf("hi"), inserts)
    }

    @Test
    fun control_delegatesToControl() {
        sink.submit(KeyboardAction.Control(ControlOp.DeleteGrapheme))

        // DeleteGrapheme resolves to Backspace here (no readable text) — the point is it reached the executor.
        assertTrue(controlOps.isNotEmpty())
    }

    @Test
    fun edit_delegatesToEditAction() {
        sink.submit(KeyboardAction.Edit(EditAction.COPY))

        assertEquals(listOf(EditAction.COPY), edits)
    }

    @Test
    fun selectWord_isUnsupported_andNeverTouchesTheInsertionService() {
        val result = sink.submit(KeyboardAction.Control(ControlOp.SelectWord(-1)))

        assertEquals(SubmitResult.Unsupported(UnsupportedReason.OP_NOT_ROUTABLE), result)
        assertTrue("SelectWord must not reach the control executor locally", controlOps.isEmpty())
    }
}
