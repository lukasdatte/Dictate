package net.devemperor.dictate.companion.domain.port

import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire

/**
 * The offer-side audit trail of the catalog (R8, peer-katalog.md §4.3 / §5.4).
 *
 * A port so [net.devemperor.dictate.companion.domain.CatalogService] can be tested against a fake and
 * the "every credential delivery writes a row" invariant is asserted without a database. Every
 * credential delivery [record]s exactly one row; entity fetches may be recorded for the offer view
 * (§8.2) but are not security-critical. No payload is ever stored — only who fetched what, and when.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md §4.3, §5.4
 */
interface CatalogAuditLog {

    /** Appends one access row. Called on every credential delivery (security), never conditionally. */
    fun record(peerDeviceId: String, entityId: String, kind: CatalogEntityKindWire, at: Long)

    /** Every access row for one entity, newest first — the offer view's "who fetched this, when". */
    fun accessFor(entityId: String): List<CatalogAccess>

    /** Every access row, newest first. */
    fun all(): List<CatalogAccess>
}

/** One audit row: which device fetched which entity of which kind, at which epoch-millis. */
data class CatalogAccess(
    val peerDeviceId: String,
    val entityId: String,
    val kind: CatalogEntityKindWire,
    val at: Long,
)
