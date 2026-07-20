package net.devemperor.dictate.companion.ui.peers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.devemperor.dictate.companion.ui.asTime
import net.devemperor.dictate.shared.config.Visibility

/**
 * The offer view ("Was biete ich an?", peer-katalog.md §8.2 / F34): one row per own entity with a
 * share switch (`visibility`) and — read-only — the last pickup from the access log. Credential
 * pickups surface through their provider row and are always shown (security transparency, R8).
 * Layout only; the log digestion lives in [OfferViewModel].
 */
@Composable
internal fun OfferTab(viewModel: OfferViewModel) {
    val state by viewModel.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("My offer", style = MaterialTheme.typography.titleMedium)
        Text(
            "Shared entities appear in this companion's catalog for every paired peer. Peers see " +
                "who fetched what and when — never any content beyond the entity itself.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.rows.isEmpty()) {
            Text("No entities yet — create profiles, models or prompts under Config.", style = MaterialTheme.typography.bodySmall)
        }
        state.rows.forEach { row ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.kind.name, style = MaterialTheme.typography.labelSmall)
                        Text(lastPickup(row), style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(
                        checked = row.visibility == Visibility.SHARED,
                        onCheckedChange = { shared ->
                            viewModel.setVisibility(row, if (shared) Visibility.SHARED else Visibility.PRIVATE)
                        },
                    )
                }
            }
        }
    }
}

private fun lastPickup(row: OfferRow): String {
    val access = row.lastAccess ?: return "never fetched"
    return "last fetched ${access.at.asTime()} by ${access.peerDeviceId}"
}
