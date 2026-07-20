package net.devemperor.dictate.companion.ui.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.devemperor.dictate.companion.data.DesktopHistoryEntry
import net.devemperor.dictate.companion.data.DesktopSessionRepository
import net.devemperor.dictate.companion.domain.DispatchService
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.model.ReceivedText
import net.devemperor.dictate.companion.domain.port.ClipboardPort
import net.devemperor.dictate.companion.domain.port.ClockPort
import net.devemperor.dictate.companion.domain.port.HistoryRepository
import net.devemperor.dictate.companion.domain.port.TextInserter

/** What just happened, so the screen can say so and then forget it. */
sealed class HistoryEvent {
    data class Reinserted(val outcome: InsertionOutcome) : HistoryEvent()
    object Copied : HistoryEvent()
    object Gone : HistoryEvent()
}

/** Which host's rows the unified history shows — the `host_origin` axis as a filter (§9.3). */
enum class HistoryFilter(val label: String) { ALL("All"), PHONE("Phone"), DESKTOP("This PC") }

/**
 * One row of the unified history: a phone-mirror row or a locally-dictated desktop take.
 *
 * The two shapes stay distinct — a phone row carries a device binding and a dispatch outcome, a
 * desktop take carries a transcript and a final output — because everything downstream (subtitle,
 * detail view, and above all the re-insert path) branches on which one it is. [text] is the one
 * thing both share: what the row shows, copies, and re-inserts.
 */
sealed interface HistoryItem {
    val sessionId: String
    val createdAt: Long
    val text: String

    data class Phone(val row: ReceivedText) : HistoryItem {
        override val sessionId get() = row.sessionId
        override val createdAt get() = row.createdAt
        override val text get() = row.text
    }

    data class Desktop(val entry: DesktopHistoryEntry) : HistoryItem {
        override val sessionId get() = entry.sessionId
        override val createdAt get() = entry.createdAt
        override val text get() = entry.finalOutputText
    }
}

data class HistoryUiState(
    val rows: List<HistoryItem> = emptyList(),
    val filter: HistoryFilter = HistoryFilter.ALL,
    val query: String = "",
    val page: Int = 0,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val totalCount: Int = 0,
    /** false on Linux/macOS — the row's "insert again" button is disabled and the banner explains why. */
    val canInsert: Boolean = true,
    /** false when the graph has no desktop-session source — the filter chips are hidden. */
    val desktopAvailable: Boolean = false,
    /** Which desktop row's transcript-vs-final-output detail is open, or null. */
    val expandedId: String? = null,
    val event: HistoryEvent? = null,
) {
    val hasNextPage: Boolean get() = (page + 1) * pageSize < totalCount
    val hasPreviousPage: Boolean get() = page > 0
    val isEmpty: Boolean get() = rows.isEmpty() && query.isEmpty()

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}

/**
 * The unified history screen's whole brain — a plain class with a [StateFlow], no Compose, no
 * framework (§9.3). Filtering, merged paging, and what a re-insert does to a row are the parts that
 * can be *wrong*, and here they are testable without rendering anything; the `@Composable` above it
 * is layout. The [scope] is injected so a test can run everything on `Dispatchers.Unconfined` and
 * read the resulting state on the very next line.
 *
 * One screen, two sources, per ADR-0035: `sessions` is the single archive and `host_origin` the
 * axis. The phone mirror ([history], `PHONE_SYNC` + `dispatch_state`) and the local desktop takes
 * ([desktopSessions], `DESKTOP_DICTATION`) keep their own read models and — crucially — their own
 * re-insert paths:
 *
 *  - a **phone** row goes through the same [DispatchService] the dispatch route uses — one insert
 *    path, the same clipboard handling and UIPI degradation, the same gotchas fixed once (ADR-0018);
 *  - a **desktop** take has no `dispatch_state` row, so [DispatchService.reinsert] would report it
 *    "gone" — it types its **final output** straight through the [inserter] and stamps `inserted_at`
 *    on a non-FAILED outcome (mirroring how the phone path records the dispatch).
 *
 * **Merged paging.** The two sources page independently in SQL, so the "All" view fetches the top
 * `(page + 1) * pageSize` rows of *each* active source and merge-sorts by `(createdAt DESC,
 * sessionId DESC)` — the same total order both queries use — then slices the page window. The top-K
 * of the merged list is always contained in the union of each source's top-K, so the window is
 * complete. The over-fetch (a deep page re-reads the rows above it) is deliberate: pages are 50 rows
 * against a local SQLite file, and one uniform load path beats a per-filter offset special case.
 *
 * **Null [desktopSessions]** (the headless [net.devemperor.dictate.companion.CompanionContainer.forTest]
 * graph, which has no capture line and thus no desktop host): the desktop source simply contributes
 * nothing and [HistoryUiState.desktopAvailable] is false, so the screen hides the filter chips and
 * behaves exactly like the pre-Block-D phone-only history. That keeps this one view model the single
 * history brain in every graph instead of forking a second phone-only variant.
 *
 * @see docs/decisions/0035-companion-history-parity.md
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-host.md §9.3
 */
class HistoryViewModel(
    private val history: HistoryRepository,
    private val dispatch: DispatchService,
    private val desktopSessions: DesktopSessionRepository?,
    private val inserter: TextInserter,
    private val clipboard: ClipboardPort,
    private val clock: ClockPort,
    private val scope: CoroutineScope,
    canInsert: Boolean,
    pageSize: Int = HistoryUiState.DEFAULT_PAGE_SIZE,
) {

    private val _state = MutableStateFlow(
        HistoryUiState(canInsert = canInsert, pageSize = pageSize, desktopAvailable = desktopSessions != null)
    )
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = scope.launch { load(_state.value.query, _state.value.filter, _state.value.page) }

    /** A new search always lands on page 0 — staying on page 4 of a different result set is nonsense. */
    fun search(query: String) = scope.launch { load(query, _state.value.filter, page = 0) }

    /** A filter change is a new result set too, so it also lands on page 0. */
    fun setFilter(filter: HistoryFilter) = scope.launch { load(_state.value.query, filter, page = 0) }

    fun nextPage() = scope.launch {
        if (_state.value.hasNextPage) load(_state.value.query, _state.value.filter, _state.value.page + 1)
    }

    fun previousPage() = scope.launch {
        if (_state.value.hasPreviousPage) load(_state.value.query, _state.value.filter, _state.value.page - 1)
    }

    /** "Insert again" — routed per origin; see the class doc for why the two paths must differ. */
    fun reinsert(item: HistoryItem) = scope.launch {
        when (item) {
            is HistoryItem.Phone -> {
                val outcome = dispatch.reinsert(item.sessionId)
                val event = if (outcome == null) HistoryEvent.Gone else HistoryEvent.Reinserted(outcome)
                load(_state.value.query, _state.value.filter, _state.value.page, event)
            }
            is HistoryItem.Desktop -> {
                // Fresh fetch — the row may be gone, and a stale state row must not get re-typed.
                val entry = desktopSessions?.desktopHistoryEntry(item.sessionId)
                if (entry == null) {
                    _state.update { it.copy(event = HistoryEvent.Gone) }
                    return@launch
                }
                val outcome = inserter.insert(entry.finalOutputText)
                if (outcome != InsertionOutcome.FAILED) desktopSessions.stampInserted(item.sessionId, clock.nowMillis())
                load(_state.value.query, _state.value.filter, _state.value.page, HistoryEvent.Reinserted(outcome))
            }
        }
    }

    /** Clipboard only, no Ctrl+V — for "I want to paste this somewhere myself, later". */
    fun copy(item: HistoryItem) = scope.launch {
        val text = when (item) {
            is HistoryItem.Phone -> history.findById(item.sessionId)?.text
            is HistoryItem.Desktop -> desktopSessions?.desktopHistoryEntry(item.sessionId)?.finalOutputText
        }
        if (text == null) {
            _state.update { it.copy(event = HistoryEvent.Gone) }
            return@launch
        }
        clipboard.writeText(text)
        _state.update { it.copy(event = HistoryEvent.Copied) }
    }

    /** Open/close a desktop row's transcript-vs-final-output detail; only one row is expanded at a time. */
    fun toggleExpand(sessionId: String) = _state.update {
        it.copy(expandedId = if (it.expandedId == sessionId) null else sessionId)
    }

    fun consumeEvent() = _state.update { it.copy(event = null) }

    private fun load(query: String, filter: HistoryFilter, page: Int, event: HistoryEvent? = null) {
        val phone = history.takeIf { filter != HistoryFilter.DESKTOP }
        val desktop = desktopSessions?.takeIf { filter != HistoryFilter.PHONE }

        val total = (phone?.count(query) ?: 0) + (desktop?.countDesktopHistory(query)?.toInt() ?: 0)
        // A deleted last row on the last page would otherwise leave the user staring at an empty
        // page with no way back except the "previous" button.
        val pageSize = _state.value.pageSize
        val lastPage = if (total == 0) 0 else (total - 1) / pageSize
        val safePage = page.coerceIn(0, lastPage)

        // Top-K merge — see the class doc for why this is correct and why the over-fetch is fine.
        val window = (safePage + 1) * pageSize
        val merged =
            (phone?.page(query, limit = window, offset = 0).orEmpty().map { HistoryItem.Phone(it) } +
                desktop?.pageDesktopHistory(query, limit = window.toLong(), offset = 0).orEmpty()
                    .map { HistoryItem.Desktop(it) })
                .sortedWith(compareByDescending<HistoryItem> { it.createdAt }.thenByDescending { it.sessionId })

        _state.update { current ->
            current.copy(
                rows = merged.drop(safePage * pageSize).take(pageSize),
                filter = filter,
                query = query,
                page = safePage,
                totalCount = total,
                event = event ?: current.event,
            )
        }
    }
}
