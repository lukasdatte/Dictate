package net.devemperor.dictate.peers.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.devemperor.dictate.peers.entity.PeerRoomEntity
import net.devemperor.dictate.peers.entity.SubscriptionRoomEntity

/**
 * DAOs for the peer-catalog subscriber tables (peer-katalog.md §5, §6). All enum columns are `String`
 * (Double-Enum rule); the sync engine's write path is [net.devemperor.dictate.peers.AndroidCatalogSubscriberStore].
 */
@Dao
interface PeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: PeerRoomEntity)

    @Query("SELECT * FROM peers WHERE peer_id = :peerId")
    fun byId(peerId: String): PeerRoomEntity?

    @Query("SELECT * FROM peers ORDER BY display_name, peer_id")
    fun getAll(): List<PeerRoomEntity>

    /** A reached-but-unsuccessful attempt (§6.4): only `last_contact_at` moves — no error, just stale. */
    @Query("UPDATE peers SET last_contact_at = :at WHERE peer_id = :peerId")
    fun recordContact(peerId: String, at: Long)

    /**
     * A successful run: both timestamps advance; `last_root_hash` advances ONLY on a clean run. A
     * partial run passes `rootHash = null`, and `coalesce` keeps the old hash so the cheap no-op path
     * cannot skip the still-unresolved change on the next run (§6.1 step 4).
     */
    @Query("UPDATE peers SET last_contact_at = :at, last_success_at = :at, last_root_hash = coalesce(:rootHash, last_root_hash) WHERE peer_id = :peerId")
    fun recordSuccess(peerId: String, at: Long, rootHash: String?)

    @Query("DELETE FROM peers WHERE peer_id = :peerId")
    fun deleteById(peerId: String)
}

@Dao
interface SubscriptionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: SubscriptionRoomEntity)

    @Query("SELECT * FROM subscriptions WHERE local_entity_id = :localEntityId")
    fun byId(localEntityId: String): SubscriptionRoomEntity?

    @Query("SELECT * FROM subscriptions WHERE peer_id = :peerId ORDER BY local_entity_id")
    fun forPeer(peerId: String): List<SubscriptionRoomEntity>

    /** Only SUBSCRIBE rows — the fork-protection query (AC8): a fork deleted its row, ONE_SHOT is frozen. */
    @Query("SELECT * FROM subscriptions WHERE peer_id = :peerId AND mode = 'SUBSCRIBE' ORDER BY local_entity_id")
    fun activeForPeer(peerId: String): List<SubscriptionRoomEntity>

    /** Advance the diff watermark after a verified pull (§6.2). */
    @Query("UPDATE subscriptions SET last_hash = :lastHash, last_checked_at = :at WHERE local_entity_id = :localEntityId")
    fun advance(localEntityId: String, lastHash: String, at: Long)

    /** Source vanished upstream (§6.4): record the check, KEEP the copy (no stored SOURCE_REMOVED flag). */
    @Query("UPDATE subscriptions SET last_checked_at = :at WHERE local_entity_id = :localEntityId")
    fun touchChecked(localEntityId: String, at: Long)

    @Query("DELETE FROM subscriptions WHERE local_entity_id = :localEntityId")
    fun deleteById(localEntityId: String)
}
