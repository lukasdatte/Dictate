package net.devemperor.dictate.shared.protocol

/**
 * Paths, header names and limits — the single source of truth for **both** sides.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015).
 *
 * The client builds its URLs from these constants and the server registers its routes from
 * them, so a path can never drift apart between the two. The limits below are enforced by the
 * Konform validations in [Validations] on both the sending and the receiving side.
 */
object Endpoints {

    const val BASE = "/v1"
    const val PAIR = "$BASE/pair"
    const val DISPATCH = "$BASE/dispatch"
    const val SYNC = "$BASE/sync"
    const val SYNC_CURSOR = "$BASE/sync/cursor"
    const val HEALTH = "$BASE/health"

    /** Keyboard-action remote control (POST, authenticated). Additive — no version bump (ADR "Input-Command-Protokoll"). */
    const val INPUT = "$BASE/input"

    const val HEADER_AUTHORIZATION = "Authorization"
    const val HEADER_DEVICE_ID = "X-Dictate-Device"
    const val HEADER_PROTOCOL = "X-Dictate-Protocol"

    /** ~100 KB of text. Beyond that it is not a dictation any more — it is an attack or a bug. */
    const val MAX_TEXT_LENGTH = 100_000

    /** Rows per sync page. Also the server's hard cap on an accepted [SyncRequest]. */
    const val MAX_SYNC_BATCH = 200

    /** Commands per `POST /v1/input` batch — one send-window flush never exceeds this (§4.3.2). */
    const val MAX_INPUT_BATCH = 20

    /** Repeat count cap on a single coalesced movement/deletion command (`count`). */
    const val MAX_INPUT_REPEAT = 50

    const val MAX_SESSION_ID_LENGTH = 64
    const val MAX_DEVICE_NAME_LENGTH = 64
    const val MAX_DEVICE_ID_LENGTH = 64
    const val MIN_DEVICE_ID_LENGTH = 8

    /** Length of the one-time pairing token the companion shows as a QR and as a typable code. */
    const val PAIRING_TOKEN_LENGTH = 8
    const val MAX_PAIRING_TOKEN_LENGTH = 64

    /** How long a shown pairing token stays redeemable. After that: `401 TOKEN_EXPIRED`. */
    const val PAIRING_TOKEN_TTL_MILLIS = 120_000L
}
