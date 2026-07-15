package net.devemperor.dictate.companion.ui

import net.devemperor.dictate.companion.data.memory.InMemoryChordMapping
import net.devemperor.dictate.companion.domain.model.ChordModifier
import net.devemperor.dictate.companion.domain.model.DefaultChords
import net.devemperor.dictate.companion.domain.model.KeyChord
import net.devemperor.dictate.companion.domain.model.KeyCommand
import net.devemperor.dictate.companion.ui.settings.ChordLabels
import net.devemperor.dictate.companion.ui.settings.ChordSettingsViewModel
import net.devemperor.dictate.companion.ui.settings.KeyCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChordSettingsViewModelTest {

    private val repository = InMemoryChordMapping()
    private val viewModel = ChordSettingsViewModel(repository)

    private fun row(command: KeyCommand) = viewModel.state.value.rows.single { it.command == command }

    @Test
    fun theInitialState_showsEveryCommandAtItsDefault() {
        assertEquals(KeyCommand.entries.size, viewModel.state.value.rows.size)
        KeyCommand.entries.forEach { assertEquals(DefaultChords.chordFor(it), row(it).chord) }
        assertEquals("Ctrl + Y", row(KeyCommand.REDO).label)
        assertEquals("Ctrl + Shift + ←", row(KeyCommand.CURSOR_WORD_SELECT_BACK).label)
    }

    @Test
    fun rebind_changesTheChord_andPersistsThroughTheRepository() {
        viewModel.rebind(KeyCommand.REDO, setOf(ChordModifier.CTRL, ChordModifier.SHIFT), vk = 0x5A) // Ctrl+Shift+Z

        assertNull(viewModel.state.value.error)
        assertEquals(KeyChord(setOf(ChordModifier.CTRL, ChordModifier.SHIFT), 0x5A), row(KeyCommand.REDO).chord)
        assertEquals(KeyChord(setOf(ChordModifier.CTRL, ChordModifier.SHIFT), 0x5A), repository.chordFor(KeyCommand.REDO))
    }

    @Test
    fun rebind_toABareModifier_isRejected_andNothingChanges() {
        viewModel.rebind(KeyCommand.REDO, setOf(ChordModifier.CTRL), vk = ChordModifier.CTRL.vk)

        assertNotNull(viewModel.state.value.error)
        assertEquals(DefaultChords.chordFor(KeyCommand.REDO), repository.chordFor(KeyCommand.REDO))
    }

    @Test
    fun rebind_toAVkOutsideTheValidRange_isRejected() {
        viewModel.rebind(KeyCommand.REDO, setOf(ChordModifier.CTRL), vk = 300)

        assertNotNull(viewModel.state.value.error)
        assertEquals(DefaultChords.chordFor(KeyCommand.REDO), repository.chordFor(KeyCommand.REDO))
    }

    @Test
    fun reset_restoresEveryDefault() {
        viewModel.rebind(KeyCommand.REDO, setOf(ChordModifier.CTRL, ChordModifier.SHIFT), vk = 0x5A)

        viewModel.resetToDefaults()

        KeyCommand.entries.forEach { assertEquals(DefaultChords.chordFor(it), row(it).chord) }
    }

    @Test
    fun startEditing_togglesTheArmedCommand() {
        viewModel.startEditing(KeyCommand.COPY)
        assertEquals(KeyCommand.COPY, viewModel.state.value.editing)

        viewModel.startEditing(KeyCommand.COPY)
        assertNull(viewModel.state.value.editing)
    }

    @Test
    fun keyCapture_fixesEnter_butPassesLettersThrough() {
        assertEquals(0x0D, KeyCapture.win32VkFor(0x0A)) // AWT VK_ENTER → Win32 VK_RETURN
        assertEquals(0x41, KeyCapture.win32VkFor(0x41)) // 'A' is the same on both
    }

    @Test
    fun chordLabels_describeCommand_isHumanReadable() {
        assertEquals("Cursor Word Select Back", ChordLabels.describe(KeyCommand.CURSOR_WORD_SELECT_BACK))
        assertEquals("Select All", ChordLabels.describe(KeyCommand.SELECT_ALL))
    }
}
