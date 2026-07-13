package net.devemperor.dictate.companion.fakes

import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.port.TextInserter

/**
 * A programmable inserter. Hand-written, per house style — no mock library.
 *
 * [inserted] is what makes the E2E suite worth its runtime: it proves the text that left the phone
 * is byte-for-byte the text that reached the Win32 boundary — no trimming, no newline mangling, no
 * mangled emoji. Everything past that boundary is Windows' business and lives on the checklist.
 *
 * [delayMillis] blocks the calling request thread, which is how the timeout case is provoked
 * against a real server over a real socket.
 */
class FakeTextInserter(
    var nextOutcome: InsertionOutcome = InsertionOutcome.TYPED_CTRL_V,
    override var available: Boolean = true,
    var delayMillis: Long = 0L,
) : TextInserter {

    val inserted = mutableListOf<String>()

    override fun insert(text: String): InsertionOutcome {
        if (delayMillis > 0) Thread.sleep(delayMillis)
        synchronized(inserted) { inserted += text }
        return nextOutcome
    }
}
