package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.domain.model.Device
import net.devemperor.dictate.companion.domain.port.ClockPort
import net.devemperor.dictate.companion.domain.port.HistoryRepository
import net.devemperor.dictate.shared.protocol.CursorResponse
import net.devemperor.dictate.shared.protocol.SyncRequest
import net.devemperor.dictate.shared.protocol.SyncResponse

/**
 * The server half of the lazy sync (ADR-0020).
 *
 * **The server holds the cursor, and the server is the one that may not lie about it.** The phone
 * asks "how far do you know me?" at the start of every run, and pages on from the answer — so a
 * cursor that does not advance after an accepted page would make the phone push the same rows for
 * ever. `SyncClient` guards against exactly that (it stops with `Stalled`), which is why the
 * response's cursor is read back *after* the write rather than computed from the request.
 *
 * A wiped database answers `cursor = null`, and the phone resends its history from the beginning.
 * That is not a degenerate case to be defended against — it is the entire self-healing story.
 */
class SyncService(
    private val history: HistoryRepository,
    private val clock: ClockPort,
) {

    fun cursor(): CursorResponse = CursorResponse(cursor = history.cursor())

    fun apply(device: Device, request: SyncRequest): SyncResponse {
        val accepted = history.upsertAll(
            deviceId = device.deviceId,
            items = request.items,
            receivedAt = clock.nowMillis(),
        )

        return SyncResponse(accepted = accepted, cursor = history.cursor())
    }
}
