package net.devemperor.dictate.peers.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire

/**
 * Room persistence for the phone's peer-catalog subscriber tables (peer-katalog.md §5.1/§5.3).
 *
 * The finite-set columns store the enum `name()` as `String` and expose a `xxxEnum` accessor with a
 * `getOrDefault` fallback — the **Double-Enum** rule (docs/DATABASE-PATTERNS.md); the matching SQL
 * `CHECK` constraints live in [net.devemperor.dictate.database.migration.MIGRATION_12_13], exactly as
 * the config-entity tables do (`ConfigRoomEntities` + `MIGRATION_11_12`).
 *
 * ## No secret column (F12)
 *
 * [PeerRoomEntity.secretRef] is the SecretStore handle of our pairing secret FOR this peer, never the
 * secret itself; a subscribed credential's plaintext lives in the SecretStore too, never a column.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md §5.1, §5.3
 */
@Entity(tableName = "peers")
data class PeerRoomEntity(
    @PrimaryKey @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "address") val address: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "secret_ref") val secretRef: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    // Last ATTEMPT (even failed) — drives no staleness.
    @ColumnInfo(name = "last_contact_at") val lastContactAt: Long? = null,
    // Last SUCCESSFUL contact — the staleness basis, derived in the UI, never a status column.
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Long? = null,
    // Root hash at the last successful run — the cheap change detector.
    @ColumnInfo(name = "last_root_hash") val lastRootHash: String? = null,
)

/**
 * The sync journal row: which local copy mirrors which peer entity, in which mode, at which hash.
 * [mode] is the SUBSET `SUBSCRIBE`/`ONE_SHOT` (a fork deletes the row, §5.3); [kind] is the wire kind.
 */
@Entity(
    tableName = "subscriptions",
    foreignKeys = [
        ForeignKey(
            entity = PeerRoomEntity::class,
            parentColumns = ["peer_id"],
            childColumns = ["peer_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["peer_id"], name = "index_subscriptions_peer_id")],
)
data class SubscriptionRoomEntity(
    @PrimaryKey @ColumnInfo(name = "local_entity_id") val localEntityId: String,
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "source_entity_id") val sourceEntityId: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "mode") val mode: String,
    @ColumnInfo(name = "last_hash") val lastHash: String,
    @ColumnInfo(name = "last_checked_at") val lastCheckedAt: Long? = null,
) {
    val kindEnum: CatalogEntityKindWire
        get() = runCatching { CatalogEntityKindWire.valueOf(kind) }.getOrDefault(CatalogEntityKindWire.UNKNOWN)
    val modeEnum: SubscriptionMode
        get() = runCatching { SubscriptionMode.valueOf(mode) }.getOrDefault(SubscriptionMode.LOCAL)
}
