package net.devemperor.dictate.companion.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.devemperor.dictate.companion.CompanionContainer
import net.devemperor.dictate.shared.config.ModelFunction
import net.devemperor.dictate.shared.config.ProviderType
import net.devemperor.dictate.shared.config.SubscriptionMode

/**
 * The config-management screen (desktop-host.md §9.2): the local profile / model / prompt editor, in
 * the existing plain-VM + Compose-layout split. All logic lives in [ConfigViewModel] (tested); this
 * file is layout. Editing depth is deliberately shallow in D3 (create / duplicate / delete / set
 * active) — the deep model-parameter + prompt-order pickers, and the Block-E peer-source column, land
 * on top of this same VM (§9.2, peer-katalog E3).
 */
@Composable
fun ManagementScreen(container: CompanionContainer) {
    val repository = container.configRepository
    if (repository == null) {
        Text("Configuration is unavailable in this build.", modifier = Modifier.padding(16.dp))
        return
    }
    val scope = rememberCoroutineScope()
    val settings = container.settings
    val viewModel = remember {
        ConfigViewModel(
            config = repository,
            activeProfileStore = object : ActiveProfileStore {
                override fun get(): String? = settings.activeProfileId
                override fun set(id: String?) { settings.activeProfileId = id }
            },
            scope = scope,
        )
    }
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ProfilesSection(state, viewModel)
        ProvidersSection(state, viewModel)
        ModelsSection(state, viewModel)
        PromptsSection(state, viewModel)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ProfilesSection(state: ConfigUiState, viewModel: ConfigViewModel) = SectionCard("Profiles") {
    var name by remember { mutableStateOf("") }
    if (state.profiles.isEmpty()) Text("No profiles yet.", style = MaterialTheme.typography.bodySmall)
    state.profiles.forEach { profile ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(
                selected = state.activeProfileId == profile.id,
                onClick = { viewModel.setActiveProfile(profile.id) },
            )
            Text(profile.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            SourceBadge(profile.sourceRef?.peerId, profile.subscriptionMode)
            Text(profile.ambiguityMode.name, style = MaterialTheme.typography.labelSmall)
            Button(onClick = { viewModel.duplicateProfile(profile.id) }) { Text("Duplicate") }
            DeleteButton { viewModel.deleteProfile(profile.id) }
        }
    }
    AddRow(value = name, onValueChange = { name = it }, placeholder = "New profile name", onAdd = {
        viewModel.createProfile(name); name = ""
    })
}

@Composable
private fun ProvidersSection(state: ConfigUiState, viewModel: ConfigViewModel) = SectionCard("Providers") {
    var label by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ProviderType.OPENAI) }
    state.providers.forEach { provider ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(provider.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            SourceBadge(provider.sourceRef?.peerId, provider.subscriptionMode)
            Text(provider.providerType.name, style = MaterialTheme.typography.labelSmall)
            DeleteButton { viewModel.deleteProvider(provider.id) }
        }
    }
    EnumChips(ProviderType.entries, type) { type = it }
    AddRow(value = label, onValueChange = { label = it }, placeholder = "Provider label", onAdd = {
        viewModel.createProvider(type, label); label = ""
    })
}

@Composable
private fun ModelsSection(state: ConfigUiState, viewModel: ConfigViewModel) = SectionCard("Models") {
    var modelId by remember { mutableStateOf("") }
    var function by remember { mutableStateOf(ModelFunction.COMPLETION) }
    var providerId by remember { mutableStateOf<String?>(null) }
    val selectedProvider = providerId ?: state.providers.firstOrNull()?.id
    state.models.forEach { model ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.modelId, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            SourceBadge(model.sourceRef?.peerId, model.subscriptionMode)
            Text(model.function.name, style = MaterialTheme.typography.labelSmall)
            DeleteButton { viewModel.deleteModel(model.id) }
        }
    }
    if (state.providers.isEmpty()) {
        Text("Add a provider first.", style = MaterialTheme.typography.bodySmall)
    } else {
        EnumChips(ModelFunction.entries, function) { function = it }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.providers.forEach { p ->
                FilterChip(selected = selectedProvider == p.id, onClick = { providerId = p.id }, label = { Text(p.label, maxLines = 1) })
            }
        }
        AddRow(value = modelId, onValueChange = { modelId = it }, placeholder = "Model id (e.g. gpt-4o-mini)", onAdd = {
            selectedProvider?.let { viewModel.createModel(it, modelId, function); modelId = "" }
        })
    }
}

@Composable
private fun PromptsSection(state: ConfigUiState, viewModel: ConfigViewModel) = SectionCard("Prompts") {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    state.prompts.forEach { prompt ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(prompt.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(prompt.text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SourceBadge(prompt.sourceRef?.peerId, prompt.subscriptionMode)
            DeleteButton { viewModel.deletePrompt(prompt.id) }
        }
    }
    OutlinedTextField(value = name, onValueChange = { name = it }, placeholder = { Text("Prompt name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    AddRow(value = text, onValueChange = { text = it }, placeholder = "Prompt text", onAdd = {
        viewModel.createPrompt(name, text); name = ""; text = ""
    })
}

@Composable
private fun <T : Enum<T>> EnumChips(entries: List<T>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { entry ->
            FilterChip(selected = selected == entry, onClick = { onSelect(entry) }, label = { Text(entry.name) })
        }
    }
}

@Composable
private fun AddRow(value: String, onValueChange: (String) -> Unit, placeholder: String, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onAdd) { Text("Add") }
    }
}

/**
 * The Block-E peer-source column of the editor lists (peer-katalog.md E3, §8.3): a copy taken from
 * a peer is labelled with where it came from — a live subscription as "from <peer>", a fork (mode
 * back to LOCAL, provenance kept) as "forked". Locally created entities show nothing. Subscribing
 * itself happens on the Peers screen; this badge is the editor-side provenance mirror.
 */
@Composable
private fun SourceBadge(sourcePeerId: String?, mode: SubscriptionMode) {
    if (sourcePeerId == null) return
    val text = when (mode) {
        SubscriptionMode.LOCAL -> "forked"
        SubscriptionMode.SUBSCRIBE -> "from $sourcePeerId"
        SubscriptionMode.ONE_SHOT -> "copied from $sourcePeerId"
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
}

@Composable
private fun DeleteButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Delete, contentDescription = "Delete")
    }
}
