package net.devemperor.dictate.companion.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.data.SqlDelightHistoryRepository
import net.devemperor.dictate.companion.domain.DispatchService
import net.devemperor.dictate.companion.domain.model.Device
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.fakes.FakeClipboard
import net.devemperor.dictate.companion.fakes.FakeTextInserter
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.companion.ui.history.HistoryEvent
import net.devemperor.dictate.companion.ui.history.HistoryViewModel
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.protocol.SessionUpsert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The history screen's logic, without Compose.
 *
 * Everything that can be *wrong* about this screen — which rows a search returns, where a page
 * boundary falls, what a re-insert does to a row — lives in the view model and is asserted here. The
 * `@Composable` above it is layout, and a test of it could only restate the layout.
 *
 * `Dispatchers.Unconfined` runs each `launch` to completion inline, so the state can be read on the
 * next line without a test dispatcher (and without dragging kotlinx-coroutines-test — pinned to 1.7.3
 * for `:app` — onto this module's classpath).
 */
class HistoryViewModelTest {

    private val database = CompanionDatabase.inMemory()
    private val devices = SqlDelightDeviceRepository(database)
    private val history = SqlDelightHistoryRepository(database)
    private val inserter = FakeTextInserter()
    private val clipboard = FakeClipboard()
    private val clock = MutableClock()
    private val dispatch = DispatchService(inserter, history, devices, clock)

    private fun viewModel(canInsert: Boolean = true, pageSize: Int = 2) = HistoryViewModel(
        history = history,
        dispatch = dispatch,
        clipboard = clipboard,
        scope = CoroutineScope(Dispatchers.Unconfined),
        canInsert = canInsert,
        pageSize = pageSize,
    )

    @Before
    fun setUp() {
        devices.save(Device(DEVICE_ID, "Pixel 8", "hash", pairedAt = 1L))
    }

    @Test
    fun anEmptyHistory_isAnEmptyState_notAnEmptySearchResult() {
        val state = viewModel().state.value

        assertTrue(state.isEmpty)
        assertEquals(0, state.totalCount)
        assertFalse(state.hasNextPage)
    }

    @Test
    fun rowsAreNewestFirst_andPagedByPageSize() {
        seed("s1", "first", createdAt = 10)
        seed("s2", "second", createdAt = 20)
        seed("s3", "third", createdAt = 30)

        val viewModel = viewModel(pageSize = 2)

        assertEquals(listOf("s3", "s2"), viewModel.state.value.rows.map { it.sessionId })
        assertEquals(3, viewModel.state.value.totalCount)
        assertTrue(viewModel.state.value.hasNextPage)

        viewModel.nextPage()
        assertEquals(listOf("s1"), viewModel.state.value.rows.map { it.sessionId })
        assertFalse(viewModel.state.value.hasNextPage)
        assertTrue(viewModel.state.value.hasPreviousPage)

        viewModel.previousPage()
        assertEquals(0, viewModel.state.value.page)
    }

    @Test
    fun aSearchAlwaysLandsOnPageZero() {
        repeat(5) { seed("s$it", "dictation $it", createdAt = 10L + it) }
        val viewModel = viewModel(pageSize = 2)
        viewModel.nextPage()
        assertEquals(1, viewModel.state.value.page)

        viewModel.search("dictation 0")

        // Staying on page 1 of a result set that has one page would show an empty screen.
        assertEquals(0, viewModel.state.value.page)
        assertEquals(listOf("s0"), viewModel.state.value.rows.map { it.sessionId })
        assertFalse("a search result is not the empty state", viewModel.state.value.isEmpty)
    }

    @Test
    fun aPageBeyondTheEnd_isClampedBackIntoRange() {
        repeat(3) { seed("s$it", "text $it", createdAt = 10L + it) }
        val viewModel = viewModel(pageSize = 2)
        viewModel.nextPage()
        assertEquals(1, viewModel.state.value.page)

        // The rows behind the current page went away (un-paired device, cascade). Refreshing must not
        // leave the user staring at an empty page with no way back.
        devices.revoke(DEVICE_ID)
        viewModel.refresh()

        assertEquals(0, viewModel.state.value.page)
        assertEquals(0, viewModel.state.value.totalCount)
    }

    @Test
    fun reinsert_goesThroughTheOneInsertPath_andUpdatesTheRow() {
        seed("s1", "please insert me", createdAt = 10)
        inserter.nextOutcome = InsertionOutcome.CLIPBOARD_ONLY
        val viewModel = viewModel()

        viewModel.reinsert("s1")

        assertEquals(listOf("please insert me"), inserter.inserted)
        assertEquals(HistoryEvent.Reinserted(InsertionOutcome.CLIPBOARD_ONLY), viewModel.state.value.event)
        val row = viewModel.state.value.rows.single()
        assertEquals(InsertionOutcome.CLIPBOARD_ONLY, row.lastOutcome)
        assertTrue("an insert makes a synced row a dispatched one", row.dispatched)
    }

    @Test
    fun reinsert_ofARowThatIsGone_saysSo() {
        val viewModel = viewModel()

        viewModel.reinsert("never-existed")

        assertEquals(HistoryEvent.Gone, viewModel.state.value.event)
        assertEquals(emptyList<String>(), inserter.inserted)
    }

    @Test
    fun copy_touchesTheClipboardOnly_notTheKeyboard() {
        seed("s1", "just copy me", createdAt = 10)
        val viewModel = viewModel()

        viewModel.copy("s1")

        assertEquals(listOf("just copy me"), clipboard.writes)
        assertEquals("no Ctrl+V — the user will paste it themselves", emptyList<String>(), inserter.inserted)
        assertEquals(HistoryEvent.Copied, viewModel.state.value.event)

        viewModel.consumeEvent()
        assertNull(viewModel.state.value.event)
    }

    @Test
    fun withoutAnInserter_theScreenKnowsTheButtonMustBeDisabled() {
        assertFalse(viewModel(canInsert = false).state.value.canInsert)
    }

    private fun seed(sessionId: String, text: String, createdAt: Long) {
        history.upsert(
            deviceId = DEVICE_ID,
            item = SessionUpsert(sessionId, text, createdAt, SessionOriginWire.KEYBOARD, dispatched = false),
            receivedAt = createdAt,
        )
    }

    private companion object {
        const val DEVICE_ID = "test-device-0001"
    }
}
