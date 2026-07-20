package net.devemperor.dictate.companion.ui.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.devemperor.dictate.companion.data.CompanionConfigRepository
import net.devemperor.dictate.shared.config.ModelFunction
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.shared.config.ProviderConfigEntity
import net.devemperor.dictate.shared.config.ProviderType
import java.util.UUID

/** The management-screen snapshot: the four entity lists plus the device-local active-profile pointer. */
data class ConfigUiState(
    val profiles: List<ProfileEntity> = emptyList(),
    val providers: List<ProviderConfigEntity> = emptyList(),
    val models: List<ModelRefEntity> = emptyList(),
    val prompts: List<PromptV3Entity> = emptyList(),
    val activeProfileId: String? = null,
)

/**
 * The brain of the config-management screens (desktop-host.md §9.2) — a plain class with a
 * [StateFlow], no Compose (the `SettingsViewModel`/`HistoryViewModel` precedent).
 *
 * CRUD over the four shareable entities plus the active-profile pointer. Writes go through
 * [CompanionConfigRepository], which recomputes each entity's `content_hash` — so a profile the user
 * builds here is immediately catalog-shareable (Block E) without a separate "prepare for sharing" step.
 * The [scope] is injected so a test runs everything on `Dispatchers.Unconfined` and reads the next line.
 *
 * @see docs/decisions/0030-config-entity-model.md
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/desktop-host.md §9.2
 */
class ConfigViewModel(
    private val config: CompanionConfigRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    private val _state = MutableStateFlow(ConfigUiState())
    val state: StateFlow<ConfigUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = scope.launch { load() }

    // ── profiles ────────────────────────────────────────────────────────────────────────────────

    fun createProfile(name: String) = scope.launch {
        val trimmed = name.trim().ifBlank { "New profile" }
        config.save(ProfileEntity(id = newId(), name = trimmed))
        load()
    }

    fun duplicateProfile(id: String) = scope.launch {
        val original = config.profile(id) ?: return@launch
        config.save(original.copy(id = newId(), name = "${original.name} (copy)", sourceRef = null))
        load()
    }

    fun deleteProfile(id: String) = scope.launch {
        config.deleteProfile(id)
        if (activeProfileStore.get() == id) activeProfileStore.set(null)
        load()
    }

    fun setActiveProfile(id: String?) = scope.launch {
        activeProfileStore.set(id)
        load()
    }

    // ── providers / models / prompts ──────────────────────────────────────────────────────────────

    fun createProvider(providerType: ProviderType, label: String) = scope.launch {
        config.save(ProviderConfigEntity(id = newId(), providerType = providerType, label = label.trim().ifBlank { providerType.name }))
        load()
    }

    fun deleteProvider(id: String) = scope.launch { config.deleteProviderConfig(id); load() }

    fun createModel(providerRef: String, modelId: String, function: ModelFunction) = scope.launch {
        if (modelId.isBlank()) return@launch
        config.save(ModelRefEntity(id = newId(), providerRef = providerRef, modelId = modelId.trim(), function = function))
        load()
    }

    fun deleteModel(id: String) = scope.launch { config.deleteModelRef(id); load() }

    fun createPrompt(name: String, text: String) = scope.launch {
        if (text.isBlank()) return@launch
        config.save(PromptV3Entity(id = newId(), name = name.trim().ifBlank { "New prompt" }, text = text.trim()))
        load()
    }

    fun deletePrompt(id: String) = scope.launch { config.deletePrompt(id); load() }

    private fun load() {
        _state.value = ConfigUiState(
            profiles = config.profiles(),
            providers = config.providerConfigs(),
            models = config.modelRefs(),
            prompts = config.prompts(),
            activeProfileId = activeProfileStore.get(),
        )
    }
}

/**
 * The device-local active-profile pointer, abstracted from `CompanionSettings` so the ViewModel is
 * testable without a settings table. Production wires it to `CompanionSettings::activeProfileId`.
 */
interface ActiveProfileStore {
    fun get(): String?
    fun set(id: String?)
}
