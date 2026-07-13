package net.devemperor.dictate.shared.protocol

/**
 * The wire-protocol version spoken between the phone and the desktop companion.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015).
 *
 * Every request and response DTO carries [CURRENT] as its first field, and the server
 * checks it **first** — before auth, before validation — so an outdated peer gets a
 * comprehensible `PROTOCOL_VERSION_UNSUPPORTED` instead of a puzzling "validation failed"
 * (ADR-0016). For bodyless requests (`GET /v1/sync/cursor`) the same value travels in the
 * [Endpoints.HEADER_PROTOCOL] header.
 */
object ProtocolVersion {

    /**
     * Bump ONLY on a breaking wire change.
     *
     * Adding an **optional** field with a default does NOT bump this: the codec decodes with
     * `ignoreUnknownKeys = true`, so an older peer silently skips a field a newer peer sent.
     * Removing a field, renaming one, or changing its meaning DOES bump it.
     */
    const val CURRENT: Int = 1

    /** A peer speaking [version] is acceptable iff it equals [CURRENT]. V1 is strict — no range yet. */
    fun isSupported(version: Int): Boolean = version == CURRENT
}
