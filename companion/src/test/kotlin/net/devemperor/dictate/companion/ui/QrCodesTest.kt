package net.devemperor.dictate.companion.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import net.devemperor.dictate.companion.ui.pairing.QrCodes
import net.devemperor.dictate.shared.auth.PairingUri
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The QR code, decoded back.
 *
 * A QR that renders beautifully and does not scan is exactly the defect a rendering test never
 * catches — so this one reads the bitmap back with ZXing's *decoder* and compares. Runs headless.
 */
class QrCodesTest {

    @Test
    fun aPairingUriSurvivesTheRoundTrip() {
        val uri = PairingUri.encode("http://vm-win.tailnet.ts.net:8756", "K7M49QXR")

        val decoded = decode(uri)

        assertEquals(uri, decoded)
        val parsed = PairingUri.parse(decoded)!!
        assertEquals("K7M49QXR", parsed.token)
        assertEquals("http://vm-win.tailnet.ts.net:8756", parsed.baseUrl)
    }

    @Test
    fun aLongHostnameStillFits() {
        // A MagicDNS name plus a base64url-encoded URL is the realistic worst case, and a QR that
        // silently overflows its version would fail to encode — not render wrong, but throw.
        val uri = PairingUri.encode("https://desktop-workstation-in-the-office.tailnet-abcdef.ts.net:8756", "ZZZZ9999")

        assertEquals(uri, decode(uri))
    }

    private fun decode(content: String): String {
        val image = QrCodes.encode(content)
        val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))
        return MultiFormatReader().decode(bitmap).text
    }
}
