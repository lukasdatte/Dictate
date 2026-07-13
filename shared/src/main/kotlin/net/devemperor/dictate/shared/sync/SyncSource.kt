package net.devemperor.dictate.shared.sync

import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.protocol.SyncCursor

/**
 * The port to the local history — the phone's Room database on one side, a fake in the tests.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015). Room stays in `:app` (`AndroidSyncSource`); `:shared` never sees it.
 *
 * **The phone is the authority.** The server never sends history back; its cursor is nothing but a
 * receive acknowledgement, and an upsert simply overwrites. That is why this port is one-way.
 */
interface SyncSource {

    /**
     * The sessions strictly after [cursor], ordered by `(createdAt, sessionId)` ascending, at most
     * [limit] of them. A null [cursor] means "from the very beginning".
     *
     * The **ordering is part of the contract**, not an implementation detail: [SyncClient] pages by
     * taking the server's new watermark and asking again, so an implementation that returns rows
     * out of order would make the sync skip sessions.
     *
     * The scope is a deliberate decision with a privacy consequence (ADR-0020): this returns
     * **every** completed session with text, including ones that were never dispatched to the PC —
     * so every dictation ends up as a plaintext copy on the PC, not only the ones the user
     * actively sent. `REVIEW_REFINEMENT` carriers, non-completed and textless sessions are excluded.
     */
    fun sessionsAfter(cursor: SyncCursor?, limit: Int): List<SessionUpsert>
}
