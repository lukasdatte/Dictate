package net.devemperor.dictate.shared.protocol

import kotlinx.serialization.Serializable

/**
 * Every payload that crosses the wire between the phone and the desktop companion.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015).
 *
 * The types own the **wire format** (fields, required/optional, enum names — enforced by the
 * compiler); the [Validations] next door own the **value constraints** (lengths, ranges,
 * version). Together they are the schema, and [ProtocolCodec] is the only door through which a
 * payload may enter or leave (ADR-0016).
 *
 * Every DTO carries `protocolVersion` as its first field, defaulted to
 * [ProtocolVersion.CURRENT] so a caller cannot forget it.
 */

// ── Pairing ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class PairRequest(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    /** The one-time token from the QR code or the typed pairing code. Burned on success. */
    val pairingToken: String,
    /** Stable per-install id, generated once by the phone (UUIDv4). */
    val deviceId: String,
    /** Human label shown in the companion's device list, e.g. "Pixel 8". */
    val deviceName: String,
)

@Serializable
data class PairResponse(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    val deviceId: String,
    /** 256-bit, base64url, no padding. The phone stores this; the desktop stores only its SHA-256. */
    val deviceSecret: String,
    /** Shown in the phone's settings summary ("paired with <serverName>"). */
    val serverName: String,
)

// ── Dispatch ────────────────────────────────────────────────────────────────────────────

@Serializable
data class DispatchRequest(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    /** The Room session id. Doubles as the idempotency key on the server. */
    val sessionId: String,
    val text: String,
    /** The session's `created_at` (epoch millis) — carries the dictation order to the PC. */
    val createdAt: Long,
    val origin: SessionOriginWire,
)

/**
 * The wire mirror of the app's `SessionOrigin`.
 *
 * A separate enum on purpose: the app's enum may grow variants that the protocol does not know,
 * and the protocol must not be dragged along by an internal refactor. `UNKNOWN` is the landing
 * zone for anything the mapper does not recognise.
 */
@Serializable
enum class SessionOriginWire { KEYBOARD, HISTORY_REPROCESS, POST_PROCESSING, REVIEW_REFINEMENT, UNKNOWN }

@Serializable
data class DispatchResponse(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    val sessionId: String,
    /**
     * TRUE means: the companion has the text and has done its best to place it.
     *
     * THIS IS THE DELIVERY CONFIRMATION (ADR-0017) — there is no second acknowledgement channel.
     * On a 200 with `delivered = true` the phone creates no pending part; on anything else
     * (including a timeout, which is *ambiguous*) it does.
     */
    val delivered: Boolean,
    val outcome: InsertionOutcomeWire,
    /** True if this sessionId was already known — the phone may skip the sync for it. */
    val duplicate: Boolean = false,
)

/**
 * How the text landed on the PC. **Both variants mean success** (ADR-0018): the text is on the
 * PC and reachable. A failed insertion is not an outcome — it is a `503 INSERTION_FAILED`.
 */
@Serializable
enum class InsertionOutcomeWire {
    /** Clipboard set AND Ctrl+V injected into the foreground window. */
    TYPED_CTRL_V,

    /**
     * Clipboard set, but nothing was typed — no foreground window, UIPI blocked the injection,
     * or the companion runs on a platform without an inserter. Still delivered, but the user
     * must be told, or they will believe the text was typed.
     */
    CLIPBOARD_ONLY,
}

// ── Input commands ──────────────────────────────────────────────────────────────────────

/**
 * A batch of keyboard actions to replay on the PC — the semantic remote-control channel
 * (`POST /v1/input`, ADR "Input-Command-Protokoll").
 *
 * Additive to the protocol: shipped **without** a [ProtocolVersion] bump. An older companion has
 * no such route and answers 404, which the client maps to a distinct `EndpointMissing` so the app
 * can say "update the companion" rather than "PC unreachable".
 *
 * The commands travel as **semantics**, never raw VK codes: the phone says `REDO`, the companion
 * decides that `REDO` is Ctrl+Y (or whatever the user rebound it to). That keeps the wire
 * Konform-validatable, layout-agnostic for the phone, and small in injection surface (§1.3).
 */
@Serializable
data class InputCommandRequest(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    /** 1..[Endpoints.MAX_INPUT_BATCH]; list order **is** the execution order on the PC. */
    val commands: List<InputCommandWire>,
)

@Serializable
data class InputCommandWire(
    val kind: InputCommandKindWire,
    /** Only for [InputCommandKindWire.TYPE_TEXT]; 1..[Endpoints.MAX_TEXT_LENGTH]. Null for every other kind. */
    val text: String? = null,
    /**
     * Repeat count for the movement/deletion kinds (BACKSPACE, CURSOR_*, CURSOR_WORD_SELECT_*).
     * 1..[Endpoints.MAX_INPUT_REPEAT]. Coalesces a burst of same-direction presses into one command.
     * Ignored (kept at 1) for TYPE_TEXT, ENTER, SPACE and the clipboard/undo kinds.
     */
    val count: Int = 1,
)

/**
 * The wire vocabulary of remote keyboard actions.
 *
 * A separate enum from the companion's internal `KeyCommand` domain enum on purpose (as
 * `SessionOriginWire` is separate from `SessionOrigin`): the wire vocabulary must not be dragged
 * along by a companion-internal refactor, and vice versa.
 */
@Serializable
enum class InputCommandKindWire {
    TYPE_TEXT, BACKSPACE, ENTER, SPACE,
    CURSOR_LEFT, CURSOR_RIGHT,

    /** Ctrl+Shift+←/→ on the PC — select one word back/forward, for the PC backspace-swipe (D1). */
    CURSOR_WORD_SELECT_BACK, CURSOR_WORD_SELECT_FORWARD,
    SELECT_ALL, CUT, COPY, PASTE, UNDO, REDO,
}

@Serializable
data class InputCommandResponse(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    /** TRUE only for a full success — mirrors `delivered` (ADR-0017): a partial UIPI rejection is `false`. */
    val executed: Boolean,
    val outcome: InputOutcomeWire,
)

/**
 * How a batch of input commands landed on the PC. Only [SENT] is a success.
 *
 * Unlike `InsertionOutcomeWire` (where CLIPBOARD_ONLY is still a success because the text reached
 * the clipboard), a keyboard action has no fallback surface: if it could not be injected, it
 * simply did not happen and the phone must say so (Entscheidung 4 — immediate error, no buffering).
 */
@Serializable
enum class InputOutcomeWire {
    /** Every command was injected into the foreground window. */
    SENT,

    /** No foreground window to receive the input — nothing was sent. */
    NO_FOREGROUND_WINDOW,

    /** UIPI (an elevated target) rejected the injection in part or whole — treated as not sent. */
    REJECTED,
}

// ── Sync ────────────────────────────────────────────────────────────────────────────────

/**
 * The server's receive watermark.
 *
 * Two fields, not one: `createdAt` alone is not unique (two sessions can be born in the same
 * millisecond), and a sync page needs a **total** order or it skips or repeats rows at the page
 * boundary. `(createdAt, sessionId)` is total because the id is unique (ADR-0020).
 */
@Serializable
data class SyncCursor(val lastCreatedAt: Long, val lastSessionId: String)

@Serializable
data class CursorResponse(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    /** null = the server knows nothing yet → the phone sends its history from the beginning. */
    val cursor: SyncCursor? = null,
)

@Serializable
data class SessionUpsert(
    val sessionId: String,
    val text: String,
    val createdAt: Long,
    val origin: SessionOriginWire,
    /** Whether this session was ever dispatched to this PC (vs. merely synced for the archive). */
    val dispatched: Boolean,
)

@Serializable
data class SyncRequest(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    val items: List<SessionUpsert>,
)

@Serializable
data class SyncResponse(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    val accepted: Int,
    /** The server's cursor AFTER applying `items`. The phone pages on from here. */
    val cursor: SyncCursor? = null,
)

// ── Health ──────────────────────────────────────────────────────────────────────────────

@Serializable
data class HealthResponse(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    val serverName: String,
    val appVersion: String,
    /** false on Linux/macOS or when no inserter is available → the phone can warn while pairing. */
    val canInsert: Boolean,
    /**
     * Whether this companion serves `POST /v1/input` (the keyboard-action channel).
     *
     * Additive, defaulted `false` so an older companion's health response (which lacks the field)
     * decodes as "no support" under `ignoreUnknownKeys` — the phone reads it at pairing/health time
     * and can warn proactively before the first keyboard action hits a 404.
     */
    val supportsInputCommands: Boolean = false,
    /**
     * Whether this companion serves the `/v1/catalog` family (peer-katalog.md §4.4).
     *
     * Additive, defaulted `false` so an older peer's health response (which lacks the field) decodes
     * as "no support" under `ignoreUnknownKeys` — a subscribing peer reads it during discovery/health
     * and can skip a peer that cannot offer a catalog. Android never sets it (Android runs no server).
     */
    val supportsCatalog: Boolean = false,
)

// ── Catalog (peer-katalog.md §3.2) ────────────────────────────────────────────────────────

/**
 * The wire kind of a catalog entity — a SEPARATE enum from the C1 domain entity types, exactly as
 * [SessionOriginWire] is separate from `SessionOrigin` above. The wire vocabulary must not be dragged
 * along by an internal config refactor. `UNKNOWN` is the landing zone the receiver's mapper uses for
 * a kind a newer provider introduced.
 */
@Serializable
enum class CatalogEntityKindWire { PROVIDER_CONFIG, MODEL_REF, PROMPT, PROFILE, CREDENTIAL, UNKNOWN }

/**
 * One row of the catalog index — metadata ONLY, never a payload and never a secret.
 *
 * (Not to be confused with `net.devemperor.dictate.shared.config.CatalogEntry`, the tagged union of a
 * v3 catalog *file*; this is the wire index row of the *peer* protocol.) `contentHash` is the SHA-256
 * over the entity's canonical serialization (C1); for a CREDENTIAL it is the hash over a stable key
 * fingerprint, never the plaintext (F12). `updatedAt` drives the "last synced" display, not the diff.
 */
@Serializable
data class CatalogEntry(
    val id: String,
    val kind: CatalogEntityKindWire,
    val contentHash: String,
    val updatedAt: Long,
    /** Human label for the offer view (e.g. prompt name, provider label). No payload. */
    val label: String,
)

/**
 * `GET /v1/catalog` — the whole shared offer of a peer, plus its rootHash.
 *
 * The rootHash is SHA-256 over the sorted `id:contentHash` join of all entries; a single GET answers
 * "did anything change at all?" before any per-entity fetch (peer-katalog.md §6.1).
 */
@Serializable
data class CatalogIndexResponse(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    val rootHash: String,
    val entries: List<CatalogEntry>,
)

/**
 * `GET /v1/catalog/entity/{id}` — the canonical v3 payload of ONE non-credential entity.
 *
 * `payload` is the exact canonical serialization (C1 `CanonicalJson`) the receiver re-hashes to verify
 * `contentHash` (peer-katalog.md §6.3). A CREDENTIAL is NEVER served here — its route is /credential/{id}.
 */
@Serializable
data class CatalogEntityResponse(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    val id: String,
    val kind: CatalogEntityKindWire,
    val contentHash: String,
    val payload: String,
)

/**
 * `GET /v1/catalog/credential/{id}` — the envelope-delivered secret value (F12).
 *
 * Reached only by an explicitly authorized call; every delivery writes an audit row (R8). The receiver
 * puts `secret` straight into its own SecretStore (B1) and never persists it in a column.
 * `provider`/`label` are metadata for the SecretStore namespace.
 */
@Serializable
data class CatalogCredentialResponse(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    val id: String,
    val provider: String,
    val label: String,
    /** Plaintext key, TLS in transit, straight into the receiver's SecretStore. */
    val secret: String,
)
