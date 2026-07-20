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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.collectAsState
import net.devemperor.dictate.companion.CompanionContainer
import net.devemperor.dictate.companion.data.DesktopHistoryEntry
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.model.ReceivedText
import net.devemperor.dictate.companion.ui.asTime

/** The two histories one screen shows (§9.3): the phone mirror, and this PC's own dictations. */
enum class HistoryScope(val label: String) { PHONE("Phone"), DESKTOP("This PC") }

/**
 * The history — the screen the user actually lives in.
 *
 * Two histories share it (§9.3): the **phone mirror** (texts dictated on the paired phone, synced and
 * dispatched here) and this PC's own **desktop dictations** (recorded locally, with a transcript and a
 * post-processed output). They are different data shapes with different re-insert paths, so each has
 * its own view model behind a scope toggle; this file is layout. The toggle only appears when there is
 * a desktop side to show ([CompanionContainer.desktopSessions] is non-null — always so in the real app,
 * null only in the headless test graph).
 */
@Composable
fun HistoryScreen(container: CompanionContainer) {
    val desktopAvailable = container.desktopSessions != null
    var historyScope by remember { mutableStateOf(HistoryScope.PHONE) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (desktopAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryScope.entries.forEach { entry ->
                    FilterChip(
                        selected = historyScope == entry,
                        onClick = { historyScope = entry },
                        label = { Text(entry.label) },
                    )
                }
            }
        }
        // A weighted Box gives the content the remaining height; without it the fillMaxSize content
        // would claim the whole column and push the toggle off the top.
        Box(modifier = Modifier.weight(1f)) {
            when (historyScope) {
                HistoryScope.PHONE -> PhoneHistoryContent(container)
                HistoryScope.DESKTOP -> DesktopHistoryContent(container)
            }
        }
    }
}

// ── phone mirror (existing) ──────────────────────────────────────────────────────────────────

@Composable
private fun PhoneHistoryContent(container: CompanionContainer) {
    val scope = rememberCoroutineScope()
    val viewModel = remember {
        HistoryViewModel(
            history = container.history,
            dispatch = container.dispatchService,
            clipboard = container.clipboard,
            scope = scope,
            canInsert = container.inserter.available,
        )
    }

    HistoryContent(viewModel.state, viewModel)
}

@Composable
private fun HistoryContent(stateFlow: StateFlow<HistoryUiState>, viewModel: HistoryViewModel) {
    val state by stateFlow.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::search,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("Search received texts") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.size(12.dp))

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isEmpty -> EmptyState()
                state.rows.isEmpty() -> Text("Nothing matches “${state.query}”.")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.rows, key = { it.sessionId }) { row ->
                        HistoryRow(row, state.canInsert, viewModel)
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

@Composable
private fun HistoryRow(row: ReceivedText, canInsert: Boolean, viewModel: HistoryViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!row.dispatched) {
                // Synced, never inserted here — the counterpart of the phone's pending dot.
                Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "${row.createdAt.asTime()} · ${row.subtitle()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TextButton(onClick = { viewModel.copy(row.sessionId) }) { Text("Copy") }
            Button(
                onClick = { viewModel.reinsert(row.sessionId) },
                enabled = canInsert,
            ) { Text("Insert again") }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing received yet.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(6.dp))
        Text(
            "Pair your phone under Devices, then dictate — the text lands here and in your active window.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── desktop dictation (§9.3) ─────────────────────────────────────────────────────────────────

@Composable
private fun DesktopHistoryContent(container: CompanionContainer) {
    val sessions = container.desktopSessions
    if (sessions == null) {
        // Defensive: the toggle is hidden when this is null, so this branch is unreachable in the real
        // app — it only guards the headless graph, mirroring ManagementScreen's null-container message.
        Text("Desktop dictation is unavailable in this build.", modifier = Modifier.padding(16.dp))
        return
    }
    val scope = rememberCoroutineScope()
    val viewModel = remember {
        DesktopHistoryViewModel(
            sessions = sessions,
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
            placeholder = { Text("Search dictations") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.size(12.dp))

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isEmpty -> DesktopEmptyState()
                state.rows.isEmpty() -> Text("Nothing matches “${state.query}”.")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.rows, key = { it.sessionId }) { row ->
                        DesktopHistoryRow(
                            row = row,
                            canInsert = state.canInsert,
                            expanded = state.expandedId == row.sessionId,
                            viewModel = viewModel,
                        )
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

@Composable
private fun DesktopHistoryRow(
    row: DesktopHistoryEntry,
    canInsert: Boolean,
    expanded: Boolean,
    viewModel: DesktopHistoryViewModel,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (row.insertedAt == null) {
                    // Completed but never placed into a window (reviewed-and-discarded) — the same visual
                    // language as the phone mirror's "synced, never inserted here" dot.
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.finalOutputText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "${row.createdAt.asTime()} · ${if (row.insertedAt != null) "inserted" else "not inserted here"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TextButton(onClick = { viewModel.copy(row.sessionId) }) { Text("Copy") }
                Button(
                    onClick = { viewModel.reinsert(row.sessionId) },
                    enabled = canInsert,
                ) { Text("Insert again") }
            }

            TextButton(onClick = { viewModel.toggleExpand(row.sessionId) }) {
                Text(if (expanded) "Hide transcript" else "Show transcript")
            }

            if (expanded) {
                val transcript = row.transcriptText
                val changedByPostProcessing = transcript != null && transcript != row.finalOutputText
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
private fun DesktopEmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No dictations yet.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(6.dp))
        Text(
            "Press your dictation hotkey and speak — takes you record on this PC land here, transcript and all.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── shared layout helpers (both scopes) ──────────────────────────────────────────────────────

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
