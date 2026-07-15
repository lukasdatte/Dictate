package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.domain.model.toDomain
import net.devemperor.dictate.companion.domain.model.toWire
import net.devemperor.dictate.companion.domain.model.InputOutcome
import net.devemperor.dictate.companion.domain.port.InputCommandPerformer
import net.devemperor.dictate.shared.protocol.InputCommandRequest
import net.devemperor.dictate.shared.protocol.InputCommandResponse

/**
 * Executes a batch of keyboard actions on this PC (`POST /v1/input`, §5.3).
 *
 * Ephemeral by design — **no history, no sync** row (D3): unlike a dictation, a keyboard action is
 * not content worth archiving, and flooding the history with single keystrokes would drown it.
 *
 * The 200 is NOT an unconditional success: `executed` mirrors `delivered` (ADR-0017) — only a fully
 * injected batch is `executed = true`. A missing foreground window or a UIPI rejection returns 200
 * with `executed = false` and the reason, and the phone shows a failure (Entscheidung 4).
 */
class InputCommandService(
    private val performer: InputCommandPerformer,
) {

    fun perform(request: InputCommandRequest): InputCommandResponse {
        val outcome = performer.perform(request.commands.map { it.toDomain() })
        return InputCommandResponse(
            executed = outcome == InputOutcome.SENT,
            outcome = outcome.toWire(),
        )
    }
}
