package net.devemperor.dictate.companion.platform.windows

import net.devemperor.dictate.companion.domain.model.InputCommand
import net.devemperor.dictate.companion.domain.model.InputOutcome
import net.devemperor.dictate.companion.domain.model.KeyChord
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.port.ChordMappingRepository
import net.devemperor.dictate.companion.domain.port.InputCommandPerformer
import net.devemperor.dictate.companion.domain.port.TextInserter

/**
 * Turns semantic input commands into `SendInput` keystrokes on Windows (§5.3).
 *
 * **Plain Kotlin, Linux-testable** — the same seam pattern as `Win32TextInserter`: all the policy
 * (chord resolution, modifier bracketing, UIPI detection, fail-fast) lives here and is exercised by
 * the Linux suite; only the raw `SendInput` sits behind [Win32Keyboard].
 *
 * Chord resolution goes through **one** door — the [ChordMappingRepository] (§5.3 SSoT). There are
 * no VK literals in this file: a `BACKSPACE` becomes whatever the repository (default or
 * user-configured) says it is. `TYPE_TEXT` is not a chord — it is delegated to the existing
 * [TextInserter] (Ctrl+V, ADR-0018), which is not user-configurable.
 */
class Win32InputPerformer(
    private val keyboard: Win32Keyboard,
    private val textInserter: TextInserter,
    private val chords: ChordMappingRepository,
) : InputCommandPerformer {

    override val available: Boolean = true

    override fun perform(commands: List<InputCommand>): InputOutcome {
        // Probe once up front: SendInput delivers to whatever has focus at delivery time, so if there
        // is no foreground window at all, nothing in the batch can land — say so without touching it.
        if (!keyboard.hasForegroundWindow()) return InputOutcome.NO_FOREGROUND_WINDOW

        for (command in commands) {
            val outcome = when (command) {
                is InputCommand.Type -> perform(command)
                is InputCommand.Key -> perform(command)
            }
            // Fail-fast: a partial UIPI rejection on any command aborts the batch — a half-applied
            // sequence of cursor moves and deletions on the PC would be worse than a clean failure.
            if (outcome != InputOutcome.SENT) return outcome
        }
        return InputOutcome.SENT
    }

    private fun perform(command: InputCommand.Type): InputOutcome = when (textInserter.insert(command.text)) {
        InsertionOutcome.TYPED_CTRL_V -> InputOutcome.SENT
        // The text reached the clipboard but was not typed (UIPI, or focus lost mid-batch): for a
        // keyboard action there is no "partial success", so it is a rejection the phone must show.
        InsertionOutcome.CLIPBOARD_ONLY -> InputOutcome.REJECTED
        InsertionOutcome.FAILED -> InputOutcome.REJECTED
    }

    private fun perform(command: InputCommand.Key): InputOutcome {
        val events = chords.chordFor(command.command).toKeyEvents()
        // A coalesced burst (count) replays the whole chord that many times — modifiers bracket each
        // press so a held Ctrl never leaks past the command.
        repeat(command.count) {
            val accepted = keyboard.sendKeySequence(events)
            if (accepted < events.size) return InputOutcome.REJECTED
        }
        return InputOutcome.SENT
    }
}

/**
 * Builds the injectable event list for a chord: modifier downs in a stable order, the key down, the
 * key up, then the modifier ups in reverse (proper nesting so the OS never sees a dangling modifier).
 *
 * Stable order = [net.devemperor.dictate.companion.domain.model.ChordModifier] declaration order,
 * so the sequence is deterministic and unit-assertable.
 */
fun KeyChord.toKeyEvents(): List<KeyEventSpec> {
    val orderedModifiers = modifiers.sortedBy { it.ordinal }
    val downs = orderedModifiers.map { KeyEventSpec(it.vk, keyUp = false) } + KeyEventSpec(vk, keyUp = false)
    val ups = listOf(KeyEventSpec(vk, keyUp = true)) + orderedModifiers.asReversed().map { KeyEventSpec(it.vk, keyUp = true) }
    return downs + ups
}
