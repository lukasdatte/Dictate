package net.devemperor.dictate.history

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.devemperor.dictate.core.JobState
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionOrigin
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.database.entity.SessionType
import net.devemperor.dictate.testutil.FakeSessionDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Virtual-clock tests for [HistoryViewModel]'s trigger throttling
 * (F-054 / history-pagination-and-scale §3 "Regression: search
 * debounce").
 *
 * The unit under test is the `filters` flow: one `filters` emission ==
 * one new `PagingSource` == one DB query chain (`flatMapLatest` +
 * `Pager` map 1:1 onto it), so "≤ 1 query per debounce window" is
 * asserted as "≤ 1 filter emission per debounce window". The
 * kotlinx-coroutines test scheduler is the fake clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val registry = MutableStateFlow<Map<String, JobState>>(emptyMap())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Builds the VM on the test scheduler so debounce/sample run in virtual time. */
    private fun TestScope.viewModel(
        dao: FakeSessionDao = FakeSessionDao(),
        activeSessionIds: () -> Set<String> = { emptySet() },
    ): HistoryViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return HistoryViewModel(
            sessionDao = dao,
            registryState = registry,
            isJobActive = { false },
            activeSessionIds = activeSessionIds,
            ioContext = StandardTestDispatcher(testScheduler),
        )
    }

    private fun session(id: String): SessionEntity = SessionEntity(
        id = id,
        type = SessionType.RECORDING.name,
        createdAt = 0L,
        targetAppPackage = null,
        language = null,
        audioFilePath = null,
        status = SessionStatus.COMPLETED.name,
        origin = SessionOrigin.KEYBOARD.name,
    )

    private fun TestScope.collectFilters(
        vm: HistoryViewModel,
    ): MutableList<HistoryViewModel.HistoryFilter> {
        val emissions = mutableListOf<HistoryViewModel.HistoryFilter>()
        backgroundScope.launch { vm.filters.collect { emissions += it } }
        runCurrent()
        return emissions
    }

    // ── Search debounce ───────────────────────────────────────────

    @Test
    fun `initial filter emits without waiting out the debounce window`() = runTest {
        val emissions = collectFilters(viewModel())

        assertEquals(1, emissions.size)
        assertEquals(HistoryViewModel.HistoryFilter(type = null, searchPattern = null), emissions.single())
    }

    @Test
    fun `rapid text changes issue at most one query per debounce window`() = runTest {
        val vm = viewModel()
        val emissions = collectFilters(vm)

        // Simulated typing burst — every change well inside the 300 ms window.
        vm.setSearchQuery("h")
        advanceTimeBy(100)
        vm.setSearchQuery("he")
        advanceTimeBy(100)
        vm.setSearchQuery("hello")
        advanceTimeBy(HistoryViewModel.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        // 1 initial + exactly 1 for the whole burst — the intermediate
        // "h"/"he" states never reach the DAO.
        assertEquals(2, emissions.size)
        assertEquals("hello", emissions.last().searchPattern)
    }

    @Test
    fun `search argument is wildcard-escaped before it reaches the DAO`() = runTest {
        val vm = viewModel()
        val emissions = collectFilters(vm)

        vm.setSearchQuery("100%_done")
        advanceTimeBy(HistoryViewModel.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals("""100\%\_done""", emissions.last().searchPattern)
    }

    @Test
    fun `blank search clears the filter`() = runTest {
        val vm = viewModel()
        val emissions = collectFilters(vm)

        vm.setSearchQuery("hello")
        advanceTimeBy(HistoryViewModel.SEARCH_DEBOUNCE_MS + 1)
        vm.setSearchQuery("   ")
        advanceTimeBy(HistoryViewModel.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        assertNull(emissions.last().searchPattern)
    }

    @Test
    fun `type filter applies without debounce delay`() = runTest {
        val vm = viewModel()
        val emissions = collectFilters(vm)

        vm.setTypeFilter("REWORDING")
        runCurrent() // no advanceTimeBy — chips must apply immediately

        assertEquals(2, emissions.size)
        assertEquals("REWORDING", emissions.last().type)
    }

    // ── Registry-tick coalescing ──────────────────────────────────

    @Test
    fun `registry tick burst coalesces into a single refresh event`() = runTest {
        val vm = viewModel()
        var events = 0
        backgroundScope.launch { vm.refreshEvents.collect { events++ } }
        runCurrent()

        // The replayed initial snapshot must not trigger a refresh.
        assertEquals(0, events)

        // Five rapid ticks inside one coalescing window.
        repeat(5) { i ->
            registry.value = mapOf(
                "session" to JobState.Running(
                    sessionId = "session",
                    currentStepIndex = i,
                    totalSteps = 5,
                    currentStepName = "step-$i",
                    startedAt = 0L,
                )
            )
            runCurrent()
        }
        advanceTimeBy(HistoryViewModel.REGISTRY_COALESCE_MS + 1)
        runCurrent()

        assertEquals(1, events)

        // Quiet registry → no further events.
        advanceTimeBy(HistoryViewModel.REGISTRY_COALESCE_MS * 5)
        runCurrent()
        assertEquals(1, events)
    }

    // ── F-114 delete guards ───────────────────────────────────────

    @Test
    fun `single delete of an active session is blocked and never hits the DAO`() = runTest {
        val dao = FakeSessionDao().apply { seed(session("busy")) }
        val vm = viewModel(dao = dao, activeSessionIds = { setOf("busy") })
        val events = mutableListOf<HistoryViewModel.DeleteEvent>()
        backgroundScope.launch { vm.deleteEvents.collect { events += it } }

        vm.deleteSession("busy")
        advanceUntilIdle()

        // D7: refusal, not a delete. The DAO must not have been touched.
        assertTrue("busy" !in dao.deletedIds)
        assertEquals(listOf(HistoryViewModel.DeleteEvent.BlockedActive), events)
    }

    @Test
    fun `single delete of an idle session proceeds to the DAO`() = runTest {
        val dao = FakeSessionDao().apply { seed(session("idle")) }
        val vm = viewModel(dao = dao, activeSessionIds = { emptySet() })

        vm.deleteSession("idle")
        advanceUntilIdle()

        assertEquals(listOf("idle"), dao.deletedIds)
    }

    @Test
    fun `delete-all skips active ids and reports the skipped count`() = runTest {
        val dao = FakeSessionDao().apply {
            seed(session("a")); seed(session("busy")); seed(session("c"))
        }
        val vm = viewModel(dao = dao, activeSessionIds = { setOf("busy") })
        val events = mutableListOf<HistoryViewModel.DeleteEvent>()
        backgroundScope.launch { vm.deleteEvents.collect { events += it } }

        vm.deleteAllSessions()
        advanceUntilIdle()

        // The exempted (active) id was passed as the DAO exemption list;
        // the plain deleteAll() wipe path must NOT have fired.
        assertEquals(listOf(listOf("busy")), dao.deleteAllExceptCalls)
        assertEquals(0, dao.deleteAllCalls)
        assertEquals(listOf(HistoryViewModel.DeleteEvent.AllDeleted(skipped = 1)), events)
    }

    @Test
    fun `delete-all with no active jobs wipes everything and reports zero skips`() = runTest {
        val dao = FakeSessionDao().apply { seed(session("a")); seed(session("b")) }
        val vm = viewModel(dao = dao, activeSessionIds = { emptySet() })
        val events = mutableListOf<HistoryViewModel.DeleteEvent>()
        backgroundScope.launch { vm.deleteEvents.collect { events += it } }

        vm.deleteAllSessions()
        advanceUntilIdle()

        assertEquals(1, dao.deleteAllCalls)
        assertTrue(dao.deleteAllExceptCalls.isEmpty())
        assertEquals(listOf(HistoryViewModel.DeleteEvent.AllDeleted(skipped = 0)), events)
    }

    // ── F-117 empty-state derivation ──────────────────────────────

    @Test
    fun `isFiltered is false with no type filter and blank search`() = runTest {
        val vm = viewModel()
        runCurrent()
        assertFalse(vm.isFiltered.value)
    }

    @Test
    fun `isFiltered is true when a type chip is selected`() = runTest {
        val vm = viewModel()
        vm.setTypeFilter(SessionType.REWORDING.name)
        runCurrent()
        assertTrue(vm.isFiltered.value)
    }

    @Test
    fun `isFiltered is true when search text is non-blank`() = runTest {
        val vm = viewModel()
        vm.setSearchQuery("hello")
        runCurrent() // raw searchInput, no debounce needed for the predicate
        assertTrue(vm.isFiltered.value)
    }

    @Test
    fun `isFiltered clears when search is blanked and chip reset to all`() = runTest {
        val vm = viewModel()
        vm.setTypeFilter(SessionType.REWORDING.name)
        vm.setSearchQuery("hello")
        runCurrent()
        assertTrue(vm.isFiltered.value)

        vm.setTypeFilter(null)
        vm.setSearchQuery("   ")
        runCurrent()
        assertFalse(vm.isFiltered.value)
    }
}
