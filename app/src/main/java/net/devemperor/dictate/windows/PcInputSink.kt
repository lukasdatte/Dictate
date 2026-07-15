package net.devemperor.dictate.windows

import net.devemperor.dictate.state.insertion.KeyboardAction
import net.devemperor.dictate.state.insertion.KeyboardActionSink
import net.devemperor.dictate.state.insertion.SubmitResult
import net.devemperor.dictate.state.insertion.UnsupportedReason

/**
 * The PC sink (§4.2): maps a [KeyboardAction] to its wire command and hands it to the
 * [PcInputCoordinator]'s send window, returning immediately.
 *
 * No blocking, no UI-thread network I/O — [submit] returns [SubmitResult.Accepted] the moment the
 * action is buffered; the network result surfaces later through the coordinator's failure callback.
 * An IC-read-bound op that has no PC meaning (cursor nudge, set-selection) is reported
 * [SubmitResult.Unsupported] instead of being silently swallowed.
 */
class PcInputSink(
    private val coordinator: PcInputCoordinator,
) : KeyboardActionSink {

    override fun submit(action: KeyboardAction): SubmitResult {
        val command = PcInputCommandMapper.toCommand(action)
            ?: return SubmitResult.Unsupported(UnsupportedReason.OP_NOT_ROUTABLE)
        coordinator.submit(listOf(command))
        return SubmitResult.Accepted
    }
}
