package net.devemperor.dictate.windows

import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.InputCommandWire
import java.util.concurrent.Executor

/**
 * The 500-ms send window for keyboard actions (§4.3.2, D5 — linger-only-when-busy).
 *
 * The first action of a burst goes out **immediately** — no artificial delay on a single keystroke.
 * While a request is in-flight, subsequent actions accumulate in a buffer and are flushed as **one**
 * coalesced batch when the response returns (HTTP/1.1 has no pipelining, so there is never more than
 * one request on the wire — which is also what gives the total ordering, Akzeptanzkriterium 8).
 *
 * **Failure = discard, one message, no retry** (Entscheidung 4). A failed batch and everything
 * buffered behind it are dropped together with a single [emitFailure]; a network fault additionally
 * opens a [CIRCUIT_OPEN_MS] cooldown during which further actions are dropped silently, so a held
 * cursor-swipe over a dead link produces exactly one notice, not a flood. Nothing is ever re-sent —
 * a retry would reorder against keys pressed in the meantime (the ordering invariant).
 *
 * **Threading:** [submit] is called on the UI thread and only touches the buffer under [lock]; the
 * blocking send runs on [executor] — the **same** single-thread executor as the dictation dispatch,
 * so keyboard actions and dictation can never reorder relative to each other.
 */
class PcInputCoordinator(
    private val send: (List<InputCommandWire>) -> PcSendResult,
    private val emitFailure: (PcInputFailure) -> Unit,
    private val executor: Executor,
    private val clock: () -> Long,
) {

    private val lock = Any()
    private val buffer = ArrayDeque<InputCommandWire>()
    private var flushScheduled = false
    private var circuitOpenUntil = 0L

    /** Enqueue one action's wire commands (already mapped by [PcInputCommandMapper]). */
    fun submit(commands: List<InputCommandWire>) {
        if (commands.isEmpty()) return
        synchronized(lock) {
            // Circuit open after a network fault: drop silently — the one notice was already shown.
            if (clock() < circuitOpenUntil) return
            buffer.addAll(commands)
            if (!flushScheduled) {
                flushScheduled = true
                executor.execute(::flush)
            }
        }
    }

    private fun flush() {
        val batch = synchronized(lock) {
            if (buffer.isEmpty()) {
                flushScheduled = false
                return
            }
            val coalesced = PcInputCommandMapper.coalesce(buffer.toList())
            buffer.clear()
            // One request never exceeds the batch cap; the overflow waits for the next flush.
            if (coalesced.size > Endpoints.MAX_INPUT_BATCH) {
                coalesced.drop(Endpoints.MAX_INPUT_BATCH).forEach(buffer::addLast)
                coalesced.take(Endpoints.MAX_INPUT_BATCH)
            } else {
                coalesced
            }
        }

        val result = send(batch) // blocking, on the shared dispatch executor

        synchronized(lock) {
            when (result) {
                is PcSendResult.Sent ->
                    if (buffer.isEmpty()) flushScheduled = false else executor.execute(::flush)

                is PcSendResult.Failed -> {
                    // Discard the whole window together — no partial replay, no retry.
                    buffer.clear()
                    flushScheduled = false
                    if (result.opensCircuit) circuitOpenUntil = clock() + CIRCUIT_OPEN_MS
                    emitFailure(result.failure)
                }
            }
        }
    }

    companion object {
        /** The buffered actions are never held longer than this before the failure path discards them. */
        const val SEND_WINDOW_MS = 500L

        /** After a network fault the link is treated as down for this long — kills the message flood. */
        const val CIRCUIT_OPEN_MS = 3_000L
    }
}
