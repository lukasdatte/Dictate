package net.devemperor.dictate.shared.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parser tests for [PairingUri].
 *
 * The phone points a camera at the world and gets a string back. Everything that is not exactly
 * our URI — another app's QR code, a truncated scan, a future protocol version — must resolve to
 * null, so the caller has one error path instead of a half-parsed result.
 */
class PairingUriTest {

    @Test
    fun roundTrip_plainHttp() {
        val encoded = PairingUri.encode("http://my-pc:8756", "K7M49QXR")

        assertEquals(PairingInfo("http://my-pc:8756", "K7M49QXR", 1), PairingUri.parse(encoded))
    }

    @Test
    fun roundTrip_httpsFromTailscaleServe() {
        val encoded = PairingUri.encode("https://my-pc.tail1234.ts.net", "K7M49QXR")

        assertEquals(PairingInfo("https://my-pc.tail1234.ts.net", "K7M49QXR", 1), PairingUri.parse(encoded))
    }

    @Test
    fun roundTrip_urlWithPortAndPath() {
        // The base URL is carried base64url-encoded precisely so its own ':' and '/' cannot
        // collide with the outer URI's grammar.
        val encoded = PairingUri.encode("http://192.168.1.20:8756/companion", "ABCD2345")

        assertEquals(PairingInfo("http://192.168.1.20:8756/companion", "ABCD2345", 1), PairingUri.parse(encoded))
    }

    @Test
    fun parse_toleratesSurroundingWhitespace() {
        val encoded = PairingUri.encode("http://my-pc:8756", "K7M49QXR")

        assertEquals(PairingUri.parse(encoded), PairingUri.parse("  $encoded \n"))
    }

    @Test
    fun parse_foreignAppUri_isNull() {
        assertNull(PairingUri.parse("otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP"))
        assertNull(PairingUri.parse("https://example.com/pair?v=1&t=K7M49QXR"))
        assertNull(PairingUri.parse("dictate://open?v=1&t=K7M49QXR"))
    }

    @Test
    fun parse_futureProtocolVersion_isNull() {
        val raw = "dictate://pair?v=2&url=aHR0cDovL215LXBjOjg3NTY&t=K7M49QXR"

        assertNull(PairingUri.parse(raw))
    }

    @Test
    fun parse_missingToken_isNull() {
        assertNull(PairingUri.parse("dictate://pair?v=1&url=aHR0cDovL215LXBjOjg3NTY"))
        assertNull(PairingUri.parse("dictate://pair?v=1&url=aHR0cDovL215LXBjOjg3NTY&t="))
    }

    @Test
    fun parse_missingUrl_isNull() {
        assertNull(PairingUri.parse("dictate://pair?v=1&t=K7M49QXR"))
    }

    @Test
    fun parse_missingVersion_isNull() {
        assertNull(PairingUri.parse("dictate://pair?url=aHR0cDovL215LXBjOjg3NTY&t=K7M49QXR"))
    }

    @Test
    fun parse_nonNumericVersion_isNull() {
        assertNull(PairingUri.parse("dictate://pair?v=one&url=aHR0cDovL215LXBjOjg3NTY&t=K7M49QXR"))
    }

    @Test
    fun parse_base64Garbage_isNull() {
        assertNull(PairingUri.parse("dictate://pair?v=1&url=!!!not-base64!!!&t=K7M49QXR"))
    }

    @Test
    fun parse_urlWithoutHttpScheme_isNull() {
        // "my-pc:8756" base64url-encoded — decodes fine, but is not a URL we would ever call.
        val encoded = PairingUri.encode("my-pc:8756", "K7M49QXR")

        assertNull(PairingUri.parse(encoded))
    }

    @Test
    fun parse_truncatedScan_isNull() {
        val encoded = PairingUri.encode("http://my-pc:8756", "K7M49QXR")

        assertNull(PairingUri.parse(encoded.dropLast(12)))
    }

    @Test
    fun parse_duplicateParameter_isNull() {
        // Two tokens in one URI is not a URI to pick a winner from — it is a broken one.
        assertNull(PairingUri.parse("dictate://pair?v=1&url=aHR0cDovL215LXBjOjg3NTY&t=K7M49QXR&t=OTHER123"))
    }

    @Test
    fun parse_malformedQuerySegment_isNull() {
        assertNull(PairingUri.parse("dictate://pair?v=1&url&t=K7M49QXR"))
        assertNull(PairingUri.parse("dictate://pair?=1&url=aHR0cDovL215LXBjOjg3NTY&t=K7M49QXR"))
    }
}
