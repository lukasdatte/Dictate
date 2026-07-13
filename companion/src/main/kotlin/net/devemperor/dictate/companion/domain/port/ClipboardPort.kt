package net.devemperor.dictate.companion.domain.port

/**
 * The system clipboard, as much of it as the insertion path needs.
 *
 * [readText] returns null for a clipboard holding something that is not text (an image, a file
 * list) — which is not an error but a *fact the restore has to respect*: overwriting a copied
 * image with a stale text would destroy user data, so a null previous content means "do not
 * restore" (see `JnaWin32TextInserter`).
 */
interface ClipboardPort {

    fun readText(): String?

    /** @return false if the clipboard could not be written (another app owns it, X11 has no owner…). */
    fun writeText(text: String): Boolean
}
