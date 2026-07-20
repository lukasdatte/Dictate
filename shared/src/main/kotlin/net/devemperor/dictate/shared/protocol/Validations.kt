package net.devemperor.dictate.shared.protocol

import io.konform.validation.Validation
import io.konform.validation.ValidationBuilder
import io.konform.validation.onEach
import io.konform.validation.constraints.maxItems
import io.konform.validation.constraints.maxLength
import io.konform.validation.constraints.maximum
import io.konform.validation.constraints.minItems
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

    val inputCommandRequest = Validation<InputCommandRequest> {
        InputCommandRequest::protocolVersion { supportedProtocol() }
        InputCommandRequest::commands {
            minItems(1)
            maxItems(Endpoints.MAX_INPUT_BATCH)
            onEach {
                // text ⇔ TYPE_TEXT: a text on a cursor move, or a TYPE_TEXT without text, is a
                // malformed command. Never interpolate `{value}` here — it would copy the typed
                // text into the error message and from there into both sides' logs (redaction rule).
                constrain("text must be present iff kind is TYPE_TEXT") {
                    (it.kind == InputCommandKindWire.TYPE_TEXT) == (it.text != null)
                }
                InputCommandWire::count {
                    minimum(1)
                    maximum(Endpoints.MAX_INPUT_REPEAT)
                }
                // Length bound only when text is present (presence is owned by the ⇔ rule above).
                // A whole-element constrain, not a property block: a nullable-text property block
                // would reject the legitimate `null` on every non-TYPE_TEXT command. The message
                // names the limit, never the value (redaction rule).
                constrain("text must have at most ${Endpoints.MAX_TEXT_LENGTH} characters") {
                    it.text == null || it.text.length in 1..Endpoints.MAX_TEXT_LENGTH
                }
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

    val inputCommandResponse = Validation<InputCommandResponse> {
        InputCommandResponse::protocolVersion { supportedProtocol() }
    }

    // ── Catalog (peer-katalog.md §3.3) ────────────────────────────────────────────────────

    /** Lowercase hex SHA-256, exactly [Endpoints.HASH_LENGTH] chars — root- and content-hashes. */
    private val HASH_PATTERN = Regex("^[0-9a-f]{${Endpoints.HASH_LENGTH}}$")

    /** Opaque printable entity id (a UUIDv4 fits, so does a later key-fingerprint id). */
    private val ENTITY_ID_PATTERN = Regex("^[A-Za-z0-9._:-]{1,${Endpoints.MAX_ENTITY_ID_LENGTH}}$")

    /**
     * Whether [id] is a well-formed catalog entity id. Exposed so the server's `catalogRoutes` can
     * reject a malformed `{id}` path parameter against the SAME pattern the response validations use —
     * one source of truth for the id format, no drift between route and DTO check.
     */
    fun isCatalogEntityId(id: String): Boolean = ENTITY_ID_PATTERN.matches(id)

    val catalogIndexResponse = Validation<CatalogIndexResponse> {
        CatalogIndexResponse::protocolVersion { supportedProtocol() }
        CatalogIndexResponse::rootHash { pattern(HASH_PATTERN) }
        CatalogIndexResponse::entries {
            maxItems(Endpoints.MAX_CATALOG_ENTRIES)
            onEach {
                CatalogEntry::id { pattern(ENTITY_ID_PATTERN) }
                CatalogEntry::contentHash { pattern(HASH_PATTERN) }
                // The label IS a config entity's name/label (up to ConfigValidations.MAX_LABEL = 200),
                // so the cap matches that, not the narrower device-name limit the spec sketch used.
                CatalogEntry::label { maxLength(Endpoints.MAX_CATALOG_LABEL_LENGTH) }
            }
        }
    }

    val catalogEntityResponse = Validation<CatalogEntityResponse> {
        CatalogEntityResponse::protocolVersion { supportedProtocol() }
        CatalogEntityResponse::id { pattern(ENTITY_ID_PATTERN) }
        CatalogEntityResponse::contentHash { pattern(HASH_PATTERN) }
        // No `{value}` on payload — redaction rule: a length breach names the limit, never the payload.
        CatalogEntityResponse::payload {
            minLength(1)
            maxLength(Endpoints.MAX_ENTITY_PAYLOAD_LENGTH)
        }
    }

    val catalogCredentialResponse = Validation<CatalogCredentialResponse> {
        CatalogCredentialResponse::protocolVersion { supportedProtocol() }
        CatalogCredentialResponse::id { pattern(ENTITY_ID_PATTERN) }
        CatalogCredentialResponse::provider {
            minLength(1)
            maxLength(64)
        }
        // NEVER a `{value}` constraint on `secret` — it would copy the key into logs (redaction rule).
        CatalogCredentialResponse::secret { minLength(1) }
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
