package net.devemperor.dictate.companion.ui.peers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One peer's detail (peer-katalog.md §8.1): every copy taken from it with kind, mode and the
 * derived state-matrix verdict, the three per-copy actions (sync-now / unsubscribe / fork), and —
 * the "Von Peer beziehen" tab (§8.3, desktop-only) — what the peer currently offers, with a
 * subscribe action wherever the [net.devemperor.dictate.companion.catalog.CatalogSubscriber] seam
 * is wired. Layout only; the matrix lives in [PeerExplorerViewModel] (AC13).
 */
@Composable
internal fun PeerDetailScreen(viewModel: PeerExplorerViewModel, state: PeerExplorerUiState, peer: PeerRow) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { viewModel.selectPeer(null) }) { Text("< Peers") }
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.record.displayName, style = MaterialTheme.typography.titleMedium)
                Text(peer.record.address, style = MaterialTheme.typography.bodySmall)
            }
            StatusLabel(peer.status)
            Button(onClick = { viewModel.syncNow(peer.record.peerId) }) { Text("Sync now") }
        }

        Text("Subscribed copies", style = MaterialTheme.typography.titleSmall)
        if (state.copies.isEmpty()) {
            Text("Nothing subscribed from this peer yet.", style = MaterialTheme.typography.bodySmall)
        }
        state.copies.forEach { row ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.copy.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${row.copy.kind.name} · ${row.copy.mode.name}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    CopyStateLabel(row.state)
                    if (row.state != CopyState.FORKED) {
                        TextButton(onClick = { viewModel.unsubscribe(row.copy.localEntityId) }) { Text("Unsubscribe") }
                        TextButton(onClick = { viewModel.fork(row.copy) }) { Text("Fork") }
                    }
                }
            }
        }

        Text("Available from this peer", style = MaterialTheme.typography.titleSmall)
        if (state.availableFromPeer.isEmpty()) {
            Text(
                "No live offer available — the peer is unreachable, has no shared entities, or the " +
                    "sync adapter is not wired yet.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.availableFromPeer.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(entry.kind.name, style = MaterialTheme.typography.labelSmall)
                }
                Button(enabled = viewModel.canSubscribe, onClick = { viewModel.subscribe(entry) }) {
                    Text("Subscribe")
                }
            }
        }
    }
}

@Composable
internal fun CopyStateLabel(state: CopyState) {
    val (text, color) = when (state) {
        CopyState.CURRENT -> "current" to MaterialTheme.colorScheme.primary
        CopyState.UPDATE_AVAILABLE -> "update available" to MaterialTheme.colorScheme.tertiary
        CopyState.FORKED -> "forked" to MaterialTheme.colorScheme.secondary
        CopyState.STALE -> "stale" to MaterialTheme.colorScheme.tertiary
        CopyState.SOURCE_REMOVED -> "source removed" to MaterialTheme.colorScheme.error
    }
    Text(text, color = color, style = MaterialTheme.typography.labelMedium)
}
