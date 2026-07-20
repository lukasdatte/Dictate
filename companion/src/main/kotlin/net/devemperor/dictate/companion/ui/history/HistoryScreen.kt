package net.devemperor.dictate.companion.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.devemperor.dictate.companion.CompanionContainer
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.model.ReceivedText
import net.devemperor.dictate.companion.ui.asTime

/**
 * The unified history — the screen the user actually lives in (§9.3).
 *
 * One list over both `host_origin`s, filterable All / Phone / This PC: phone-mirror rows (texts
 * dictated on the paired phone, synced and dispatched here) and this PC's own dictations (recorded
 * locally, with a transcript-vs-final-output detail). All logic — merged paging, filtering, and the
 * per-origin re-insert routing — lives in [HistoryViewModel]; this file is layout. The filter chips
 * only appear when there is a desktop side to show ([HistoryUiState.desktopAvailable] — always so in
 * the real app, false only in the headless test graph).
 *
 * @see docs/decisions/0035-companion-history-parity.md
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-host.md §9.3
 */
@Composable
fun HistoryScreen(container: CompanionContainer) {
    val scope = rememberCoroutineScope()
    val viewModel = remember {
        HistoryViewModel(
            history = container.history,
            dispatch = container.dispatchService,
            desktopSessions = container.desktopSessions,
            inserter = container.inserter,
            clipboard = container.clipboard,
            clock = container.clock,
            scope = scope,
            canInsert = container.inserter.available,
        )
    }
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::search,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("Search history") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.desktopAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(filter.label) },
                    )
                }
            }
        }

        Spacer(Modifier.size(12.dp))

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isEmpty -> EmptyState(state.filter)
                state.rows.isEmpty() -> Text("Nothing matches “${state.query}”.")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.rows, key = { it.sessionId }) { row ->
                        when (row) {
                            is HistoryItem.Phone -> PhoneHistoryRow(row, state.canInsert, viewModel)
                            is HistoryItem.Desktop -> DesktopHistoryRow(
                                row = row,
                                canInsert = state.canInsert,
                                expanded = state.expandedId == row.sessionId,
                                viewModel = viewModel,
                            )
                        }
                    }
                }
            }
        }

        if (state.totalCount > state.pageSize) {
            Pager(state.page, state.pageSize, state.totalCount, state.hasPreviousPage, state.hasNextPage,
                viewModel::previousPage, viewModel::nextPage)
        }

        state.event?.let { event ->
            LaunchedEffect(event) {
                delay(SNACKBAR_MILLIS)
                viewModel.consumeEvent()
            }
            Snackbar(modifier = Modifier.padding(top = 8.dp)) { Text(event.describe()) }
        }
    }
}

// ── rows ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun PhoneHistoryRow(item: HistoryItem.Phone, canInsert: Boolean, viewModel: HistoryViewModel) {
    val row = item.row
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!row.dispatched) {
                // Synced, never inserted here — the counterpart of the phone's pending dot.
                PendingDot()
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "${row.createdAt.asTime()} · Phone · ${row.subtitle()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RowActions(item, canInsert, viewModel)
        }
    }
}

@Composable
private fun DesktopHistoryRow(
    row: HistoryItem.Desktop,
    canInsert: Boolean,
    expanded: Boolean,
    viewModel: HistoryViewModel,
) {
    val entry = row.entry
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (entry.insertedAt == null) {
                    // Completed but never placed into a window (reviewed-and-discarded) — the same
                    // visual language as the phone mirror's "synced, never inserted here" dot.
                    PendingDot()
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.finalOutputText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "${entry.createdAt.asTime()} · This PC · " +
                            if (entry.insertedAt != null) "inserted" else "not inserted here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                RowActions(row, canInsert, viewModel)
            }

            TextButton(onClick = { viewModel.toggleExpand(entry.sessionId) }) {
                Text(if (expanded) "Hide transcript" else "Show transcript")
            }

            if (expanded) {
                val transcript = entry.transcriptText
                val changedByPostProcessing = transcript != null && transcript != entry.finalOutputText
                Text(
                    text = if (changedByPostProcessing) "Transcript (before post-processing)"
                    else "Transcript (unchanged by post-processing)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = transcript ?: "(no transcript recorded)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RowActions(item: HistoryItem, canInsert: Boolean, viewModel: HistoryViewModel) {
    TextButton(onClick = { viewModel.copy(item) }) { Text("Copy") }
    Button(
        onClick = { viewModel.reinsert(item) },
        enabled = canInsert,
    ) { Text("Insert again") }
}

@Composable
private fun PendingDot() {
    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
}

@Composable
private fun EmptyState(filter: HistoryFilter) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when (filter) {
                HistoryFilter.DESKTOP -> "No dictations on this PC yet."
                HistoryFilter.PHONE -> "Nothing received yet."
                HistoryFilter.ALL -> "Nothing here yet."
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = when (filter) {
                HistoryFilter.DESKTOP -> "Press your dictation hotkey and speak — takes recorded on this PC land here, transcript and all."
                HistoryFilter.PHONE -> "Pair your phone under Devices, then dictate — the text lands here and in your active window."
                HistoryFilter.ALL -> "Dictate on your paired phone or press the dictation hotkey on this PC — everything lands here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── shared layout helpers ────────────────────────────────────────────────────────────────────

@Composable
private fun Pager(
    page: Int,
    pageSize: Int,
    totalCount: Int,
    hasPreviousPage: Boolean,
    hasNextPage: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onPrevious, enabled = hasPreviousPage) { Text("Previous") }
            OutlinedButton(onClick = onNext, enabled = hasNextPage) { Text("Next") }
            Text(
                text = "${page * pageSize + 1}–" +
                    "${minOf((page + 1) * pageSize, totalCount)} of $totalCount",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun HistoryEvent.describe(): String = when (this) {
    is HistoryEvent.Copied -> "Copied to the clipboard."
    is HistoryEvent.Gone -> "That text is no longer here."
    is HistoryEvent.Reinserted -> when (outcome) {
        InsertionOutcome.TYPED_CTRL_V -> "Typed into the active window."
        InsertionOutcome.CLIPBOARD_ONLY -> "Copied to the clipboard — the active window would not accept input."
        InsertionOutcome.FAILED -> "Could not insert the text."
    }
}

private fun ReceivedText.subtitle(): String = when (lastOutcome) {
    InsertionOutcome.TYPED_CTRL_V -> "typed (Ctrl+V)"
    InsertionOutcome.CLIPBOARD_ONLY -> "clipboard only"
    InsertionOutcome.FAILED -> "insertion failed"
    null -> "synced"
}

private const val SNACKBAR_MILLIS = 3_000L
