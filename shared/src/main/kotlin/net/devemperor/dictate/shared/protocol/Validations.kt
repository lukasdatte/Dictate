package net.devemperor.dictate.shared.protocol

import io.konform.validation.Validation
import io.konform.validation.ValidationBuilder
import io.konform.validation.onEach
import io.konform.validation.constraints.maxItems
import io.konform.validation.constraints.maxLength
import io.konform.validation.constraints.minLength
import io.konform.validation.constraints.minimum
import io.konform.validation.constraints.pattern

/**
 * The value constraints of every wire type — one `Validation<T>` per DTO, right next to it.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015).
 *
 * kotlinx-serialization owns the *shape* (types, required fields, enum names); Konform owns the
 * *values* (lengths, ranges, formats). Both live here, both are applied by [ProtocolCodec] on
 * the way out **and** on the way in — so every payload is validated twice, once by the sender
 * and once by the receiver, and neither side can skip it (ADR-0016).
 */
object Validations {

    /**
     * Crockford Base32 — the digits plus the letters minus `I`, `L`, `O`, `U`, which are the ones
     * a human misreads. The pairing token is typed by hand as often as it is scanned.
     */
    private val TOKEN_PATTERN = Regex("^[0-9A-HJKMNP-TV-Z]{${Endpoints.PAIRING_TOKEN_LENGTH},${Endpoints.MAX_PAIRING_TOKEN_LENGTH}}$")

    /** A UUIDv4 fits, and so does anything else opaque and printable the phone may generate later. */
    private val DEVICE_ID_PATTERN = Regex("^[A-Za-z0-9._:-]{${Endpoints.MIN_DEVICE_ID_LENGTH},${Endpoints.MAX_DEVICE_ID_LENGTH}}$")

    // ── Requests ────────────────────────────────────────────────────────────────────────

    val pairRequest = Validation<PairRequest> {
        PairRequest::protocolVersion { supportedProtocol() }
        PairRequest::pairingToken { pattern(TOKEN_PATTERN) }
        PairRequest::deviceId { pattern(DEVICE_ID_PATTERN) }
        PairRequest::deviceName {
            minLength(1)
            maxLength(Endpoints.MAX_DEVICE_NAME_LENGTH)
        }
    }

    val dispatchRequest = Validation<DispatchRequest> {
        DispatchRequest::protocolVersion { supportedProtocol() }
        DispatchRequest::sessionId {
            minLength(1)
            maxLength(Endpoints.MAX_SESSION_ID_LENGTH)
        }
        DispatchRequest::text {
            minLength(1)
            maxLength(Endpoints.MAX_TEXT_LENGTH)
        }
        DispatchRequest::createdAt { minimum(0L) }
    }

    val syncRequest = Validation<SyncRequest> {
        SyncRequest::protocolVersion { supportedProtocol() }
        SyncRequest::items {
            maxItems(Endpoints.MAX_SYNC_BATCH)
            // Konform prefixes each element error with `items[i]`, so a bad row in a 200-row page
            // is pinpointed rather than sinking the page anonymously.
            onEach {
                SessionUpsert::sessionId {
                    minLength(1)
                    maxLength(Endpoints.MAX_SESSION_ID_LENGTH)
                }
                // No minLength here (unlike a dispatch): the sync mirrors the history as it is,
                // and an empty text is the phone's business, not a protocol violation.
                SessionUpsert::text { maxLength(Endpoints.MAX_TEXT_LENGTH) }
                SessionUpsert::createdAt { minimum(0L) }
            }
        }
    }

    // ── Responses ───────────────────────────────────────────────────────────────────────

    val pairResponse = Validation<PairResponse> {
        PairResponse::protocolVersion { supportedProtocol() }
        PairResponse::deviceId { pattern(DEVICE_ID_PATTERN) }
        PairResponse::deviceSecret { minLength(32) }
        PairResponse::serverName { minLength(1) }
    }

    val dispatchResponse = Validation<DispatchResponse> {
        DispatchResponse::protocolVersion { supportedProtocol() }
        DispatchResponse::sessionId {
            minLength(1)
            maxLength(Endpoints.MAX_SESSION_ID_LENGTH)
        }
    }

    val cursorResponse = Validation<CursorResponse> {
        CursorResponse::protocolVersion { supportedProtocol() }
    }

    val syncResponse = Validation<SyncResponse> {
        SyncResponse::protocolVersion { supportedProtocol() }
        SyncResponse::accepted { minimum(0) }
    }

    val healthResponse = Validation<HealthResponse> {
        HealthResponse::protocolVersion { supportedProtocol() }
        HealthResponse::serverName { minLength(1) }
    }

    /**
     * Deliberately empty.
     *
     * An error envelope is the peer *complaining*, and we must always be able to read the
     * complaint — including "your protocol version is unsupported", which by definition arrives
     * from a peer whose version we do not accept. Validating it would let a protocol mismatch
     * swallow the very message that explains the mismatch.
     */
    val errorEnvelope = Validation<ErrorEnvelope> { }

    /**
     * The version rule in ONE place — every DTO reuses it.
     *
     * `{value}` is interpolated by Konform with the **validated value itself**. That is fine for
     * an Int version and forbidden everywhere else: on a payload-bearing field it would copy the
     * dictated text into the error message, and from there into both sides' logs. Never put
     * `{value}` in a constraint on `text`, `deviceSecret` or `pairingToken`.
     */
    private fun ValidationBuilder<Int>.supportedProtocol() =
        constrain("unsupported protocol version {value}") { ProtocolVersion.isSupported(it) }
}
