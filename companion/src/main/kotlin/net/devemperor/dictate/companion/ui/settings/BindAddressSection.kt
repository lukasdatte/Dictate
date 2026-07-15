package net.devemperor.dictate.companion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.devemperor.dictate.companion.domain.net.AddressKind
import net.devemperor.dictate.companion.domain.net.BindCandidate
import net.devemperor.dictate.companion.domain.net.BindSelection
import net.devemperor.dictate.companion.domain.net.BindWarning

/**
 * The "listen on" chooser — a radio pair (All / Selected) over a checkbox list of the machine's
 * addresses, mirroring the domain [BindSelection] one-for-one.
 *
 * Free text survives as a collapsed *validated* advanced field: an address can legitimately be absent
 * at configuration time (a VPN up only after login), so the catalogue guides without becoming a cage.
 * The warning banners are load-bearing, not decoration — [BindWarning.ListeningOnAllInterfaces] is
 * the visible warning ADR-0017 §3 requires before the LAN can reach the port.
 */
@Composable
fun BindAddressSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val isAll = state.bindSelection is BindSelection.AllInterfaces

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Listen on", style = MaterialTheme.typography.titleSmall)

        state.warnings.forEach { WarningBanner(it) }

        if (state.tailscaleSuggestion != null) {
            SuggestionBanner(state.tailscaleSuggestion) { viewModel.applyTailscaleSuggestion() }
        }

        ModeRow(
            selected = isAll,
            label = "All interfaces (0.0.0.0)",
            onSelect = viewModel::listenOnAllInterfaces,
        )
        ModeRow(
            selected = !isAll,
            label = "Selected addresses",
            onSelect = viewModel::listenOnSelected,
        )

        if (!isAll) {
            Column(modifier = Modifier.padding(start = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.candidates.forEach { candidate ->
                    AddressRow(
                        candidate = candidate,
                        checked = candidate.address in state.selectedAddresses,
                        onCheckedChange = { viewModel.toggleAddress(candidate.address, it) },
                    )
                }
                AdvancedManualEntry(onAdd = viewModel::addManualAddress)
            }
        }

        state.bindError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        TextButton(onClick = viewModel::refreshInterfaces) { Text("⟳ Refresh addresses") }
    }
}

@Composable
private fun ModeRow(selected: Boolean, label: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}

@Composable
private fun AddressRow(candidate: BindCandidate, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(candidate.address, fontFamily = FontFamily.Monospace, modifier = Modifier.width(140.dp))
        Text(candidate.interfaceName, modifier = Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall)
        Text(kindLabel(candidate.kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AdvancedManualEntry(onAdd: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    if (!expanded) {
        TextButton(onClick = { expanded = true }) { Text("Advanced: enter an address manually") }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("IPv4 address") },
            supportingText = { Text("For an address not listed yet — e.g. a VPN that comes up after login.") },
            singleLine = true,
            modifier = Modifier.width(240.dp),
        )
        OutlinedButton(onClick = { onAdd(draft); draft = "" }) { Text("Add") }
    }
}

@Composable
private fun SuggestionBanner(address: String, onApply: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Tailscale found ($address). Listen on it only, so nothing on the LAN can reach this port?",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onApply) { Text("Use Tailscale only") }
    }
}

@Composable
private fun WarningBanner(warning: BindWarning) {
    val (text, isError) = when (warning) {
        is BindWarning.ListeningOnAllInterfaces ->
            "Everything on your LAN can reach this port." to false
        is BindWarning.NoTailscaleFound ->
            "No Tailscale address found — listening on the local network instead." to false
        is BindWarning.AddressUnavailable ->
            "The address ${warning.address} is not on any current interface; it is skipped." to true
        is BindWarning.FellBackToLoopback ->
            "The chosen address no longer exists — the server is on loopback and no phone can reach it." to true
        is BindWarning.AddressMigrated ->
            "The chosen address changed; now listening on ${warning.to}." to false
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun kindLabel(kind: AddressKind): String = when (kind) {
    AddressKind.TAILSCALE -> "Tailscale"
    AddressKind.LAN -> "LAN"
    AddressKind.LOOPBACK -> "Loopback"
    AddressKind.OTHER -> "Other"
}
