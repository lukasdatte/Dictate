package net.devemperor.dictate.windows

import net.devemperor.dictate.shared.client.DispatchError
import net.devemperor.dictate.state.PipelineErrorKind

/**
 * The ONE place that classifies a [DispatchError] into a user-facing [PipelineErrorKind] (ADR-0019).
 *
 * Centralised on purpose (Gate-2 finding G2-9 was a threefold-duplicated error classification): the
 * IME seam, the headless sink, and the history-row send all reach the state through this single
 * mapping, so the three paths cannot drift on what "unreachable" vs "unauthorized" means.
 *
 * Only two outcomes matter to the phone: a 401-family error (the pairing is invalid → the user must
 * re-pair) maps to [PipelineErrorKind.WINDOWS_UNAUTHORIZED]; everything else — unreachable, timeout,
 * 5xx, a contract violation, an insertion failure — maps to [PipelineErrorKind.WINDOWS_UNREACHABLE],
 * a dismiss-only error with the text safely held as a pending part.
 */
object DispatchOutcomeMapper {

    fun toErrorKind(error: DispatchError): PipelineErrorKind = when (error) {
        DispatchError.Unauthorized,
        DispatchError.TokenInvalid,
        DispatchError.TokenExpired,
        DispatchError.TokenConsumed,
        -> PipelineErrorKind.WINDOWS_UNAUTHORIZED

        is DispatchError.Unreachable,
        is DispatchError.Invalid,
        DispatchError.ProtocolMismatch,
        DispatchError.InsertionFailed,
        is DispatchError.Server,
        -> PipelineErrorKind.WINDOWS_UNREACHABLE
    }
}
