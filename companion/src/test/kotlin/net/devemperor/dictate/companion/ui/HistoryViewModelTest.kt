package net.devemperor.dictate.companion.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.DesktopHistoryEntry
import net.devemperor.dictate.companion.data.DesktopSessionRepository
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.data.SqlDelightHistoryRepository
import net.devemperor.dictate.companion.data.TranscriptionRecord
import net.devemperor.dictate.companion.domain.DispatchService
import net.devemperor.dictate.companion.domain.model.Device
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.fakes.FakeClipboard
import net.devemperor.dictate.companion.fakes.FakeTextInserter
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.companion.ui.history.HistoryEvent
import net.devemperor.dictate.companion.ui.history.HistoryFilter
import net.devemperor.dictate.companion.ui.history.HistoryItem
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
 * The unified history screen's logic, without Compose (desktop-host.md §9.3).
 *
 * Everything that can be *wrong* about this screen — which rows a merged page holds and in what
 * order, what each filter hides, and which re-insert path a row takes (phone rows through
 * [DispatchService], desktop rows straight through the inserter) — lives in the view model and is
 * asserted here. The `@Composable` above it is layout, and a test of it could only restate the
 * layout.
 *
 * `Dispatchers.Unconfined` runs each `launch` to completion inline, so the state can be read on the
 * next line without a test dispatcher (and without dragging kotlinx-coroutines-test — pinned to
 * 1.7.3 for `:app` — onto this module's classpath).
 */
class HistoryViewModelTest {

    private val database = CompanionDatabase.inMemory()
    private val devices = SqlDelightDeviceRepository(database)
    private val history = SqlDelightHistoryRepository(database)
    private val desktopSessions = DesktopSessionRepository(database)
    private val inserter = FakeTextInserter()
    private val clipboard = FakeClipboard()
    private val clock = MutableClock()
    private val dispatch = DispatchService(inserter, history, devices, clock)

    private fun viewModel(
        canInsert: Boolean = true,
        pageSize: Int = 2,
        desktop: DesktopSessionRepository? = desktopSessions,
    ) = HistoryViewModel(
        history = history,
        dispatch = dispatch,
        desktopSessions = desktop,
        inserter = inserter,
        clipboard = clipboard,
        clock = clock,
        scope = CoroutineScope(Dispatchers.Unconfined),
        canInsert = canInsert,
        pageSize = pageSize,
    )

    @Before
    fun setUp() {
        devices.save(Device(DEVICE_ID, "Pixel 8", "hash", pairedAt = 1L))
    }

    // ── merged list ──────────────────────────────────────────────────────────────────────────

    @Test
    fun anEmptyHistory_isAnEmptyState_notAnEmptySearchResult() {
        val state = viewModel().state.value

        assertTrue(state.isEmpty)
        assertEquals(0, state.totalCount)
        assertFalse(state.hasNextPage)
        assertEquals(HistoryFilter.ALL, state.filter)
    }

    @Test
    fun mergedRows_interleaveBothOrigins_newestFirst_andPagedByPageSize() {
        seedPhone("p1", "phone one", createdAt = 10)
        seedDesktopTake("d1", createdAt = 20, transcript = "raw one", output = "Desktop one.")
        seedPhone("p2", "phone two", createdAt = 30)
        seedDesktopTake("d2", createdAt = 40, transcript = "raw two", output = "Desktop two.")

        val viewModel = viewModel(pageSize = 2)

        // Page 0 holds the newest two rows regardless of which host produced them.
        assertEquals(listOf("d2", "p2"), viewModel.state.value.rows.map { it.sessionId })
        assertEquals(4, viewModel.state.value.totalCount)
        assertTrue(viewModel.state.value.hasNextPage)

        viewModel.nextPage()
        assertEquals(listOf("d1", "p1"), viewModel.state.value.rows.map { it.sessionId })
        assertFalse(viewModel.state.value.hasNextPage)
        assertTrue(viewModel.state.value.hasPreviousPage)

        viewModel.previousPage()
        assertEquals(0, viewModel.state.value.page)
    }

    @Test
    fun rows_carryTheirOrigin_soTheScreenCanRenderEachShape() {
        seedPhone("p1", "phone text", createdAt = 10)
        seedDesktopTake("d1", createdAt = 20, transcript = "raw", output = "Desktop text.")

        val rows = viewModel(pageSize = 10).state.value.rows

        assertTrue(rows.single { it.sessionId == "p1" } is HistoryItem.Phone)
        val desktop = rows.single { it.sessionId == "d1" } as HistoryItem.Desktop
        assertEquals("raw", desktop.entry.transcriptText)
        assertEquals("Desktop text.", desktop.text)
    }

    // ── filter ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun phoneFilter_hidesDesktopRows_andCountsOnlyPhoneRows() {
        seedPhone("p1", "phone", createdAt = 10)
        seedDesktopTake("d1", createdAt = 20, transcript = "raw", output = "Desktop.")
        val viewModel = viewModel(pageSize = 10)

        viewModel.setFilter(HistoryFilter.PHONE)

        assertEquals(listOf("p1"), viewModel.state.value.rows.map { it.sessionId })
        assertEquals(1, viewModel.state.value.totalCount)
    }

    @Test
    fun desktopFilter_hidesPhoneRows_andCountsOnlyDesktopRows() {
        seedPhone("p1", "phone", createdAt = 10)
        seedDesktopTake("d1", createdAt = 20, transcript = "raw", output = "Desktop.")
        val viewModel = viewModel(pageSize = 10)

        viewModel.setFilter(HistoryFilter.DESKTOP)

        assertEquals(listOf("d1"), viewModel.state.value.rows.map { it.sessionId })
        assertEquals(1, viewModel.state.value.totalCount)
    }

    @Test
    fun changingTheFilter_landsOnPageZero() {
        repeat(5) { seedPhone("p$it", "phone $it", createdAt = 10L + it) }
        val viewModel = viewModel(pageSize = 2)
        viewModel.nextPage()
        assertEquals(1, viewModel.state.value.page)

        viewModel.setFilter(HistoryFilter.PHONE)

        // Page 1 of "All" and page 1 of "Phone" are different result sets — keeping the offset is nonsense.
        assertEquals(0, viewModel.state.value.page)
    }

    @Test
    fun theDesktopSide_showsNeitherPhoneSyncNorRefinementTakes() {
        seedDesktopTake("d1", createdAt = 10, transcript = "local raw", output = "Local take.")
        seedPhone("p1", "phone text", createdAt = 20)
        // An S2 review-refinement take must not surface — its transcript belongs to the parent (§8.3).
        desktopSessions.createRefinementSession(
            id = "d2", createdAt = 30, language = "de",
            audioFilePath = null, audioFilePathsJson = "[]", durationSeconds = 0L,
            parentSessionId = "d1",
        )
        val viewModel = viewModel(pageSize = 10)

        viewModel.setFilter(HistoryFilter.DESKTOP)

        assertEquals(listOf("d1"), viewModel.state.value.rows.map { it.sessionId })
        assertEquals(1, viewModel.state.value.totalCount)
    }

    // ── search / paging edges ────────────────────────────────────────────────────────────────

    @Test
    fun aSearch_filtersBothSources_andAlwaysLandsOnPageZero() {
        seedPhone("p1", "the meeting notes", createdAt = 10)
        seedPhone("p2", "unrelated", createdAt = 20)
        seedDesktopTake("d1", createdAt = 30, transcript = "raw", output = "More meeting notes.")
        seedDesktopTake("d2", createdAt = 40, transcript = "raw", output = "Something else.")
        val viewModel = viewModel(pageSize = 1)
        viewModel.nextPage()
        assertEquals(1, viewModel.state.value.page)

        viewModel.search("meeting")

        assertEquals(0, viewModel.state.value.page)
        assertEquals(2, viewModel.state.value.totalCount)
        assertEquals(listOf("d1"), viewModel.state.value.rows.map { it.sessionId })
        assertFalse("a search result is not the empty state", viewModel.state.value.isEmpty)
    }

    @Test
    fun aPageBeyondTheEnd_isClampedBackIntoRange() {
        repeat(3) { seedPhone("p$it", "text $it", createdAt = 10L + it) }
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

    // ── re-insert routing ────────────────────────────────────────────────────────────────────

    @Test
    fun reinsert_ofAPhoneRow_goesThroughTheDispatchService_andUpdatesTheRow() {
        seedPhone("p1", "please insert me", createdAt = 10)
        inserter.nextOutcome = InsertionOutcome.CLIPBOARD_ONLY
        val viewModel = viewModel()

        viewModel.reinsert(viewModel.state.value.rows.single())

        assertEquals(listOf("please insert me"), inserter.inserted)
        assertEquals(HistoryEvent.Reinserted(InsertionOutcome.CLIPBOARD_ONLY), viewModel.state.value.event)
        val row = (viewModel.state.value.rows.single() as HistoryItem.Phone).row
        assertEquals(InsertionOutcome.CLIPBOARD_ONLY, row.lastOutcome)
        assertTrue("an insert makes a synced row a dispatched one", row.dispatched)
    }

    @Test
    fun reinsert_ofADesktopRow_typesTheFinalOutput_notTheTranscript_andStampsInserted() {
        seedDesktopTake("d1", createdAt = 10, transcript = "hallo welt", output = "Hallo Welt.")
        inserter.nextOutcome = InsertionOutcome.TYPED_CTRL_V
        clock.now = MutableClock.START + 5_000
        val viewModel = viewModel()

        viewModel.reinsert(viewModel.state.value.rows.single())

        // The final output is typed — NOT the raw transcript, NOT via DispatchService.
        assertEquals(listOf("Hallo Welt."), inserter.inserted)
        assertEquals(HistoryEvent.Reinserted(InsertionOutcome.TYPED_CTRL_V), viewModel.state.value.event)
        assertEquals(MutableClock.START + 5_000, desktopSessions.desktopHistoryEntry("d1")?.insertedAt)
        // The row's "not inserted here" dot is now gone (insertedAt non-null).
        val row = (viewModel.state.value.rows.single() as HistoryItem.Desktop).entry
        assertTrue(row.insertedAt != null)
    }

    @Test
    fun reinsert_ofADesktopRow_whenInsertionFails_doesNotStampInserted() {
        seedDesktopTake("d1", createdAt = 10, transcript = "hallo", output = "Hallo.")
        inserter.nextOutcome = InsertionOutcome.FAILED
        val viewModel = viewModel()

        viewModel.reinsert(viewModel.state.value.rows.single())

        assertEquals(HistoryEvent.Reinserted(InsertionOutcome.FAILED), viewModel.state.value.event)
        assertNull("a failed insert never landed in a window — inserted_at must stay null",
            desktopSessions.desktopHistoryEntry("d1")?.insertedAt)
    }

    @Test
    fun reinsert_ofARowThatIsGone_saysSo_forEitherOrigin() {
        val viewModel = viewModel()

        viewModel.reinsert(goneDesktopItem())
        assertEquals(HistoryEvent.Gone, viewModel.state.value.event)

        viewModel.consumeEvent()
        viewModel.reinsert(gonePhoneItem())
        assertEquals(HistoryEvent.Gone, viewModel.state.value.event)

        assertEquals(emptyList<String>(), inserter.inserted)
    }

    // ── copy ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun copy_ofAPhoneRow_touchesTheClipboardOnly_notTheKeyboard() {
        seedPhone("p1", "just copy me", createdAt = 10)
        val viewModel = viewModel()

        viewModel.copy(viewModel.state.value.rows.single())

        assertEquals(listOf("just copy me"), clipboard.writes)
        assertEquals("no Ctrl+V — the user will paste it themselves", emptyList<String>(), inserter.inserted)
        assertEquals(HistoryEvent.Copied, viewModel.state.value.event)

        viewModel.consumeEvent()
        assertNull(viewModel.state.value.event)
    }

    @Test
    fun copy_ofADesktopRow_writesTheFinalOutput_notTheTranscript() {
        seedDesktopTake("d1", createdAt = 10, transcript = "hallo welt", output = "Hallo Welt.")
        val viewModel = viewModel()

        viewModel.copy(viewModel.state.value.rows.single())

        assertEquals(listOf("Hallo Welt."), clipboard.writes)
        assertEquals(emptyList<String>(), inserter.inserted)
        assertEquals(HistoryEvent.Copied, viewModel.state.value.event)
    }

    // ── detail expansion ─────────────────────────────────────────────────────────────────────

    @Test
    fun toggleExpand_opensOneRowAtATime() {
        seedDesktopTake("d1", createdAt = 10, transcript = "a", output = "A.")
        val viewModel = viewModel()

        viewModel.toggleExpand("d1")
        assertEquals("d1", viewModel.state.value.expandedId)
        viewModel.toggleExpand("d1")
        assertNull(viewModel.state.value.expandedId)
    }

    // ── graph without a desktop source ───────────────────────────────────────────────────────

    @Test
    fun withoutADesktopSource_theHistoryIsThePhoneMirrorOnly() {
        seedPhone("p1", "phone", createdAt = 10)
        // A desktop row exists in the database, but this graph has no desktop-session source.
        seedDesktopTake("d1", createdAt = 20, transcript = "raw", output = "Desktop.")

        val viewModel = viewModel(pageSize = 10, desktop = null)

        assertFalse("the filter chips must be hidden", viewModel.state.value.desktopAvailable)
        assertEquals(listOf("p1"), viewModel.state.value.rows.map { it.sessionId })
        assertEquals(1, viewModel.state.value.totalCount)

        // Even an (unreachable through the UI) desktop filter stays safe: no source, no rows, no crash.
        viewModel.setFilter(HistoryFilter.DESKTOP)
        assertEquals(emptyList<String>(), viewModel.state.value.rows.map { it.sessionId })
        assertEquals(0, viewModel.state.value.totalCount)
    }

    @Test
    fun withoutAnInserter_theScreenKnowsTheButtonMustBeDisabled() {
        assertFalse(viewModel(canInsert = false).state.value.canInsert)
    }

    // ── seeds ────────────────────────────────────────────────────────────────────────────────

    private fun seedPhone(sessionId: String, text: String, createdAt: Long) {
        history.upsert(
            deviceId = DEVICE_ID,
            item = SessionUpsert(sessionId, text, createdAt, SessionOriginWire.KEYBOARD, dispatched = false),
            receivedAt = createdAt,
        )
    }

    private fun seedDesktopTake(id: String, createdAt: Long, transcript: String, output: String) {
        desktopSessions.createDictationSession(
            id = id, createdAt = createdAt, language = "de",
            audioFilePath = null, audioFilePathsJson = "[]", durationSeconds = 0L,
        )
        desktopSessions.insertTranscription(
            TranscriptionRecord(
                id = "t-$id", sessionId = id, version = 1, isCurrent = true, text = transcript,
                modelUsed = "whisper-1", provider = "OPENAI", promptTokens = 0, completionTokens = 0,
                durationMs = 0, createdAt = createdAt,
            )
        )
        desktopSessions.completeWithFinalOutput(id, output)
    }

    private fun goneDesktopItem() = HistoryItem.Desktop(
        DesktopHistoryEntry("never-existed", createdAt = 0, finalOutputText = "", transcriptText = null, insertedAt = null)
    )

    private fun gonePhoneItem() = HistoryItem.Phone(
        net.devemperor.dictate.companion.domain.model.ReceivedText(
            sessionId = "never-existed", deviceId = DEVICE_ID, text = "", createdAt = 0,
            receivedAt = 0, origin = SessionOriginWire.KEYBOARD, dispatched = false,
        )
    )

    private companion object {
        const val DEVICE_ID = "test-device-0001"
    }
}
