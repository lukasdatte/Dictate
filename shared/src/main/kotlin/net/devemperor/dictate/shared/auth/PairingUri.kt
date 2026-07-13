package net.devemperor.dictate.shared.auth

import java.util.Base64

/** What a scanned (or typed) pairing QR code resolves to. */
data class PairingInfo(
    /** The companion's base URL including scheme and port, e.g. `http://my-pc:8756`. */
    val baseUrl: String,
    val token: String,
    val version: Int,
)

/**
 * The `dictate://pair?…` URI: written by the desktop into a QR bitmap, read by the phone.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015). The parse is hand-rolled on purpose: `android.net.Uri` would make this
 * module unusable from the companion, and `java.net.URI` rejects a custom scheme with an
 * authority of `pair` in more JDKs than it accepts it.
 *
 * The base URL is carried **base64url-encoded** so its own `:`, `/` and possible query never
 * collide with the outer URI's grammar. It carries the scheme, so `tailscale serve` (https) and
 * a plain LAN address (http) both work without a second flag.
 */
object PairingUri {

    const val SCHEME = "dictate"
    const val HOST = "pair"
    const val VERSION = 1

    private const val PREFIX = "$SCHEME://$HOST?"

    fun encode(baseUrl: String, token: String): String {
        val encodedUrl = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(baseUrl.toByteArray(Charsets.UTF_8))
        return "${PREFIX}v=$VERSION&url=$encodedUrl&t=$token"
    }

    /**
     * Returns null for **anything** that is not a well-formed pairing URI of a version we speak —
     * a foreign app's QR code, a truncated scan, a future `v=2`. The caller shows one clear error;
     * it never has to reason about a half-parsed result.
     */
    fun parse(raw: String): PairingInfo? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith(PREFIX)) return null

        val params = mutableMapOf<String, String>()
        trimmed.removePrefix(PREFIX).split('&').forEach { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) return null
            val key = pair.substring(0, separator)
            // A repeated key is a malformed URI, not something to silently pick a winner from.
            if (params.put(key, pair.substring(separator + 1)) != null) return null
        }

        val version = params["v"]?.toIntOrNull() ?: return null
        if (version != VERSION) return null

        val token = params["t"]?.takeIf { it.isNotEmpty() } ?: return null
        val baseUrl = params["url"]?.let { decodeBase64Url(it) } ?: return null
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) return null

        return PairingInfo(baseUrl = baseUrl, token = token, version = version)
    }

    private fun decodeBase64Url(encoded: String): String? = try {
        String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8).takeIf { it.isNotEmpty() }
    } catch (e: IllegalArgumentException) {
        null
    }
}
