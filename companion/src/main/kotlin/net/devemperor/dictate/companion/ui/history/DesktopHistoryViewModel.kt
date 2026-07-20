package net.devemperor.dictate.companion.ui.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.devemperor.dictate.companion.data.DesktopHistoryEntry
import net.devemperor.dictate.companion.data.DesktopSessionRepository
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.port.ClipboardPort
import net.devemperor.dictate.companion.domain.port.ClockPort
import net.devemperor.dictate.companion.domain.port.TextInserter

data class DesktopHistoryUiState(
    val rows: List<DesktopHistoryEntry> = emptyList(),
    val query: String = "",
    val page: Int = 0,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val totalCount: Int = 0,
    /** false on Linux/macOS — the "insert again" button is disabled (ADR-0018), same as the phone screen. */
    val canInsert: Boolean = true,
    /** Which row's transcript-vs-final-output detail is open, or null. */
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
 * The desktop-dictation history's brain — the `DESKTOP_DICTATION` counterpart to [HistoryViewModel]
 * (desktop-host.md §9.3).
 *
 * Kept a separate class rather than a mode on [HistoryViewModel] because the two histories are
 * genuinely different domains, not one shape with a flag:
 *  - a desktop take is a [DesktopHistoryEntry] (raw transcript **and** post-processed final output),
 *    a phone row is a `ReceivedText` (device binding + dispatch outcome);
 *  - a desktop session has **no** `dispatch_state` row, so its re-insert cannot go through
 *    [net.devemperor.dictate.companion.domain.DispatchService] (which only ever sees `PHONE_SYNC`
 *    rows and would report the row "gone") — it calls the [inserter] directly on the final output.
 *
 * Same plain-class + injected-[scope] shape as [HistoryViewModel], for the same reason: paging,
 * filtering and what a re-insert does are the parts that can be wrong, and here they are testable on
 * `Dispatchers.Unconfined` without rendering anything.
 */
class DesktopHistoryViewModel(
    private val sessions: DesktopSessionRepository,
    private val inserter: TextInserter,
    private val clipboard: ClipboardPort,
    private val clock: ClockPort,
    private val scope: CoroutineScope,
    canInsert: Boolean,
    pageSize: Int = DesktopHistoryUiState.DEFAULT_PAGE_SIZE,
) {

    private val _state = MutableStateFlow(DesktopHistoryUiState(canInsert = canInsert, pageSize = pageSize))
    val state: StateFlow<DesktopHistoryUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = scope.launch { load(_state.value.query, _state.value.page) }

    /** A new search always lands on page 0 — staying on page 4 of a different result set is nonsense. */
    fun search(query: String) = scope.launch { load(query, page = 0) }

    fun nextPage() = scope.launch {
        if (_state.value.hasNextPage) load(_state.value.query, _state.value.page + 1)
    }

    fun previousPage() = scope.launch {
        if (_state.value.hasPreviousPage) load(_state.value.query, _state.value.page - 1)
    }

    /**
     * "Insert again" — straight through the [TextInserter] on the **final output**, NOT through
     * `DispatchService` (§9.3): a desktop session has no `dispatch_state` row, so the phone re-insert
     * path would report it gone. A non-FAILED outcome stamps `inserted_at` so a re-inserted
     * reviewed-and-discarded take stops showing as "not inserted here" — mirroring how the phone path
     * calls `recordDispatch` after a re-insert.
     */
    fun reinsert(sessionId: String) = scope.launch {
        val entry = sessions.desktopHistoryEntry(sessionId)
        if (entry == null) {
            _state.update { it.copy(event = HistoryEvent.Gone) }
            return@launch
        }
        val outcome = inserter.insert(entry.finalOutputText)
        if (outcome != InsertionOutcome.FAILED) sessions.stampInserted(sessionId, clock.nowMillis())
        load(_state.value.query, _state.value.page, HistoryEvent.Reinserted(outcome))
    }

    /** Clipboard only, no Ctrl+V — the final output, for "I want to paste this somewhere myself, later". */
    fun copy(sessionId: String) = scope.launch {
        val entry = sessions.desktopHistoryEntry(sessionId)
        if (entry == null) {
            _state.update { it.copy(event = HistoryEvent.Gone) }
            return@launch
        }
        clipboard.writeText(entry.finalOutputText)
        _state.update { it.copy(event = HistoryEvent.Copied) }
    }

    /** Open/close a row's transcript-vs-final-output detail; only one row is expanded at a time. */
    fun toggleExpand(sessionId: String) = _state.update {
        it.copy(expandedId = if (it.expandedId == sessionId) null else sessionId)
    }

    fun consumeEvent() = _state.update { it.copy(event = null) }

    private fun load(query: String, page: Int, event: HistoryEvent? = null) {
        val total = sessions.countDesktopHistory(query).toInt()
        // A deleted last row on the last page would otherwise leave the user staring at an empty page
        // with no way back except the "previous" button (same clamp as HistoryViewModel).
        val lastPage = if (total == 0) 0 else (total - 1) / _state.value.pageSize
        val safePage = page.coerceIn(0, lastPage)

        _state.update { current ->
            current.copy(
                rows = sessions.pageDesktopHistory(
                    term = query,
                    limit = current.pageSize.toLong(),
                    offset = safePage.toLong() * current.pageSize,
                ),
                query = query,
                page = safePage,
                totalCount = total,
                event = event ?: current.event,
            )
        }
    }
}
