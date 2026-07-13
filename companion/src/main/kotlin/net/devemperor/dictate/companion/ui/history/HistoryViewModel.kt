package net.devemperor.dictate.companion.ui.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.devemperor.dictate.companion.domain.DispatchService
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.model.ReceivedText
import net.devemperor.dictate.companion.domain.port.ClipboardPort
import net.devemperor.dictate.companion.domain.port.HistoryRepository

/** What just happened, so the screen can say so and then forget it. */
sealed class HistoryEvent {
    data class Reinserted(val outcome: InsertionOutcome) : HistoryEvent()
    object Copied : HistoryEvent()
    object Gone : HistoryEvent()
}

data class HistoryUiState(
    val rows: List<ReceivedText> = emptyList(),
    val query: String = "",
    val page: Int = 0,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val totalCount: Int = 0,
    /** false on Linux/macOS — the row's "insert again" button is disabled and the banner explains why. */
    val canInsert: Boolean = true,
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
 * The history screen's whole brain — a plain class with a [StateFlow], no Compose, no framework.
 *
 * That is the point: filtering, paging, and what a re-insert does to a row are the parts that can be
 * *wrong*, and here they are testable without rendering anything. What is left in the `@Composable`
 * is layout, which a test could only re-state.
 *
 * The [scope] is injected rather than created so a test can run everything on `Dispatchers.Unconfined`
 * and read the resulting state on the very next line.
 */
class HistoryViewModel(
    private val history: HistoryRepository,
    private val dispatch: DispatchService,
    private val clipboard: ClipboardPort,
    private val scope: CoroutineScope,
    canInsert: Boolean,
    pageSize: Int = HistoryUiState.DEFAULT_PAGE_SIZE,
) {

    private val _state = MutableStateFlow(HistoryUiState(canInsert = canInsert, pageSize = pageSize))
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

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
     * "Insert again" — through the **same** [DispatchService] the dispatch route uses.
     *
     * One insert path in the whole application: the same clipboard handling, the same UIPI
     * degradation, the same gotchas fixed in one place (ADR-0018).
     */
    fun reinsert(sessionId: String) = scope.launch {
        val outcome = dispatch.reinsert(sessionId)
        val event = if (outcome == null) HistoryEvent.Gone else HistoryEvent.Reinserted(outcome)
        load(_state.value.query, _state.value.page, event)
    }

    /** Clipboard only, no Ctrl+V — for "I want to paste this somewhere myself, later". */
    fun copy(sessionId: String) = scope.launch {
        val row = history.findById(sessionId)
        if (row == null) {
            _state.update { it.copy(event = HistoryEvent.Gone) }
            return@launch
        }
        clipboard.writeText(row.text)
        _state.update { it.copy(event = HistoryEvent.Copied) }
    }

    fun consumeEvent() = _state.update { it.copy(event = null) }

    private fun load(query: String, page: Int, event: HistoryEvent? = null) {
        val total = history.count(query)
        // A deleted last row on the last page would otherwise leave the user staring at an empty
        // page with no way back except the "previous" button.
        val lastPage = if (total == 0) 0 else (total - 1) / _state.value.pageSize
        val safePage = page.coerceIn(0, lastPage)

        _state.update { current ->
            current.copy(
                rows = history.page(query, limit = current.pageSize, offset = safePage * current.pageSize),
                query = query,
                page = safePage,
                totalCount = total,
                event = event ?: current.event,
            )
        }
    }
}
