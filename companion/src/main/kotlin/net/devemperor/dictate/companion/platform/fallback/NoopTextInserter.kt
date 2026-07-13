package net.devemperor.dictate.companion.platform.fallback

import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.port.TextInserter

/**
 * The inserter on every OS that is not Windows — and the reason the companion runs on this Linux
 * dev VM at all (ADR-0018).
 *
 * It reports [available] = false, so `/v1/health` tells the phone `canInsert = false` and the UI
 * shows a banner. It still returns [InsertionOutcome.CLIPBOARD_ONLY] rather than
 * [InsertionOutcome.FAILED] **only if** a clipboard was wired in; without one there is nowhere for
 * the text to go and `FAILED` is the truthful answer — the phone then keeps the text as a pending
 * part instead of believing a PC took it.
 */
object NoopTextInserter : TextInserter {

    override val available: Boolean = false

    override fun insert(text: String): InsertionOutcome = InsertionOutcome.FAILED
}
