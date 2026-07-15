package net.devemperor.dictate.companion.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.devemperor.dictate.companion.domain.model.ChordModifier
import net.devemperor.dictate.companion.domain.model.KeyChord
import net.devemperor.dictate.companion.domain.model.KeyCommand
import net.devemperor.dictate.companion.domain.port.ChordMappingRepository

/** One command's current binding, ready to render: the command, its chord, and a human label. */
data class ChordRowUi(
    val command: KeyCommand,
    val chord: KeyChord,
    val label: String,
)

data class ChordSettingsUiState(
    val rows: List<ChordRowUi>,
    /** Non-null → the last rebind attempt was invalid (rendered in the row's supportingText). */
    val error: String? = null,
    /** Which command's capture field is armed, or null when none is being edited. */
    val editing: KeyCommand? = null,
)

/**
 * The brain of the "Keyboard shortcuts" settings section (D6, §5.4).
 *
 * A **dedicated** view-model, not another arm of [SettingsViewModel]: chord editing and bind-address
 * selection share nothing, and folding them together would violate SRP (the bind VM is already the
 * substance of one screen). All logic here is pure — the Compose layer only captures a key event and
 * calls [rebind]; every validation lives where a JVM test can reach it.
 *
 * The state always reflects the repository (which falls back to `DefaultChords` for a missing row),
 * so a fresh install shows the defaults with no seed required.
 */
class ChordSettingsViewModel(
    private val repository: ChordMappingRepository,
) {

    private val _state = MutableStateFlow(load())
    val state: StateFlow<ChordSettingsUiState> = _state.asStateFlow()

    /** Arms the capture field for [command] (a second call toggles it off). */
    fun startEditing(command: KeyCommand) {
        _state.value = _state.value.copy(editing = if (_state.value.editing == command) null else command, error = null)
    }

    /**
     * Rebinds [command] to the captured [modifiers] + [vk].
     *
     * Rejected — with an error, without persisting — when the key is out of the valid VK range or is
     * itself a bare modifier (a chord must have a real main key, or "Ctrl" alone would shadow every
     * Ctrl-combo).
     */
    fun rebind(command: KeyCommand, modifiers: Set<ChordModifier>, vk: Int) {
        if (vk in MODIFIER_VKS) {
            _state.value = load().copy(error = "Press a key together with the modifiers.")
            return
        }
        val chord = try {
            KeyChord(modifiers = modifiers, vk = vk)
        } catch (e: IllegalArgumentException) {
            _state.value = load().copy(error = e.message)
            return
        }
        repository.update(command, chord)
        _state.value = load()
    }

    /** Restores every command to its `DefaultChords` value. */
    fun resetToDefaults() {
        repository.resetToDefaults()
        _state.value = load()
    }

    private fun load(): ChordSettingsUiState =
        ChordSettingsUiState(
            rows = KeyCommand.entries.map { command ->
                val chord = repository.chordFor(command)
                ChordRowUi(command, chord, ChordLabels.describe(chord))
            },
        )

    private companion object {
        val MODIFIER_VKS: Set<Int> = ChordModifier.entries.map { it.vk }.toSet()
    }
}
