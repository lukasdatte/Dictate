package net.devemperor.dictate.windows

import net.devemperor.dictate.shared.client.DispatchError
import net.devemperor.dictate.shared.client.DispatchResult
import net.devemperor.dictate.shared.protocol.InputCommandResponse
import net.devemperor.dictate.shared.protocol.InputOutcomeWire

/**
 * Why a batch of keyboard actions did not reach the PC — the classification the InfoBar reads (§6.1).
 *
 * Distinct from a dictation's `WINDOWS_UNREACHABLE`: a keyboard action is **not** held as a pending
 * part (Entscheidung 4), so its message must say "not performed", not "held for later".
 */
enum class PcInputFailure {
    /** Network failure / timeout / 5xx — "PC unreachable — action not performed". Opens the circuit. */
    UNREACHABLE,

    /** 404 — the companion is too old to serve `/v1/input`. "Companion update required". Opens the circuit. */
    COMPANION_UPDATE_REQUIRED,

    /** 401 — not (or no longer) paired. Reuses the existing "pair again" flow. Opens the circuit. */
    UNAUTHORIZED,

    /**
     * The companion answered 200 but `executed = false` (no foreground window / UIPI). The link is
     * fine, so this does **not** open the circuit — the next action may well land.
     */
    NOT_PERFORMED,
}

/** The coordinator's view of one send attempt. */
sealed interface PcSendResult {
    /** Every command was injected on the PC. */
    data object Sent : PcSendResult

    /** The batch failed. [opensCircuit] suppresses further attempts for the cooldown (network faults only). */
    data class Failed(val failure: PcInputFailure, val opensCircuit: Boolean) : PcSendResult
}

/**
 * Maps the shared [DispatchResult] of a `/v1/input` call onto a [PcSendResult] (mirrors
 * `DispatchOutcomeMapper` for dictation). One place, exhaustive, so a new [DispatchError] must be
 * classified deliberately.
 */
object PcInputOutcomeMapper {

    fun classify(result: DispatchResult<InputCommandResponse>): PcSendResult = when (result) {
        is DispatchResult.Success ->
            if (result.value.executed && result.value.outcome == InputOutcomeWire.SENT) {
                PcSendResult.Sent
            } else {
                // 200 but not injected (no foreground / UIPI). Link is up → no circuit cooldown.
                PcSendResult.Failed(PcInputFailure.NOT_PERFORMED, opensCircuit = false)
            }

        is DispatchResult.Failure -> when (result.error) {
            is DispatchError.EndpointMissing -> PcSendResult.Failed(PcInputFailure.COMPANION_UPDATE_REQUIRED, opensCircuit = true)
            is DispatchError.Unauthorized,
            is DispatchError.TokenInvalid,
            is DispatchError.TokenExpired,
            is DispatchError.TokenConsumed -> PcSendResult.Failed(PcInputFailure.UNAUTHORIZED, opensCircuit = true)
            // Unreachable, Server, Invalid, ProtocolMismatch, InsertionFailed → all "can't reach it now".
            else -> PcSendResult.Failed(PcInputFailure.UNREACHABLE, opensCircuit = true)
        }
    }
}
