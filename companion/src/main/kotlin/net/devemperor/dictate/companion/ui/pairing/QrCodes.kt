package net.devemperor.dictate.companion.ui.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage

/**
 * The pairing QR code — kept out of the `@Composable` so it can be tested.
 *
 * `QrCodesTest` encodes a real pairing URI, reads the bitmap back with ZXing's decoder and compares:
 * a QR code that renders beautifully and does not scan is the sort of defect only a round trip
 * catches.
 *
 * Error correction M (~15 %) rather than L: the code is photographed off a screen, at an angle, and
 * often with a phone that is auto-focusing. The extra modules cost nothing at this size.
 */
object QrCodes {

    const val DEFAULT_SIZE_PX = 320

    fun encode(content: String, sizePx: Int = DEFAULT_SIZE_PX): BufferedImage {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        return MatrixToImageWriter.toBufferedImage(matrix)
    }

    /** The quiet zone is part of the spec, not padding: without it many scanners simply never lock on. */
    private const val QUIET_ZONE_MODULES = 2
}
