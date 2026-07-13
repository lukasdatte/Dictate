package net.devemperor.dictate.companion.platform.windows

import net.devemperor.dictate.companion.domain.port.ClipboardPort
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * The system clipboard through AWT — the same one every Java desktop app uses.
 *
 * Both methods swallow their exceptions on purpose. The clipboard is a *shared, contended* resource:
 * another application can hold it open for a moment, and Windows then answers with an
 * `IllegalStateException` that means nothing more than "try again later". A dictation must not be
 * lost over that, so a failed read is reported as "there was nothing text-shaped there" (→ do not
 * restore) and a failed write as `false` (→ the caller answers `FAILED` and the phone keeps the
 * text as a pending part).
 */
class AwtClipboard : ClipboardPort {

    override fun readText(): String? = try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        // Not text (an image, a file list, an empty clipboard) → null. That is not a failure, it is
        // a fact the restore has to respect: overwriting a copied image with a stale text would
        // destroy something the user still wanted.
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            clipboard.getData(DataFlavor.stringFlavor) as? String
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    override fun writeText(text: String): Boolean = try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        true
    } catch (e: Exception) {
        false
    }
}
