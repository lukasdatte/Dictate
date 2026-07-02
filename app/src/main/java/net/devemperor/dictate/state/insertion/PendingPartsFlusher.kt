package net.devemperor.dictate.state.insertion

import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.PendingSession

/**
 * Inserts deferred pending parts back into the host field in recording
 * order (R4), as separate sequential commits — one commit + one audit row
 * per session. Stops at the first failed commit: nothing is consumed
 * without a successful insert.
 *
 * **Insert-first, consume-after.** Each part is inserted *before* its
 * consumption is dispatched. The pre-R4 accept side-channel dispatched
 * [Action.PendingSessionsAction.AcceptAndInsert] *first* and committed
 * after — so a dead `InputConnection` between dispatch and commit lost the
 * text while the session was already marked inserted. This flusher reverses
 * that order: a failed commit leaves the part (and every later part)
 * pending, and only a [InsertionResult.Committed] dispatches the per-part
 * consumption.
 *
 * **Separator policy (D4).** Within one flush batch the first part is
 * inserted bare; parts 2..n are prefixed with a single space
 * ([SEPARATOR]). Consistent with today's single-part accept and with live
 * pipeline commits (which add no spacing either); a future pref can widen
 * this — the policy is the one named constant below.
 *
 * Pure Kotlin: the [InsertionService] hides every Android/DB concern, so
 * the batch spine is exercised in JVM unit tests.
 *
 * @see net.devemperor.dictate.state.insertion.InsertionPolicy.PENDING_PART
 * @see docs/decisions/0009-pipeline-run-queue-serialized-concurrency.md
 * @see docs/research/2026-07-02 - concurrent-recording-deferred-insertion.md §3.5
 */
class PendingPartsFlusher(
    private val insertion: InsertionService,
    private val dispatch: (Action) -> Unit,
) {
    /**
     * Insert [parts] in the given (recording) order. Each successful commit
     * dispatches an [Action.PendingSessionsAction.AcceptAndInsert] for its
     * session (consume + `markInserted`). The first non-[InsertionResult.Committed]
     * outcome stops the flush and leaves that part plus all later parts
     * pending.
     *
     * @return the number of parts successfully inserted.
     */
    fun flush(parts: List<PendingPart>): Int {
        var inserted = 0
        for ((index, part) in parts.withIndex()) {
            val text = if (index > 0) SEPARATOR + part.text else part.text
            val result = insertion.insert(
                InsertionRequest(
                    text = text,
                    source = InsertionSource.PENDING_PART,
                    policy = InsertionPolicy.PENDING_PART,
                    sessionIdOverride = part.sessionId,
                ),
            )
            if (result is InsertionResult.Committed) {
                dispatch(Action.PendingSessionsAction.AcceptAndInsert(part.sessionId))
                inserted++
            } else {
                // Stop-on-failure: the failed part and every later part
                // remain pending (nothing consumed without a commit).
                break
            }
        }
        return inserted
    }

    companion object {
        /** D4 — single-space joiner between consecutive parts in a batch. */
        const val SEPARATOR = " "
    }
}

/** One deferred part to flush: the session id (for audit + consume) and its text. */
data class PendingPart(val sessionId: String, val text: String)

/**
 * The COMPLETED pending sessions with non-null text, in recording order
 * (ascending `created_at`). Single source of truth for the deferred-parts
 * selection so the [net.devemperor.dictate.state.infobar.InfoBarSelector]
 * aggregate item and the IME flush read the exact same ordered set.
 *
 * @see PendingPartsFlusher
 */
fun orderedCompletedParts(pendingSessions: List<PendingSession>): List<PendingSession> =
    pendingSessions
        .filter { it.status == SessionStatus.COMPLETED && it.transcribedText != null }
        .sortedBy { it.createdAt }

/**
 * The ordered pending parts (recording order) ready for
 * [PendingPartsFlusher.flush], mapped from the bound state's
 * `pendingSessions`. Convenience over [orderedCompletedParts] for the
 * IME side (Java), which only needs `(sessionId, text)` per part.
 */
fun pendingPartsToFlush(pendingSessions: List<PendingSession>): List<PendingPart> =
    orderedCompletedParts(pendingSessions).map {
        // transcribedText is non-null by the orderedCompletedParts filter.
        PendingPart(it.sessionId, it.transcribedText!!)
    }
