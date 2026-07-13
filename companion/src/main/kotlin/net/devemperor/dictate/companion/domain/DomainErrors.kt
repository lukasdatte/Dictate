package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.shared.protocol.ValidationDetail

/**
 * The typed failures of the domain — every one of them has exactly one `ErrorCode` + status.
 *
 * Services throw these; **nothing** in `domain/` ever builds an HTTP response. The mapping lives
 * in a single place (`server/plugins/StatusPagesSetup.kt`), which is what keeps the `ErrorEnvelope`
 * constructible at exactly one point on this side of the wire (ADR-0016).
 *
 * None of these messages may carry a dictated text or a secret: they are logged on both sides.
 */
sealed class CompanionException(message: String) : RuntimeException(message) {

    /** 400 `PROTOCOL_VERSION_UNSUPPORTED` — checked before auth and before validation. */
    class ProtocolVersionException(val presented: String) :
        CompanionException("unsupported protocol version $presented")

    /** 400 `VALIDATION_FAILED` — well-formed enough to read, out of contract. */
    class ValidationException(val details: List<ValidationDetail>) :
        CompanionException("payload rejected")

    /** 401 `UNAUTHORIZED` — unknown device or wrong secret. Deliberately does not say which. */
    class UnauthorizedException : CompanionException("unauthorized")

    /** 401 `INVALID_TOKEN` — this pairing token was never issued (or a newer one replaced it). */
    class InvalidTokenException : CompanionException("invalid pairing token")

    /** 401 `TOKEN_EXPIRED` — older than [net.devemperor.dictate.shared.protocol.Endpoints.PAIRING_TOKEN_TTL_MILLIS]. */
    class TokenExpiredException : CompanionException("pairing token expired")

    /** 409 `TOKEN_CONSUMED` — one token, one device. */
    class TokenConsumedException : CompanionException("pairing token already used")

    /** 503 `INSERTION_FAILED` — alive, but the text could not be placed anywhere. */
    class InsertionFailedException : CompanionException("could not insert text")
}
