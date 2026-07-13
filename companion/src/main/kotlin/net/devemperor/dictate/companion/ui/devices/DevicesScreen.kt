package net.devemperor.dictate.companion.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.devemperor.dictate.companion.CompanionContainer
import net.devemperor.dictate.companion.ui.pairing.PairingDialog
import net.devemperor.dictate.companion.ui.pairing.PairingViewModel

@Composable
fun DevicesScreen(container: CompanionContainer, baseUrl: () -> String) {
    val viewModel = remember { DevicesViewModel(container.devices) }
    val state by viewModel.state.collectAsState()
    var pairing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Paired phones", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = { pairing = true }) { Text("Pair a phone") }
        }

        Spacer(Modifier.size(12.dp))

        if (state.devices.isEmpty()) {
            Text(
                "No phone is paired yet. Pair one, and its dictations land here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.devices.forEach { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = device.lastSeenAt?.let { "last seen " + it.asRelative() } ?: "never seen",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Un-pairing deletes the device row — the phone's next call gets a 401 and it
                        // offers "pair again" — and cascades its received texts away with it.
                        OutlinedButton(onClick = { viewModel.revoke(device.deviceId) }) { Text("Un-pair") }
                    }
                }
            }
        }
    }

    if (pairing) {
        val pairingViewModel = remember {
            PairingViewModel(container.pairingService, container.devices, baseUrl)
        }
        PairingDialog(
            viewModel = pairingViewModel,
            onDismiss = {
                pairing = false
                viewModel.refresh()
            },
        )
    }
}

private fun Long.asRelative(): String {
    val minutes = (System.currentTimeMillis() - this) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}
