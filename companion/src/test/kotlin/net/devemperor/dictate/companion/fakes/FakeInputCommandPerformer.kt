package net.devemperor.dictate.companion.fakes

import net.devemperor.dictate.companion.domain.model.InputCommand
import net.devemperor.dictate.companion.domain.model.InputOutcome
import net.devemperor.dictate.companion.domain.port.InputCommandPerformer

/**
 * A programmable input performer. Hand-written, per house style — no mock library.
 *
 * [received] is what makes the input E2E worth its runtime: it proves the commands that left the
 * phone reached the Win32 boundary in the same order and shape. Everything past that boundary is
 * Windows' business and lives on the checklist.
 */
class FakeInputCommandPerformer(
    var nextOutcome: InputOutcome = InputOutcome.SENT,
    override var available: Boolean = true,
) : InputCommandPerformer {

    val received = mutableListOf<List<InputCommand>>()

    override fun perform(commands: List<InputCommand>): InputOutcome {
        synchronized(received) { received += commands }
        return nextOutcome
    }
}
