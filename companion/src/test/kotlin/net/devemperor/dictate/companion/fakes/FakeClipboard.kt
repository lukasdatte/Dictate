package net.devemperor.dictate.companion.fakes

import net.devemperor.dictate.companion.domain.port.ClipboardPort

/**
 * A clipboard a test can pre-load and break on demand.
 *
 * [content] = null models the case that matters most: a clipboard holding an *image* or a file list.
 * The insertion must not restore over it, and the only way to prove that is to have a clipboard that
 * can be in that state.
 */
class FakeClipboard(
    var content: String? = null,
    var writable: Boolean = true,
) : ClipboardPort {

    val writes = mutableListOf<String>()

    override fun readText(): String? = content

    override fun writeText(text: String): Boolean {
        if (!writable) return false
        writes += text
        content = text
        return true
    }
}
