package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.domain.model.Device
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.domain.model.toWire
import net.devemperor.dictate.companion.domain.port.ClockPort
import net.devemperor.dictate.companion.domain.port.DeviceRepository
import net.devemperor.dictate.companion.domain.port.HistoryRepository
import net.devemperor.dictate.companion.domain.port.TextInserter
import net.devemperor.dictate.shared.protocol.DispatchRequest
import net.devemperor.dictate.shared.protocol.DispatchResponse
import net.devemperor.dictate.shared.protocol.SessionUpsert

/**
 * Receives one dictation: insert it, then remember it (ADR-0017).
 *
 * **The order is a contract, not a preference.** Insert first, persist second. If the insertion
 * fails there is no history row and no 200 — the phone keeps the text as a pending part, and the
 * two sides agree on what happened. Persisting first would leave the PC with a row for a text that
 * never arrived while the phone still got its 503: two truths, one of them wrong.
 *
 * The price is the crash window *between* insert and persist: the text is in the user's window but
 * not in the history. The lazy sync closes it on the next run — which is exactly what makes the
 * sync a safety net rather than a nice-to-have (documented as a failure mode in ADR-0017).
 */
class DispatchService(
    private val inserter: TextInserter,
    private val history: HistoryRepository,
    private val devices: DeviceRepository,
    private val clock: ClockPort,
) {

    /** @throws CompanionException.InsertionFailedException when the text could not be placed at all. */
    fun dispatch(device: Device, request: DispatchRequest): DispatchResponse {
        val outcome = inserter.insert(request.text)
        if (outcome == InsertionOutcome.FAILED) throw CompanionException.InsertionFailedException()

        val now = clock.nowMillis()
        val duplicate = history.upsert(
            deviceId = device.deviceId,
            item = SessionUpsert(
                sessionId = request.sessionId,
                text = request.text,
                createdAt = request.createdAt,
                origin = request.origin,
                dispatched = true,
            ),
            receivedAt = now,
        )
        history.recordDispatch(request.sessionId, now, outcome)
        devices.touchLastSeen(device.deviceId, now)

        return DispatchResponse(
            sessionId = request.sessionId,
            delivered = true,
            outcome = outcome.toWire(),
            duplicate = duplicate,
        )
    }

    /**
     * Re-inserts a text the PC already has — the history row's "insert again" button.
     *
     * Goes through the **same** [TextInserter] as [dispatch] on purpose: one insert path, one set of
     * Win32 gotchas, one place to fix them. Unlike [dispatch] a failure is not an exception here —
     * there is no phone waiting on a status code, only a UI that shows the outcome.
     *
     * @return null when [sessionId] is unknown.
     */
    fun reinsert(sessionId: String): InsertionOutcome? {
        val row = history.findById(sessionId) ?: return null
        val outcome = inserter.insert(row.text)
        history.recordDispatch(sessionId, clock.nowMillis(), outcome)
        return outcome
    }
}
