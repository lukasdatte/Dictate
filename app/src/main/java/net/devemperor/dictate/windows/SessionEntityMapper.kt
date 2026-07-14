package net.devemperor.dictate.windows

import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.protocol.SessionUpsert

/**
 * Maps a Room [SessionEntity] to the wire [SessionUpsert] (ADR-0020).
 *
 * Pure — no Android, no Room access; the caller resolves the [dispatched] flag (which needs the
 * audit table) and hands it in, so this stays a plain, testable value mapping.
 */
object SessionEntityMapper {

    fun toUpsert(entity: SessionEntity, dispatched: Boolean): SessionUpsert =
        SessionUpsert(
            sessionId = entity.id,
            text = entity.finalOutputText.orEmpty(),
            createdAt = entity.createdAt,
            origin = originToWire(entity.origin),
            dispatched = dispatched,
        )

    /**
     * The app's `SessionOrigin` name onto the protocol enum. A separate wire enum on purpose
     * (Dtos.kt): an app-side origin the protocol does not know lands on [SessionOriginWire.UNKNOWN]
     * instead of dragging the protocol along with an internal refactor.
     *
     * Public + `@JvmStatic` so the two dispatch producers (the IME seam in Java, the headless sink
     * in Kotlin) resolve a session's wire origin through this ONE mapping — no second, drifting copy.
     */
    @JvmStatic
    fun originToWire(origin: String): SessionOriginWire =
        runCatching { SessionOriginWire.valueOf(origin) }.getOrDefault(SessionOriginWire.UNKNOWN)
}
