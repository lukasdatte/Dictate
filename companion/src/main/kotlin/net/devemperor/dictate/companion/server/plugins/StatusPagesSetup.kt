package net.devemperor.dictate.companion.server.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.statuspages.StatusPages
import net.devemperor.dictate.companion.domain.CompanionException
import net.devemperor.dictate.companion.server.respondEnvelope
import net.devemperor.dictate.shared.protocol.ErrorCode

/**
 * THE error mapper — one exception type, one `ErrorCode`, one status, one place.
 *
 * Routes throw; they never build an error response themselves. That is what makes the client's
 * `DispatchError` an exhaustive `sealed class` instead of a pile of status-code guesses: every
 * failure this server can produce leaves through this function, in the one envelope format both
 * sides agreed on (ADR-0016).
 *
 * The catch-all arm logs the cause and answers `INTERNAL` — an unforeseen exception must never leak
 * a stack trace, a file path or a dictated text onto the wire.
 */
fun Application.installStatusPages() = install(StatusPages) {

    exception<CompanionException.ProtocolVersionException> { call, cause ->
        call.respondEnvelope(HttpStatusCode.BadRequest, ErrorCode.PROTOCOL_VERSION_UNSUPPORTED, cause.message.orEmpty())
    }
    exception<CompanionException.ValidationException> { call, cause ->
        call.respondEnvelope(HttpStatusCode.BadRequest, ErrorCode.VALIDATION_FAILED, "payload rejected", cause.details)
    }
    exception<CompanionException.UnauthorizedException> { call, _ ->
        call.respondEnvelope(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED, "unauthorized")
    }
    exception<CompanionException.InvalidTokenException> { call, _ ->
        call.respondEnvelope(HttpStatusCode.Unauthorized, ErrorCode.INVALID_TOKEN, "invalid pairing token")
    }
    exception<CompanionException.TokenExpiredException> { call, _ ->
        call.respondEnvelope(HttpStatusCode.Unauthorized, ErrorCode.TOKEN_EXPIRED, "pairing token expired")
    }
    exception<CompanionException.TokenConsumedException> { call, _ ->
        call.respondEnvelope(HttpStatusCode.Conflict, ErrorCode.TOKEN_CONSUMED, "pairing token already used")
    }
    exception<CompanionException.InsertionFailedException> { call, _ ->
        call.respondEnvelope(HttpStatusCode.ServiceUnavailable, ErrorCode.INSERTION_FAILED, "could not insert text")
    }
    exception<CompanionException.CatalogEntityNotFoundException> { call, _ ->
        call.respondEnvelope(HttpStatusCode.NotFound, ErrorCode.CATALOG_ENTITY_NOT_FOUND, "catalog entity not found")
    }
    exception<Throwable> { call, cause ->
        call.application.log.error("unhandled exception on ${call.request.local.uri}", cause)
        call.respondEnvelope(HttpStatusCode.InternalServerError, ErrorCode.INTERNAL, "internal error")
    }
}
