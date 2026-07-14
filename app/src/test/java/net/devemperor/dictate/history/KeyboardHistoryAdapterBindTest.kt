package net.devemperor.dictate.history

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import net.devemperor.dictate.R
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0014 / ADR-0019: binds a row of the in-keyboard history adapter against the
 * real inflated `item_keyboard_history` layout (via the extracted `bindRow`, so the
 * Paging machinery is not needed). Verifies the pending marker, the insert callback,
 * the text-less disable gate, and the per-row "Send to Windows" slot — GONE when no
 * PC is paired, VISIBLE + wired when one is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardHistoryAdapterBindTest {

    private lateinit var ctx: Context
    private val inserts = mutableListOf<Pair<String, Boolean>>()
    private val sends = mutableListOf<Pair<String, Boolean>>()
    private val details = mutableListOf<String>()
    private val callback = object : KeyboardHistoryAdapter.Callback {
        override fun onInsert(session: SessionEntity, pending: Boolean) {
            inserts += session.id to pending
        }

        override fun onSendToWindows(session: SessionEntity, pending: Boolean) {
            sends += session.id to pending
        }

        override fun onOpenDetail(session: SessionEntity) {
            details += session.id
        }
    }
    private lateinit var adapter: KeyboardHistoryAdapter
    private lateinit var parent: FrameLayout

    @Before
    fun setUp() {
        ctx = ContextThemeWrapper(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            R.style.Theme_Dictate,
        )
        adapter = KeyboardHistoryAdapter(callback)
        parent = FrameLayout(ctx)
    }

    /** An adapter that shows the send slot (a PC is paired). */
    private fun pairedAdapter() = KeyboardHistoryAdapter(callback, windowsTargetPaired = true)

    private fun holder(): KeyboardHistoryAdapter.ViewHolder {
        val view = LayoutInflater.from(ctx).inflate(R.layout.item_keyboard_history, parent, false)
        return KeyboardHistoryAdapter.ViewHolder(view)
    }

    private fun session(
        id: String = "s",
        status: SessionStatus = SessionStatus.COMPLETED,
        insertedAt: Long? = null,
        finalOutput: String? = "out",
        input: String? = null,
    ) = SessionEntity(
        id = id, type = "RECORDING", createdAt = 1L,
        targetAppPackage = null, language = null, audioFilePath = null,
        status = status.name, finalOutputText = finalOutput, inputText = input, insertedAt = insertedAt,
    )

    @Test
    fun `pending row shows the marker`() {
        val h = holder()
        adapter.bindRow(h, session(insertedAt = null))
        assertEquals(View.VISIBLE, h.pendingDot.visibility)
    }

    @Test
    fun `already-inserted row hides the marker`() {
        val h = holder()
        adapter.bindRow(h, session(insertedAt = 123L))
        assertEquals(View.GONE, h.pendingDot.visibility)
    }

    @Test
    fun `insert button forwards session and pending flag`() {
        val h = holder()
        adapter.bindRow(h, session(id = "s1", insertedAt = null))
        assertTrue(h.insertButton.isEnabled)
        h.insertButton.performClick()
        assertEquals(listOf("s1" to true), inserts)
    }

    @Test
    fun `row body tap opens the detail view`() {
        val h = holder()
        adapter.bindRow(h, session(id = "s5"))
        h.itemView.performClick()
        assertEquals(listOf("s5"), details)
    }

    @Test
    fun `text-less row disables the insert button and fires no callback`() {
        val h = holder()
        adapter.bindRow(h, session(status = SessionStatus.RECORDING, finalOutput = null, input = null))
        assertFalse(h.insertButton.isEnabled)
        h.insertButton.performClick()
        assertTrue(inserts.isEmpty())
    }

    @Test
    fun `preview falls back to input text when final output is null`() {
        val h = holder()
        adapter.bindRow(h, session(finalOutput = null, input = "transcript"))
        assertEquals("transcript", h.previewView.text.toString())
    }

    @Test
    fun `send slot is gone when no PC is paired`() {
        val h = holder()
        adapter.bindRow(h, session(insertedAt = null))
        assertEquals(View.GONE, h.sendButton.visibility)
        // No callback wiring on an unpaired install, even for a text-bearing row.
        h.sendButton.performClick()
        assertTrue(sends.isEmpty())
    }

    @Test
    fun `send slot is visible and forwards session and pending flag when paired`() {
        val paired = pairedAdapter()
        val h = holder()
        paired.bindRow(h, session(id = "s1", insertedAt = null))
        assertEquals(View.VISIBLE, h.sendButton.visibility)
        assertTrue(h.sendButton.isEnabled)
        h.sendButton.performClick()
        assertEquals(listOf("s1" to true), sends)
    }

    @Test
    fun `send slot forwards pending=false for an already-inserted row`() {
        val paired = pairedAdapter()
        val h = holder()
        paired.bindRow(h, session(id = "s2", insertedAt = 123L))
        h.sendButton.performClick()
        assertEquals(listOf("s2" to false), sends)
    }

    @Test
    fun `text-less row disables the send button and fires no callback when paired`() {
        val paired = pairedAdapter()
        val h = holder()
        paired.bindRow(h, session(status = SessionStatus.RECORDING, finalOutput = null, input = null))
        assertEquals(View.VISIBLE, h.sendButton.visibility)
        assertFalse(h.sendButton.isEnabled)
        h.sendButton.performClick()
        assertTrue(sends.isEmpty())
    }
}
