package net.devemperor.dictate.companion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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

@Composable
fun SettingsScreen(container: CompanionContainer) {
    val viewModel = remember { SettingsViewModel(container.settings, container.autostart, container.addressCatalog) }
    val chordViewModel = remember { ChordSettingsViewModel(container.chordMapping) }
    val state by viewModel.state.collectAsState()

    var port by remember { mutableStateOf(state.port.toString()) }
    var delay by remember { mutableStateOf(state.clipboardRestoreDelayMillis.toString()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = port,
            onValueChange = {
                port = it
                viewModel.setPort(it)
            },
            label = { Text("Port") },
            isError = state.portError != null,
            supportingText = { state.portError?.let { Text(it) } },
            singleLine = true,
            modifier = Modifier.width(220.dp),
        )

        BindAddressSection(state, viewModel)

        OutlinedTextField(
            value = delay,
            onValueChange = {
                delay = it
                it.toLongOrNull()?.let(viewModel::setClipboardRestoreDelay)
            },
            label = { Text("Clipboard restore delay (ms)") },
            supportingText = { Text("How long the dictated text stays on the clipboard before your own is put back.") },
            singleLine = true,
            modifier = Modifier.width(320.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Switch(
                checked = state.autostartEnabled,
                onCheckedChange = viewModel::setAutostart,
                // Disabled rather than lying: on Linux/macOS there is no Run key to write.
                enabled = state.autostartSupported,
            )
            Column {
                Text("Start with the computer")
                if (!state.autostartSupported) {
                    Text(
                        "Not available on this platform.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.restartRequired) {
            Text(
                "Port and bind address take effect the next time the companion starts — the socket " +
                    "in use right now still has the old ones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        ChordSettingsSection(chordViewModel)
    }
}
