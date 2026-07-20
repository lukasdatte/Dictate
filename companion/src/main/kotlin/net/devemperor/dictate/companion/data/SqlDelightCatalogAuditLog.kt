package net.devemperor.dictate.companion.data

import net.devemperor.dictate.companion.db.DictateCompanionDb
import net.devemperor.dictate.companion.domain.port.CatalogAccess
import net.devemperor.dictate.companion.domain.port.CatalogAuditLog
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire

/**
 * The `catalog_access_log` write/read path (peer-katalog.md §5.4) — the SqlDelight impl of
 * [CatalogAuditLog].
 *
 * A credential delivery calls [record] exactly once, so the offer view (§8.2) and any security review
 * can answer "who fetched which credential, and when". No payload is stored — only who/what/when.
 */
class SqlDelightCatalogAuditLog(
    database: DictateCompanionDb,
) : CatalogAuditLog {

    private val queries = database.companionQueries

    override fun record(peerDeviceId: String, entityId: String, kind: CatalogEntityKindWire, at: Long) {
        queries.recordCatalogAccess(peerDeviceId = peerDeviceId, entityId = entityId, kind = kind, at = at)
    }

    override fun accessFor(entityId: String): List<CatalogAccess> =
        queries.catalogAccessForEntity(entityId).executeAsList().map { it.toDomain() }

    override fun all(): List<CatalogAccess> =
        queries.allCatalogAccess().executeAsList().map { it.toDomain() }

    private fun net.devemperor.dictate.companion.db.Catalog_access_log.toDomain() =
        CatalogAccess(peerDeviceId = peer_device_id, entityId = entity_id, kind = kind, at = at)
}
