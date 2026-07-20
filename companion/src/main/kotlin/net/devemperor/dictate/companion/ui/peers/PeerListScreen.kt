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
import java.text.DateFormat
import java.util.Date

/**
 * The peer list (peer-katalog.md §8.1): name, address, derived reachability, last successful
 * contact — plus the "Add peer" entry with tailnet discovery candidates (§9.2) and a pointer to the
 * manual §9.1 pairing path. Layout only; every verdict comes from [PeerExplorerViewModel].
 */
@Composable
internal fun PeerListScreen(viewModel: PeerExplorerViewModel, state: PeerExplorerUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Peers", style = MaterialTheme.typography.titleMedium)
        if (state.peers.isEmpty()) {
            Text(
                "No peers yet. A peer is another Dictate Companion you subscribe prompts, profiles, " +
                    "models or credentials from.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.peers.forEach { peer ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(peer.record.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(peer.record.address, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(lastReached(peer), style = MaterialTheme.typography.labelSmall)
                    }
                    StatusLabel(peer.status)
                    TextButton(onClick = { viewModel.selectPeer(peer.record.peerId) }) { Text("Open") }
                }
            }
        }

        Text("Add peer", style = MaterialTheme.typography.titleSmall)
        Button(onClick = { viewModel.discoverCandidates() }) { Text("Search tailnet") }
        if (state.candidates.isEmpty()) {
            Text(
                "No candidates found. To add a peer manually, open the pairing dialog on the other " +
                    "companion and pair this machine like a phone (same QR/code).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.candidates.forEach { candidate ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(candidate.magicDnsName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(candidate.address, style = MaterialTheme.typography.bodySmall)
                }
                // Becoming a peer = health probe + §9.1 pairing; the candidate row only informs (§9.2).
                Text("pair on the other companion", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun StatusLabel(status: PeerStatus) {
    val (text, color) = when (status) {
        PeerStatus.OK -> "OK" to MaterialTheme.colorScheme.primary
        PeerStatus.STALE -> "STALE" to MaterialTheme.colorScheme.tertiary
        PeerStatus.UNREACHABLE -> "UNREACHABLE" to MaterialTheme.colorScheme.error
    }
    Text(text, color = color, style = MaterialTheme.typography.labelMedium)
}

private fun lastReached(peer: PeerRow): String {
    val at = peer.record.lastSuccessAt ?: return "never reached"
    return "last reached ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))}"
}
