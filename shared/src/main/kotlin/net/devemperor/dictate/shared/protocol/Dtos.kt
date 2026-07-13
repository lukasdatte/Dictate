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
)
