package net.devemperor.dictate.windows

import net.devemperor.dictate.shared.client.DispatchError
import net.devemperor.dictate.shared.protocol.ValidationDetail
import net.devemperor.dictate.state.PipelineErrorKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [DispatchOutcomeMapper] — the ONE place a [DispatchError] becomes a
 * [PipelineErrorKind] (ADR-0019). A 401-family error means "re-pair"; everything else is a
 * dismiss-only "unreachable" with the text held as a pending part.
 */
class DispatchOutcomeMapperTest {

    @Test
    fun `401-family errors map to WINDOWS_UNAUTHORIZED`() {
        listOf(
            DispatchError.Unauthorized,
            DispatchError.TokenInvalid,
            DispatchError.TokenExpired,
            DispatchError.TokenConsumed,
        ).forEach {
            assertEquals(PipelineErrorKind.WINDOWS_UNAUTHORIZED, DispatchOutcomeMapper.toErrorKind(it))
        }
    }

    @Test
    fun `everything else maps to WINDOWS_UNREACHABLE`() {
        listOf(
            DispatchError.Unreachable("timeout"),
            DispatchError.Invalid(listOf(ValidationDetail("text", "too long"))),
            DispatchError.ProtocolMismatch,
            DispatchError.InsertionFailed,
            DispatchError.Server(500, "boom"),
        ).forEach {
            assertEquals(PipelineErrorKind.WINDOWS_UNREACHABLE, DispatchOutcomeMapper.toErrorKind(it))
        }
    }
}
