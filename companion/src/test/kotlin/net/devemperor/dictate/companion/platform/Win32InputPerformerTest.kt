package net.devemperor.dictate.companion.platform

import net.devemperor.dictate.companion.data.memory.InMemoryChordMapping
import net.devemperor.dictate.companion.domain.model.ChordModifier
import net.devemperor.dictate.companion.domain.model.InputCommand
import net.devemperor.dictate.companion.domain.model.InputOutcome
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.model.KeyChord
import net.devemperor.dictate.companion.domain.model.KeyCommand
import net.devemperor.dictate.companion.fakes.FakeTextInserter
import net.devemperor.dictate.companion.platform.windows.KeyEventSpec
import net.devemperor.dictate.companion.platform.windows.Win32InputPerformer
import net.devemperor.dictate.companion.platform.windows.Win32Keyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The input-command policy, every branch of it — on Linux.
 *
 * The `Win32Keyboard` seam again: `SendInput` cannot run here, but the decisions built on its result
 * (chord → key-event ordering, modifier bracketing, count coalescing, UIPI fail-fast, foreground
 * probe, TYPE_TEXT delegation) are plain Kotlin and are exactly where the bugs live.
 */
class Win32InputPerformerTest {

    private val keyboard = CapturingKeyboard()
    private val inserter = FakeTextInserter()
    private val chords = InMemoryChordMapping()
    private val performer = Win32InputPerformer(keyboard, inserter, chords)

    // ── Chord resolution & ordering ───────────────────────────────────────────────────────

    @Test
    fun aModifierChord_bracketsModifiersAroundTheKey() {
        // Default UNDO = Ctrl+Z (0x11 + 0x5A): Ctrl down, Z down, Z up, Ctrl up.
        val outcome = performer.perform(listOf(InputCommand.Key(KeyCommand.UNDO, count = 1)))

        assertEquals(InputOutcome.SENT, outcome)
        assertEquals(
            listOf(
                KeyEventSpec(0x11, keyUp = false),
                KeyEventSpec(0x5A, keyUp = false),
                KeyEventSpec(0x5A, keyUp = true),
                KeyEventSpec(0x11, keyUp = true),
            ),
            keyboard.sequences.single(),
        )
    }

    @Test
    fun aTwoModifierChord_upsAreReverseOfDowns() {
        // Default word-select back = Ctrl+Shift+Left. Modifier order is enum-declaration order
        // (CTRL then SHIFT); the ups mirror it in reverse.
        performer.perform(listOf(InputCommand.Key(KeyCommand.CURSOR_WORD_SELECT_BACK, count = 1)))

        assertEquals(
            listOf(
                KeyEventSpec(ChordModifier.CTRL.vk, keyUp = false),
                KeyEventSpec(ChordModifier.SHIFT.vk, keyUp = false),
                KeyEventSpec(0x25, keyUp = false), // VK_LEFT
                KeyEventSpec(0x25, keyUp = true),
                KeyEventSpec(ChordModifier.SHIFT.vk, keyUp = true),
                KeyEventSpec(ChordModifier.CTRL.vk, keyUp = true),
            ),
            keyboard.sequences.single(),
        )
    }

    @Test
    fun aModifierlessChord_isJustDownThenUp() {
        performer.perform(listOf(InputCommand.Key(KeyCommand.BACKSPACE, count = 1)))

        assertEquals(
            listOf(KeyEventSpec(0x08, keyUp = false), KeyEventSpec(0x08, keyUp = true)),
            keyboard.sequences.single(),
        )
    }

    @Test
    fun aCount_replaysTheChordThatManyTimes() {
        performer.perform(listOf(InputCommand.Key(KeyCommand.CURSOR_LEFT, count = 3)))

        assertEquals(3, keyboard.sequences.size)
        keyboard.sequences.forEach { assertEquals(listOf(KeyEventSpec(0x25, false), KeyEventSpec(0x25, true)), it) }
    }

    @Test
    fun aReboundChord_changesTheInjectedSequence() {
        // The single resolution point in action: rebinding REDO changes what the performer sends,
        // with no change to the wire or the performer itself.
        chords.update(KeyCommand.REDO, KeyChord(setOf(ChordModifier.CTRL, ChordModifier.SHIFT), vk = 0x5A))

        performer.perform(listOf(InputCommand.Key(KeyCommand.REDO, count = 1)))

        assertEquals(
            listOf(
                KeyEventSpec(ChordModifier.CTRL.vk, false),
                KeyEventSpec(ChordModifier.SHIFT.vk, false),
                KeyEventSpec(0x5A, false),
                KeyEventSpec(0x5A, true),
                KeyEventSpec(ChordModifier.SHIFT.vk, true),
                KeyEventSpec(ChordModifier.CTRL.vk, true),
            ),
            keyboard.sequences.single(),
        )
    }

    // ── Outcomes ──────────────────────────────────────────────────────────────────────────

    @Test
    fun withoutAForegroundWindow_nothingIsSent() {
        keyboard.foreground = false

        val outcome = performer.perform(listOf(InputCommand.Key(KeyCommand.BACKSPACE, count = 1)))

        assertEquals(InputOutcome.NO_FOREGROUND_WINDOW, outcome)
        assertTrue("nothing may be injected without a target", keyboard.sequences.isEmpty())
    }

    @Test
    fun aUipiPartialRejection_failsFast_andAbortsTheBatch() {
        keyboard.acceptAll = false // accept fewer events than passed → UIPI

        val outcome = performer.perform(
            listOf(InputCommand.Key(KeyCommand.CURSOR_LEFT, count = 1), InputCommand.Key(KeyCommand.BACKSPACE, count = 1)),
        )

        assertEquals(InputOutcome.REJECTED, outcome)
        assertEquals("the batch aborts on the first rejection", 1, keyboard.sequences.size)
    }

    @Test
    fun aTypeText_isDelegatedToTheTextInserter() {
        val outcome = performer.perform(listOf(InputCommand.Type("hello 🎉")))

        assertEquals(InputOutcome.SENT, outcome)
        assertEquals(listOf("hello 🎉"), inserter.inserted)
        assertTrue("TYPE_TEXT is not a chord — no SendInput sequence", keyboard.sequences.isEmpty())
    }

    @Test
    fun aTypeTextThatOnlyReachedTheClipboard_isRejected() {
        inserter.nextOutcome = InsertionOutcome.CLIPBOARD_ONLY

        assertEquals(InputOutcome.REJECTED, performer.perform(listOf(InputCommand.Type("x"))))
    }

    @Test
    fun aMixedBatchInOrder_typesThenMovesThenSelects() {
        performer.perform(
            listOf(
                InputCommand.Type("hi"),
                InputCommand.Key(KeyCommand.CURSOR_LEFT, count = 2),
                InputCommand.Key(KeyCommand.SELECT_ALL, count = 1),
            ),
        )

        assertEquals(listOf("hi"), inserter.inserted)
        // 2 cursor replays + 1 select-all = 3 injected sequences.
        assertEquals(3, keyboard.sequences.size)
    }

    @Test
    fun thePerformerReportsItselfAvailable() {
        assertEquals(true, performer.available)
    }

    private class CapturingKeyboard : Win32Keyboard {
        var foreground = true
        var acceptAll = true
        val sequences = mutableListOf<List<KeyEventSpec>>()

        override fun hasForegroundWindow(): Boolean = foreground

        override fun sendCtrlV(): Int = throw UnsupportedOperationException("input performer never calls sendCtrlV")

        override fun sendKeySequence(events: List<KeyEventSpec>): Int {
            sequences += events
            return if (acceptAll) events.size else events.size - 1
        }
    }
}
