package net.devemperor.dictate.shared.auth

import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.ProtocolVersion

/**
 * What the phone proves about itself on every request after pairing.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015): the phone builds these headers, the companion parses them, and both
 * read the names from [Endpoints] so they cannot drift apart.
 */
data class Credentials(
    val deviceId: String,
    /** The long-lived secret from pairing. The desktop stores only its SHA-256 (ADR-0017). */
    val deviceSecret: String,
)

object AuthHeaders {

    const val BEARER_PREFIX = "Bearer "

    /** The three headers every authenticated request carries. */
    fun forDevice(credentials: Credentials): Map<String, String> = mapOf(
        Endpoints.HEADER_AUTHORIZATION to BEARER_PREFIX + credentials.deviceSecret,
        Endpoints.HEADER_DEVICE_ID to credentials.deviceId,
        Endpoints.HEADER_PROTOCOL to ProtocolVersion.CURRENT.toString(),
    )

    /**
     * Pairing itself is unauthenticated — the one-time token in the body *is* the credential
     * (ADR-0017). The protocol header still travels, so a version mismatch is answered with
     * `PROTOCOL_VERSION_UNSUPPORTED` rather than a confusing validation error.
     */
    fun forPairing(): Map<String, String> = mapOf(
        Endpoints.HEADER_PROTOCOL to ProtocolVersion.CURRENT.toString(),
    )

    /** Server side. Returns the presented secret, or null if the header is absent or malformed. */
    fun parseBearer(headerValue: String?): String? {
        if (headerValue == null || !headerValue.startsWith(BEARER_PREFIX)) return null
        return headerValue.removePrefix(BEARER_PREFIX).trim().ifEmpty { null }
    }
}
