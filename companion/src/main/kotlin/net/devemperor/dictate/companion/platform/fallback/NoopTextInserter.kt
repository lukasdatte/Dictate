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

/** A clipboard that goes nowhere — for tests that must not touch the developer's real one. */
object NoopClipboard : net.devemperor.dictate.companion.domain.port.ClipboardPort {
    override fun readText(): String? = null
    override fun writeText(text: String): Boolean = true
}

/**
 * The input performer on every OS that is not Windows (and the reason the companion runs on Linux).
 *
 * Reports [available] = false → `/v1/health` says `supportsInputCommands = false`. It never
 * pretends: a keyboard action here cannot reach any window, so it answers [InputOutcome.REJECTED],
 * which the phone shows as a failure rather than believing an action landed (§5.3).
 */
object NoopInputCommandPerformer : net.devemperor.dictate.companion.domain.port.InputCommandPerformer {

    override val available: Boolean = false

    override fun perform(
        commands: List<net.devemperor.dictate.companion.domain.model.InputCommand>,
    ): net.devemperor.dictate.companion.domain.model.InputOutcome =
        net.devemperor.dictate.companion.domain.model.InputOutcome.REJECTED
}
