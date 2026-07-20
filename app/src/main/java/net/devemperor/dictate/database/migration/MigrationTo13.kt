package net.devemperor.dictate.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * M12 → M13 introduces the phone's **peer-catalog subscriber tables** (peer-katalog.md §5.1/§5.3):
 *
 *  - `peers` — a peer we subscribe to (we are its HTTP client): its address, our pairing identity
 *    (`device_id` + the `secret_ref` behind which the pairing secret sits in the SecretStore, never a
 *    plaintext column), and the staleness/no-op watermark (`last_contact_at`/`last_success_at`/
 *    `last_root_hash`).
 *  - `subscriptions` — the sync journal: which local entity copy mirrors which peer entity, in which
 *    mode, at which hash. `kind`/`mode` carry their Double-Enum `CHECK` (docs/DATABASE-PATTERNS.md);
 *    `mode` is the SUBSET `SUBSCRIBE`/`ONE_SHOT` — a subscription row is always an ACTIVE binding, and
 *    a fork DELETES its row (§5.3), so the sync's `mode = 'SUBSCRIBE'` query is the fork protection.
 *
 * Purely additive: no existing table is touched, no row migrated. The §5.2 `source_peer_id` FK from the
 * config-entity tables to `peers` is deliberately NOT added — those provenance columns already exist
 * (M11→M12) and a DB-level cascade is unnecessary for the sync, whose store code is the referential
 * authority (peer deletion cascades the subscription rows via the FK below; a config copy that outlives
 * its peer simply keeps its `source_peer_id` as display provenance).
 *
 * Room's `validateMigration` ignores `CHECK` and SQLite `DEFAULT` clauses (same as [MIGRATION_11_12]),
 * so the extra CHECKs stay invisible to schema validation; the FK + index below DO validate and mirror
 * exactly what Room generates for [net.devemperor.dictate.peers.entity.SubscriptionRoomEntity].
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md §5
 * @see docs/DATABASE-PATTERNS.md
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS peers (
                peer_id TEXT NOT NULL PRIMARY KEY,
                display_name TEXT NOT NULL,
                address TEXT NOT NULL,
                device_id TEXT NOT NULL,
                secret_ref TEXT NOT NULL,
                added_at INTEGER NOT NULL,
                last_contact_at INTEGER,
                last_success_at INTEGER,
                last_root_hash TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS subscriptions (
                local_entity_id TEXT NOT NULL PRIMARY KEY,
                peer_id TEXT NOT NULL,
                source_entity_id TEXT NOT NULL,
                kind TEXT NOT NULL
                    CHECK (kind IN ('PROVIDER_CONFIG','MODEL_REF','PROMPT','PROFILE','CREDENTIAL','UNKNOWN')),
                mode TEXT NOT NULL CHECK (mode IN ('SUBSCRIBE','ONE_SHOT')),
                last_hash TEXT NOT NULL,
                last_checked_at INTEGER,
                FOREIGN KEY (peer_id) REFERENCES peers(peer_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_subscriptions_peer_id ON subscriptions(peer_id)")
    }
}
