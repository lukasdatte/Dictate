package net.devemperor.dictate.companion.domain.port

import net.devemperor.dictate.companion.domain.model.InputCommand
import net.devemperor.dictate.companion.domain.model.InputOutcome

/**
 * Performs a batch of keyboard actions on this PC — the input-side counterpart of [TextInserter].
 *
 * Windows-only in V1 (`Win32InputPerformer`); every other OS gets a no-op that reports
 * [available] = false, so the companion still **runs and is testable** on Linux and `/v1/health`
 * honestly says `supportsInputCommands = false` (§5.3).
 */
interface InputCommandPerformer {

    /** Executes [commands] in order. Returns the first non-success as the batch outcome (fail-fast). */
    fun perform(commands: List<InputCommand>): InputOutcome

    /** false → the companion advertises no `/v1/input` capability and the phone warns while pairing. */
    val available: Boolean
}
