package net.devemperor.dictate.keyboard

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.core.FakeInputConnection
import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.state.insertion.AutoEnterScheduler
import net.devemperor.dictate.state.insertion.ClipboardGateway
import net.devemperor.dictate.state.insertion.ControlOp
import net.devemperor.dictate.state.insertion.EditAction
import net.devemperor.dictate.state.insertion.HostTarget
import net.devemperor.dictate.state.insertion.InsertionAuditLog
import net.devemperor.dictate.state.insertion.InsertionService
import net.devemperor.dictate.state.insertion.RecoveryHandler
import net.devemperor.dictate.testutil.FakeHostTextReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Swipe-select offset tests for [BackspaceSwipeHandler] — the F-023
 * regression: `ExtractedText.selectionStart/End` are offsets *within* the
 * extracted window (relative to `ExtractedText.startOffset`), while
 * `InputConnection.setSelection` takes *absolute* document offsets. Hosts
 * with large documents legitimately return a windowed extract with
 * `startOffset > 0`; without adding it, swipe-select highlights — and on
 * release DELETES — a range shifted toward the document start.
 *
 * Robolectric K-4 justified (real `MotionEvent` / `ViewConfiguration` /
 * display metrics); K-1 handwritten fakes (no mocking framework).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackspaceSwipeHandlerTest {

    private lateinit var ctx: Context
    private lateinit var button: View

    /**
     * K-1 fake: serves a configurable [ExtractedText] window and records
     * every `setSelection` call.
     */
    private class WindowedIc(
        text: String,
        private val startOffset: Int,
        selectionInWindow: Int,
    ) : FakeInputConnection() {
        private val et = ExtractedText().also {
            it.text = text
            it.startOffset = startOffset
            it.selectionStart = selectionInWindow
            it.selectionEnd = selectionInWindow
        }
        val selections = mutableListOf<Pair<Int, Int>>()

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText = et
        override fun setSelection(start: Int, end: Int): Boolean {
            selections += start to end
            return true
        }
    }

    private val controlOps = mutableListOf<ControlOp>()

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        button = View(ctx)
        controlOps.clear()
    }

    /** Minimal real service whose executor records the resolved control ops. */
    private fun recordingInsertionService(connection: InputConnection): InsertionService =
        InsertionService(
            ic = { HostTarget(connection, null as EditorInfo?) },
            guard = { true },
            committer = { _, _ -> true },
            controlExecutor = { _, op -> controlOps += op; true },
            autoEnter = object : AutoEnterScheduler {
                override fun isActive() = false
                override fun schedule(text: String) {}
            },
            audit = object : InsertionAuditLog {
                override fun captureReplaced(ic: InputConnection): String? = null
                override fun record(
                    text: String, replaced: String?, editor: EditorInfo?,
                    source: InsertionSource, sessionIdOverride: String?,
                ) {}
            },
            recovery = object : RecoveryHandler {
                override fun notifyFocusLost() {}
                override fun resume(sessionId: String) {}
            },
            clipboard = object : ClipboardGateway {
                override fun performHostAction(ic: InputConnection, action: EditAction) = true
                override fun fallback(ic: InputConnection, action: EditAction) {}
            },
            textReader = FakeHostTextReader(),
        )

    private fun handler(ic: InputConnection) = BackspaceSwipeHandler(
        inputConnectionProvider = { ic },
        insertionService = { recordingInsertionService(ic) },
        vibrate = {},
        onDeleteCancelled = {},
    )

    private fun motionEvent(action: Int, x: Float): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, x, 0f, 0)

    /** DOWN far right, then a long swipe left — activates + maxes the steps. */
    private fun swipeFarLeft(h: BackspaceSwipeHandler) {
        h.onTouch(button, motionEvent(MotionEvent.ACTION_DOWN, 500f))
        h.onTouch(button, motionEvent(MotionEvent.ACTION_MOVE, 0f))
    }

    // ── F-023 — startOffset must shift every setSelection to absolute ──

    @Test
    fun `swipe-select adds startOffset to the selection range (windowed extract)`() {
        // Window "hello world" starts at absolute offset 100; caret at window
        // offset 11 (= absolute 111). A max swipe selects back to window
        // offset 0 (= absolute 100). Pre-fix code passed the window-relative
        // (0, 11) — selecting (and then deleting) 100 chars too far left.
        val ic = WindowedIc("hello world", startOffset = 100, selectionInWindow = 11)

        swipeFarLeft(handler(ic))

        assertTrue("swipe must have selected something", ic.selections.isNotEmpty())
        assertEquals(100 to 111, ic.selections.last())
    }

    @Test
    fun `swipe-select with full extract (startOffset zero) keeps legacy offsets`() {
        val ic = WindowedIc("hello world", startOffset = 0, selectionInWindow = 11)

        swipeFarLeft(handler(ic))

        assertEquals(0 to 11, ic.selections.last())
    }

    @Test
    fun `release with selection routes DeleteSelection through the service`() {
        val ic = WindowedIc("hello world", startOffset = 100, selectionInWindow = 11)
        val h = handler(ic)

        swipeFarLeft(h)
        h.onTouch(button, motionEvent(MotionEvent.ACTION_UP, 0f))

        assertEquals(listOf<ControlOp>(ControlOp.DeleteSelection), controlOps)
    }

    @Test
    fun `release without steps restores the absolute base cursor`() {
        // Activate the swipe (small move past the slop) but stay below one
        // step, then lift: the caret must be restored at the ABSOLUTE base.
        val ic = WindowedIc("hello world", startOffset = 100, selectionInWindow = 11)
        val h = handler(ic)

        h.onTouch(button, motionEvent(MotionEvent.ACTION_DOWN, 500f))
        h.onTouch(button, motionEvent(MotionEvent.ACTION_MOVE, 480f)) // past slop, < 1 step
        h.onTouch(button, motionEvent(MotionEvent.ACTION_UP, 480f))

        assertTrue(ic.selections.isNotEmpty())
        assertEquals(111 to 111, ic.selections.last())
    }

    @Test
    fun `negative startOffset (unknown) is clamped to zero`() {
        val ic = WindowedIc("hello world", startOffset = -1, selectionInWindow = 11)

        swipeFarLeft(handler(ic))

        assertEquals(0 to 11, ic.selections.last())
    }
}
