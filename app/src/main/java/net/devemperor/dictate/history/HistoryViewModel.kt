package net.devemperor.dictate.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import net.devemperor.dictate.core.ActiveJobRegistry
import net.devemperor.dictate.core.JobState
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.dao.LikeEscape
import net.devemperor.dictate.database.dao.SessionDao

/**
 * State holder for [HistoryActivity] (F-054 /
 * history-pagination-and-scale §2) — owns the paged session stream and
 * every trigger that used to funnel into the old synchronous
 * `refreshData()`:
 *
 *  - **Type-filter chips** apply immediately ([setTypeFilter]).
 *  - **Search input** is debounced by [SEARCH_DEBOUNCE_MS]
 *    ([setSearchQuery]) so per-keystroke `onQueryTextChange` callbacks
 *    collapse into at most one query per pause, and the argument is
 *    wildcard-escaped via [LikeEscape] before it reaches the DAO.
 *  - **Registry ticks** are coalesced by [REGISTRY_COALESCE_MS] into
 *    [refreshEvents]; the activity answers each event with
 *    `adapter.refresh()`.
 *  - **Deletes** run on [ioContext]; Room's invalidation tracker then
 *    invalidates the live `PagingSource`, so the list self-refreshes
 *    without a manual reload.
 *
 * All queries execute on Room's background paging executor — this
 * screen no longer depends on the app-wide `allowMainThreadQueries()`
 * flag (spec §2.2; removing the flag globally stays out of scope).
 */
class HistoryViewModel(
    private val sessionDao: SessionDao,
    /**
     * Running-job snapshots — production passes
     * [ActiveJobRegistry.state]. `StateFlow` (not plain `Flow`) is part
     * of the contract: [refreshEvents] drops the replayed current
     * value, so a cold flow would swallow its first real tick.
     */
    registryState: StateFlow<Map<String, JobState>>,
    /** Row-level running check, sampled at page-load time (see [HistoryRow]). */
    private val isJobActive: (String) -> Boolean,
    /** Injectable so tests can run deletes on a test dispatcher. */
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : ViewModel() {

    /** `SessionType.name` or `null` = all types (Double-Enum boundary convention). */
    private val typeFilter = MutableStateFlow<String?>(null)

    /** Raw (unescaped) search text; `null` = no search active. */
    private val searchInput = MutableStateFlow<String?>(null)

    /**
     * Search input, debounced. The `withIndex` selector exempts the
     * initial `StateFlow` replay from the debounce so the first page
     * loads immediately when the screen opens; only *changes* wait out
     * the [SEARCH_DEBOUNCE_MS] window.
     */
    @OptIn(FlowPreview::class)
    private val debouncedSearch: Flow<String?> = searchInput
        .withIndex()
        .debounce { (index, _) -> if (index == 0) 0L else SEARCH_DEBOUNCE_MS }
        .map { it.value }
        .distinctUntilChanged()

    /**
     * The effective query filter. One emission == one new
     * `PagingSource` == one DB query chain — the debounce guarantee
     * asserted by `HistoryViewModelTest` hangs off this flow.
     *
     * Internal (not private) purely as a test seam.
     */
    internal val filters: Flow<HistoryFilter> =
        combine(typeFilter, debouncedSearch) { type, search ->
            HistoryFilter(type = type, searchPattern = search?.let(LikeEscape::escape))
        }.distinctUntilChanged()

    /**
     * Paged history rows. `flatMapLatest` cancels the previous pager as
     * soon as the filter changes (stale in-flight loads are dropped);
     * `cachedIn` keeps the loaded pages across configuration changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingData: Flow<PagingData<HistoryRow>> = filters
        .flatMapLatest { filter ->
            Pager(
                config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false),
                pagingSourceFactory = { sessionDao.pagedHistory(filter.type, filter.searchPattern) },
            ).flow
        }
        .map { pagingData ->
            pagingData.map { session -> HistoryRow(session, isJobActive(session.id)) }
        }
        .cachedIn(viewModelScope)

    /**
     * Coalesced registry ticks (spec §2.3). `sample` — not `debounce` —
     * because a busy pipeline emits continuous progress updates and a
     * debounce would starve the refresh until the burst ends; sampling
     * guarantees at most one refresh per [REGISTRY_COALESCE_MS] window
     * with bounded latency. The replayed current snapshot is dropped:
     * the initial page load already carries fresh running-flags.
     */
    @OptIn(FlowPreview::class)
    val refreshEvents: Flow<Unit> = registryState
        .drop(1)
        .sample(REGISTRY_COALESCE_MS)
        .map { }

    /** @param type `SessionType.name` or `null` for "all". Applies without debounce. */
    fun setTypeFilter(type: String?) {
        typeFilter.value = type
    }

    /** Raw SearchView text; blank input clears the search filter. */
    fun setSearchQuery(query: String?) {
        searchInput.value = query?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Deletes one session off the main thread; Room invalidation refreshes the list. */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch(ioContext) { sessionDao.deleteById(sessionId) }
    }

    /** Deletes the entire history off the main thread. */
    fun deleteAllSessions() {
        viewModelScope.launch(ioContext) { sessionDao.deleteAll() }
    }

    /**
     * Effective query arguments for [SessionDao.pagedHistory].
     * [searchPattern] is already [LikeEscape]-escaped.
     */
    internal data class HistoryFilter(val type: String?, val searchPattern: String?)

    companion object {
        /** Spec §2.3 — "debounce the search callback (~300 ms)". */
        const val SEARCH_DEBOUNCE_MS = 300L

        /** Registry-tick coalescing window (spec §2.3). */
        const val REGISTRY_COALESCE_MS = 300L

        /**
         * Rows per page. Comfortably more than one screenful so the
         * first load fills the viewport, small enough that a query
         * stays cheap on low-end devices.
         */
        const val PAGE_SIZE = 40

        /** Production wiring — DB singleton + process-wide job registry. */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HistoryViewModel(
                    sessionDao = DictateDatabase.getInstance(context).sessionDao(),
                    registryState = ActiveJobRegistry.state,
                    isJobActive = ActiveJobRegistry::isActive,
                )
            }
        }
    }
}
