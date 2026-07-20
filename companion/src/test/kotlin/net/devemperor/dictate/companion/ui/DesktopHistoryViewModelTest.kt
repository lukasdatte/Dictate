package net.devemperor.dictate.companion.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.DesktopSessionRepository
import net.devemperor.dictate.companion.data.TranscriptionRecord
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.session.SessionOrigin
import net.devemperor.dictate.companion.fakes.FakeClipboard
import net.devemperor.dictate.companion.fakes.FakeTextInserter
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.companion.ui.history.DesktopHistoryViewModel
import net.devemperor.dictate.companion.ui.history.HistoryEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop-history screen's logic, without Compose (desktop-host.md §9.3).
 *
 * The mirror of [HistoryViewModelTest] for the `DESKTOP_DICTATION` scope: what a search returns, where
 * a page boundary falls, and — the load-bearing difference from the phone screen — that a re-insert
 * types the **final output** straight through the inserter (not the transcript, not `DispatchService`)
 * and stamps `inserted_at`. `Dispatchers.Unconfined` runs each `launch` inline so state reads on the
 * next line.
 */
class DesktopHistoryViewModelTest {

    private val database = CompanionDatabase.inMemory()
    private val sessions = DesktopSessionRepository(database)
    private val queries = database.companionQueries
    private val inserter = FakeTextInserter()
    private val clipboard = FakeClipboard()
    private val clock = MutableClock()

    private fun viewModel(canInsert: Boolean = true, pageSize: Int = 2) = DesktopHistoryViewModel(
        sessions = sessions,
        inserter = inserter,
        clipboard = clipboard,
        clock = clock,
        scope = CoroutineScope(Dispatchers.Unconfined),
        canInsert = canInsert,
        pageSize = pageSize,
    )

    @Test
    fun anEmptyHistory_isAnEmptyState_notAnEmptySearchResult() {
        val state = viewModel().state.value

        assertTrue(state.isEmpty)
        assertEquals(0, state.totalCount)
        assertFalse(state.hasNextPage)
    }

    @Test
    fun rowsAreNewestFirst_andPagedByPageSize() {
        seedCompletedDesktopTake("s1", createdAt = 10, transcript = "first raw", output = "First.")
        seedCompletedDesktopTake("s2", createdAt = 20, transcript = "second raw", output = "Second.")
        seedCompletedDesktopTake("s3", createdAt = 30, transcript = "third raw", output = "Third.")

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
    fun aSearchAlwaysLandsOnPageZero_andFiltersByFinalOutput() {
        seedCompletedDesktopTake("s1", createdAt = 10, transcript = "hallo welt", output = "Hallo Welt.")
        seedCompletedDesktopTake("s2", createdAt = 20, transcript = "wie gehts", output = "Wie geht's?")
        val viewModel = viewModel(pageSize = 1)
        viewModel.nextPage()
        assertEquals(1, viewModel.state.value.page)

        viewModel.search("welt")

        assertEquals(0, viewModel.state.value.page)
        assertEquals(listOf("s1"), viewModel.state.value.rows.map { it.sessionId })
        assertFalse("a search result is not the empty state", viewModel.state.value.isEmpty)
    }

    @Test
    fun reinsert_typesTheFinalOutput_notTheTranscript_andStampsInserted() {
        seedCompletedDesktopTake("s1", createdAt = 10, transcript = "hallo welt", output = "Hallo Welt.")
        inserter.nextOutcome = InsertionOutcome.TYPED_CTRL_V
        clock.now = MutableClock.START + 5_000
        val viewModel = viewModel()

        viewModel.reinsert("s1")

        // The final output is typed — NOT the raw transcript, NOT via DispatchService.
        assertEquals(listOf("Hallo Welt."), inserter.inserted)
        assertEquals(HistoryEvent.Reinserted(InsertionOutcome.TYPED_CTRL_V), viewModel.state.value.event)
        assertEquals(MutableClock.START + 5_000, sessions.desktopHistoryEntry("s1")?.insertedAt)
        // The row's "not inserted here" dot is now gone (insertedAt non-null).
        assertTrue(viewModel.state.value.rows.single().insertedAt != null)
    }

    @Test
    fun reinsert_whenInsertionFails_doesNotStampInserted() {
        seedCompletedDesktopTake("s1", createdAt = 10, transcript = "hallo", output = "Hallo.")
        inserter.nextOutcome = InsertionOutcome.FAILED
        val viewModel = viewModel()

        viewModel.reinsert("s1")

        assertEquals(HistoryEvent.Reinserted(InsertionOutcome.FAILED), viewModel.state.value.event)
        assertNull("a failed insert never landed in a window — inserted_at must stay null",
            sessions.desktopHistoryEntry("s1")?.insertedAt)
    }

    @Test
    fun reinsert_ofARowThatIsGone_saysSo() {
        val viewModel = viewModel()

        viewModel.reinsert("never-existed")

        assertEquals(HistoryEvent.Gone, viewModel.state.value.event)
        assertEquals(emptyList<String>(), inserter.inserted)
    }

    @Test
    fun copy_writesTheFinalOutputToTheClipboardOnly() {
        seedCompletedDesktopTake("s1", createdAt = 10, transcript = "hallo welt", output = "Hallo Welt.")
        val viewModel = viewModel()

        viewModel.copy("s1")

        assertEquals(listOf("Hallo Welt."), clipboard.writes)
        assertEquals("no Ctrl+V — the user will paste it themselves", emptyList<String>(), inserter.inserted)
        assertEquals(HistoryEvent.Copied, viewModel.state.value.event)

        viewModel.consumeEvent()
        assertNull(viewModel.state.value.event)
    }

    @Test
    fun toggleExpand_opensOneRowAtATime() {
        seedCompletedDesktopTake("s1", createdAt = 10, transcript = "a", output = "A.")
        val viewModel = viewModel()

        viewModel.toggleExpand("s1")
        assertEquals("s1", viewModel.state.value.expandedId)
        viewModel.toggleExpand("s1")
        assertNull(viewModel.state.value.expandedId)
    }

    @Test
    fun withoutAnInserter_theScreenKnowsTheButtonMustBeDisabled() {
        assertFalse(viewModel(canInsert = false).state.value.canInsert)
    }

    @Test
    fun theDesktopList_showsNeitherPhoneSyncNorRefinementTakes() {
        seedCompletedDesktopTake("s1", createdAt = 10, transcript = "local raw", output = "Local take.")
        // A phone-sync mirror row must not surface here …
        queries.upsertSyncSession(
            id = "ph-1", createdAt = 20, origin = SessionOrigin.KEYBOARD,
            finalOutputText = "phone text", insertedAt = null,
        )
        // … and neither may an S2 review-refinement take (its transcript belongs to the parent, §8.3).
        sessions.createRefinementSession(
            id = "s2", createdAt = 30, language = "de",
            audioFilePath = null, audioFilePathsJson = "[]", durationSeconds = 0L,
            parentSessionId = "s1",
        )

        val viewModel = viewModel(pageSize = 10)

        assertEquals(listOf("s1"), viewModel.state.value.rows.map { it.sessionId })
        assertEquals(1, viewModel.state.value.totalCount)
    }

    private fun seedCompletedDesktopTake(id: String, createdAt: Long, transcript: String, output: String) {
        sessions.createDictationSession(
            id = id, createdAt = createdAt, language = "de",
            audioFilePath = null, audioFilePathsJson = "[]", durationSeconds = 0L,
        )
        sessions.insertTranscription(
            TranscriptionRecord(
                id = "t-$id", sessionId = id, version = 1, isCurrent = true, text = transcript,
                modelUsed = "whisper-1", provider = "OPENAI", promptTokens = 0, completionTokens = 0,
                durationMs = 0, createdAt = createdAt,
            )
        )
        sessions.completeWithFinalOutput(id, output)
    }
}
