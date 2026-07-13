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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.collectAsState
import net.devemperor.dictate.companion.CompanionContainer
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.model.ReceivedText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The history — the screen the user actually lives in.
 *
 * All of the logic sits in [HistoryViewModel] and is tested there; what is left here is layout. The
 * one rule worth stating: a row that was only ever *synced* (never dispatched to this PC) carries a
 * dot, the same visual language as the pending dot in the phone's keyboard panel.
 */
@Composable
fun HistoryScreen(container: CompanionContainer) {
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
            Pager(state, viewModel)
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

@Composable
private fun Pager(state: HistoryUiState, viewModel: HistoryViewModel) {
    Surface(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = viewModel::previousPage, enabled = state.hasPreviousPage) { Text("Previous") }
            OutlinedButton(onClick = viewModel::nextPage, enabled = state.hasNextPage) { Text("Next") }
            Text(
                text = "${state.page * state.pageSize + 1}–" +
                    "${minOf((state.page + 1) * state.pageSize, state.totalCount)} of ${state.totalCount}",
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

private fun Long.asTime(): String =
    TIME_FORMAT.format(Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()))

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM. HH:mm")
private const val SNACKBAR_MILLIS = 3_000L
