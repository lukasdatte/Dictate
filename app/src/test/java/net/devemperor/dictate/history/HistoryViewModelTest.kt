package net.devemperor.dictate.history

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.devemperor.dictate.core.JobState
import net.devemperor.dictate.testutil.FakeSessionDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private fun TestScope.viewModel(): HistoryViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return HistoryViewModel(
            sessionDao = FakeSessionDao(),
            registryState = registry,
            isJobActive = { false },
            ioContext = StandardTestDispatcher(testScheduler),
        )
    }

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
}
