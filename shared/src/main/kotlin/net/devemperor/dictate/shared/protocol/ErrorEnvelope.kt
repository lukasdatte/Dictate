package net.devemperor.dictate.shared.protocol

import kotlinx.serialization.Serializable

/**
 * The one error format for **every** non-2xx response of either side.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015).
 *
 * The server maps every exception onto this in a single StatusPages handler; the client parses
 * every failure with a single parser. One shape, one classifier — that is what lets
 * `DispatchError` be an exhaustive `sealed class` instead of a pile of status-code guesses.
 */

@Serializable
enum class ErrorCode {
    /** 400 — the peer speaks a protocol version we cannot serve. Checked before auth. */
    PROTOCOL_VERSION_UNSUPPORTED,

    /** 400 — well-formed JSON, but out of contract. `details` carries the property paths. */
    VALIDATION_FAILED,

    /** 401 — unknown device or wrong secret. Deliberately does not say which. */
    UNAUTHORIZED,

    /** 401 — the presented pairing token was never issued. */
    INVALID_TOKEN,

    /** 401 — the pairing token is older than [Endpoints.PAIRING_TOKEN_TTL_MILLIS]. */
    TOKEN_EXPIRED,

    /** 409 — the pairing token was already redeemed. One token, one device. */
    TOKEN_CONSUMED,

    /** 503 — the companion is alive but could not place the text anywhere. */
    INSERTION_FAILED,

    /**
     * 404 — the requested catalog entity is unknown OR not shared.
     *
     * One code for both, on purpose: telling them apart would leak which private entities exist
     * (parallel to the uniform 401). The `CatalogClient` maps it to `DispatchError.EntityGone`
     * (peer-katalog.md §6.4); a *bare* 404 with no envelope means "no catalog route" → `EndpointMissing`.
     */
    CATALOG_ENTITY_NOT_FOUND,

    /** 500 — anything unforeseen. */
    INTERNAL,
}

/** One violated constraint. [path] is the property path, e.g. `text` or `items[3].sessionId`. */
@Serializable
data class ValidationDetail(
    val path: String,
    val message: String,
)

@Serializable
data class ErrorEnvelope(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    val code: ErrorCode,
    /**
     * Human-readable, English, safe to log.
     *
     * NEVER contains the dictated text or a secret — this envelope is logged on both sides.
     * The same rule binds [details]: a constraint message may name the *limit* ("must have at
     * most 100000 characters") but never the *value*. `ErrorEnvelopeRedactionTest` pins it.
     */
    val message: String,
    val details: List<ValidationDetail> = emptyList(),
)
