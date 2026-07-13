package net.devemperor.dictate.shared.sync

import net.devemperor.dictate.shared.client.DispatchClient
import net.devemperor.dictate.shared.client.DispatchError
import net.devemperor.dictate.shared.client.getOrElse
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.SyncCursor

/**
 * How a sync run ended.
 *
 * Three of the four are *successes of different completeness*, which is why this is not the generic
 * `DispatchResult`: a sync that pushed 200 rows and then lost the network did real work, and the
 * caller must be able to tell that apart from one that did nothing.
 */
sealed class SyncOutcome {

    /** The server is level with the phone. [sent] rows were pushed to get there (0 = nothing to do). */
    data class UpToDate(val sent: Int) : SyncOutcome()

    /** [sent] rows made it, then the call failed. The pages that got through are acknowledged and stay so. */
    data class Partial(val sent: Int, val error: DispatchError) : SyncOutcome()

    /** The per-run page cap was reached. The next trigger continues where this one stopped. */
    data class Truncated(val sent: Int) : SyncOutcome()

    /**
     * The server acknowledged a page but its cursor did not move.
     *
     * A broken or half-implemented server; continuing would push the same rows for ever. Stopping
     * is the only honest answer — and it says so, rather than looking like a cap was hit.
     */
    data class Stalled(val sent: Int, val cursor: SyncCursor?) : SyncOutcome()

    /** The run could not even start — the cursor could not be fetched. Nothing was sent. */
    data class Failed(val error: DispatchError) : SyncOutcome()
}

/**
 * Pushes the history the PC does not have yet, in pages, until it is level (ADR-0020).
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015). Blocking; the caller owns the thread.
 *
 * **The server holds the cursor, not the phone.** If the phone remembered how far it had synced, a
 * companion that came back with a wiped database would be invisible — the phone would believe
 * everything was already over there. Asking "how far do you know me?" at the start of every run
 * costs one round trip and makes the sync self-healing.
 *
 * **Lazy, never on the critical path.** A sync failure must never devalue a dispatch that
 * succeeded; the text is already on the PC. That is why this returns an outcome instead of
 * throwing, and why the caller fires it and forgets it.
 *
 * Restarting an interrupted run is safe and needs no repair: an upsert is idempotent over its
 * `sessionId`, so a page that was sent twice changes nothing on the second pass.
 */
class SyncClient(
    private val client: DispatchClient,
    private val source: SyncSource,
    private val batchSize: Int = Endpoints.MAX_SYNC_BATCH,
    /** A freshly paired PC against a 10 000-session history must not hold the executor for minutes. */
    private val maxBatches: Int = 20,
    /** "No silent caps": a truncated or stalled run says so out loud. The Android side passes Log::i. */
    private val log: (String) -> Unit = {},
) {

    init {
        require(batchSize in 1..Endpoints.MAX_SYNC_BATCH) {
            "batchSize must be within 1..${Endpoints.MAX_SYNC_BATCH}, was $batchSize"
        }
        require(maxBatches > 0) { "maxBatches must be positive, was $maxBatches" }
    }

    fun sync(): SyncOutcome {
        var cursor = client.cursor()
            .getOrElse { return SyncOutcome.Failed(it) }
            .cursor
        var sent = 0

        repeat(maxBatches) {
            val page = source.sessionsAfter(cursor, batchSize)
            if (page.isEmpty()) return SyncOutcome.UpToDate(sent)

            val response = client.sync(page).getOrElse { error ->
                return SyncOutcome.Partial(sent, error)
            }
            sent += response.accepted

            // The server just accepted the page, so its watermark must be at least the last row of
            // it. Anything less means the rows were acknowledged but not recorded — and paging on
            // would hand the server the very same page again, for ever.
            val acknowledged = page.last().toCursor()
            val advanced = response.cursor
            if (advanced == null || acknowledged.isAfter(advanced)) {
                log("windows-sync: server cursor did not advance after $sent row(s) — stopping")
                return SyncOutcome.Stalled(sent, advanced)
            }
            cursor = advanced

            // A short page is the last page — the phone has nothing beyond it.
            if (page.size < batchSize) return SyncOutcome.UpToDate(sent)
        }

        log("windows-sync: page cap of $maxBatches reached after $sent row(s) — the next trigger continues")
        return SyncOutcome.Truncated(sent)
    }
}
