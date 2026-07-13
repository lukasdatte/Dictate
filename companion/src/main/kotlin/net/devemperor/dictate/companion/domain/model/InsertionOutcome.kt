package net.devemperor.dictate.companion.domain.model

import net.devemperor.dictate.shared.protocol.InsertionOutcomeWire

/**
 * What became of one attempt to place a dictated text on this PC (ADR-0018).
 *
 * A **superset** of the wire enum [InsertionOutcomeWire], and deliberately so: [FAILED] never
 * travels the wire as an outcome, because a failed insertion is not a delivery with a sad flag —
 * it is a `503 INSERTION_FAILED` with no body. Keeping it in the domain enum is what lets the
 * history record a failed *re-insert* from the UI (where there is no HTTP status to carry it).
 */
enum class InsertionOutcome {

    /** Clipboard set AND Ctrl+V injected into the foreground window. */
    TYPED_CTRL_V,

    /** Clipboard set, nothing typed — no foreground window, UIPI blocked us, or no inserter at all. */
    CLIPBOARD_ONLY,

    /** Not even the clipboard took it. The phone keeps the text (pending part). */
    FAILED,
}

/**
 * @throws IllegalStateException for [InsertionOutcome.FAILED] — a caller that reaches this with a
 * failure has skipped the `503` arm, and silently reporting "delivered" would be the one bug this
 * whole package exists to prevent.
 */
fun InsertionOutcome.toWire(): InsertionOutcomeWire = when (this) {
    InsertionOutcome.TYPED_CTRL_V -> InsertionOutcomeWire.TYPED_CTRL_V
    InsertionOutcome.CLIPBOARD_ONLY -> InsertionOutcomeWire.CLIPBOARD_ONLY
    InsertionOutcome.FAILED -> error("FAILED is not a wire outcome — it must become a 503 INSERTION_FAILED")
}
