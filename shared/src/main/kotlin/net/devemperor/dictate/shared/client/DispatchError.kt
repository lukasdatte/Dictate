package net.devemperor.dictate.shared.client

import net.devemperor.dictate.shared.protocol.ValidationDetail

/**
 * Everything that can go wrong on a call to the companion, classified once.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015).
 *
 * The point of a closed hierarchy here is that the Android side can decide **exhaustively** what
 * each failure means for the user (ADR-0019): almost all of them end in the existing pending-part
 * fallback, but they differ in what the InfoBar says and whether a retry can possibly help.
 */
sealed class DispatchError {

    /**
     * No network, timeout, connection reset, truncated response.
     *
     * A timeout is **ambiguous** — the PC may well have received the text. It is classified as a
     * failure anyway, because the conservative resolution is the safe one: one pending part too
     * many (the user sees the text and places it) beats a text that vanishes silently. The
     * server's idempotent upsert makes the re-send from the history row harmless.
     */
    data class Unreachable(val cause: String) : DispatchError()

    /** 401 — not (or no longer) paired. Pending part + "pair again". */
    object Unauthorized : DispatchError()

    /** 401 — the pairing token was never issued by this companion. Pairing only. */
    object TokenInvalid : DispatchError()

    /** 401 — the pairing token is older than its TTL. Show a fresh QR. Pairing only. */
    object TokenExpired : DispatchError()

    /** 409 — the pairing token was already redeemed. One token, one device. Pairing only. */
    object TokenConsumed : DispatchError()

    /** 400 VALIDATION_FAILED — we sent something out of contract. A bug class: log it, then the pending part. */
    data class Invalid(val details: List<ValidationDetail>) : DispatchError()

    /** 400 PROTOCOL_VERSION_UNSUPPORTED — the companion is too old or too new. "Update the companion". */
    object ProtocolMismatch : DispatchError()

    /**
     * 404 — the companion does not serve this endpoint at all.
     *
     * A paired-but-old companion has no `/v1/input` route, so Ktor answers a bare 404 that is not
     * an `ErrorEnvelope`. Told apart from a generic [Server] so the app can say "update the
     * companion" for a keyboard action rather than "PC unreachable" (§5.1).
     */
    object EndpointMissing : DispatchError()

    /** 503 — the companion is alive but could not place the text. Pending part + a hint. */
    object InsertionFailed : DispatchError()

    /** Any other status, or a response we cannot parse. Pending part. */
    data class Server(val status: Int, val message: String) : DispatchError()
}

/**
 * The result of a call.
 *
 * One generic type instead of a `PairOutcome` / `DispatchOutcome` / `HealthOutcome` / … family:
 * they would differ only in their success payload, and every one of them would have to repeat the
 * same failure arm. See `SyncOutcome` for the one place where a richer, non-generic outcome earns
 * its keep — it has three *partial* successes, which this cannot express.
 */
sealed class DispatchResult<out T> {
    data class Success<T>(val value: T) : DispatchResult<T>()
    data class Failure(val error: DispatchError) : DispatchResult<Nothing>()
}

/** Unwraps a success, or hands the error to [onFailure], which must not return. */
inline fun <T> DispatchResult<T>.getOrElse(onFailure: (DispatchError) -> Nothing): T = when (this) {
    is DispatchResult.Success -> value
    is DispatchResult.Failure -> onFailure(error)
}
