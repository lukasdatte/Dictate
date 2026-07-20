package net.devemperor.dictate.companion.domain.session

import net.devemperor.dictate.shared.protocol.SessionOriginWire

/**
 * The Room-parity session vocabularies, mirrored companion-side.
 *
 * These enums are the Kotlin half of the Double-Enum rule (docs/DATABASE-PATTERNS.md) for the four
 * core session tables. Their `.name` strings are the persisted values, and the CHECK constraints in
 * `Companion.sq` are the SQL half — `CompanionSchemaParityTest` pins the two together, and
 * `RoomParityReference` pins these names against the Room originals in `:app` (which `:companion`
 * cannot import — `:app` is Android). See desktop-host.md §3.2/§3.6.
 *
 * **Deliberately duplicated, not imported.** The Room originals live under
 * `app/.../database/entity/` and cannot cross the module boundary. The proper cross-module SSoT
 * (define once in `:shared`, map both Room and SQLDelight onto it) is an `:app` refactor tracked in
 * desktop-host.md §15 Gap 1. Until then, the tested `RoomParityReference` is the drift guard.
 *
 * The error/provider vocabularies are the exception: `AIProviderException.ErrorType` and `AIProvider`
 * come from `:shared-ai` (they already exist there after Block A) and are NOT redefined here, or the
 * shared error taxonomy would fork. Only the pure session-structure enums — which are Room-only in
 * `:app` today — are mirrored below.
 */

/** SSoT: app/.../database/entity/SessionType.kt + MigrationTo9.kt CHECK. */
enum class SessionType {
    RECORDING,
    REWORDING,
    POST_PROCESSING,
}

/** SSoT: app/.../database/entity/SessionStatus.kt + MigrationTo9.kt CHECK. */
enum class SessionStatus {
    RECORDING,
    RECORDING_INTERRUPTED,
    RECORDED,
    TRANSCRIBING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/**
 * SSoT: app/.../database/entity/SessionOrigin.kt + MigrationTo9.kt CHECK.
 *
 * Four values, one fewer than the wire enum [SessionOriginWire]: the wire's `UNKNOWN` is a landing
 * zone the protocol carries but the archive never stores (ADR-0016). [SessionOriginWire.toSession]
 * folds it onto [KEYBOARD].
 */
enum class SessionOrigin {
    KEYBOARD,
    HISTORY_REPROCESS,
    POST_PROCESSING,
    REVIEW_REFINEMENT,
}

/**
 * Companion-only axis (F16): does a session mirror the phone's history, or was it dictated on this
 * PC? Not part of the Room parity — it is the one column that separates the two archives so the
 * sync cursor and the desktop pipeline never read each other's rows. See desktop-host.md §3.3.
 */
enum class HostOrigin {
    PHONE_SYNC,
    DESKTOP_DICTATION,
}

/** SSoT: app/.../database/entity/StepType.kt + MigrationTo8.kt CHECK. */
enum class StepType {
    AUTO_FORMAT,
    REWORDING,
    QUEUED_PROMPT,
    CONVERSATION_TURN,
}

/**
 * SSoT: app/.../database/entity/StepStatus.kt.
 *
 * Room stores this as bare `TEXT NOT NULL` — **no** CHECK (MigrationTo8.kt:63). The companion adds
 * the CHECK anyway (a strict improvement on a green field; every Room value stays valid), see
 * desktop-host.md §14 D3.
 */
enum class StepStatus {
    SUCCESS,
    ERROR,
}

/** SSoT: app/.../database/entity/ResponseFormatKind.kt + MigrationTo8.kt CHECK. */
enum class ResponseFormatKind {
    JSON_SCHEMA,
    TOOL_USE,
    TEXT_FALLBACK,
}

/** SSoT: app/.../database/entity/MessageRole.kt + MigrationTo8.kt CHECK. */
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

/**
 * Wire origin → stored session origin. `UNKNOWN` folds onto [SessionOrigin.KEYBOARD] — the landing
 * default ADR-0016 prescribes for values the protocol does not map. The other four are namesakes.
 */
fun SessionOriginWire.toSession(): SessionOrigin = when (this) {
    SessionOriginWire.KEYBOARD -> SessionOrigin.KEYBOARD
    SessionOriginWire.HISTORY_REPROCESS -> SessionOrigin.HISTORY_REPROCESS
    SessionOriginWire.POST_PROCESSING -> SessionOrigin.POST_PROCESSING
    SessionOriginWire.REVIEW_REFINEMENT -> SessionOrigin.REVIEW_REFINEMENT
    SessionOriginWire.UNKNOWN -> SessionOrigin.KEYBOARD
}

/**
 * Stored session origin → wire origin. Total and lossless: every stored value is a namesake on the
 * wire. `UNKNOWN` is never produced here — it only ever travels *into* the archive, never out.
 */
fun SessionOrigin.toWire(): SessionOriginWire = when (this) {
    SessionOrigin.KEYBOARD -> SessionOriginWire.KEYBOARD
    SessionOrigin.HISTORY_REPROCESS -> SessionOriginWire.HISTORY_REPROCESS
    SessionOrigin.POST_PROCESSING -> SessionOriginWire.POST_PROCESSING
    SessionOrigin.REVIEW_REFINEMENT -> SessionOriginWire.REVIEW_REFINEMENT
}
