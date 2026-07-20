package net.devemperor.dictate.database.migration

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.devemperor.dictate.database.DictateDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [MIGRATION_12_13] (peer-catalog subscriber tables, peer-katalog.md §5).
 *
 * **pending: instrumented — Local-only, no CI run today** (same status as [MigrationTo12Test]): the
 * JVM/Robolectric side is covered by [MigrationTo13MetadataTest] (version pair) and
 * `AndroidCatalogSubscriberStoreTest` (the tables driven by the real engine on an in-memory DB); this
 * suite is the on-device schema-validation + CHECK/FK behaviour that `MigrationTestHelper` needs a real
 * SQLite for. Execute via `./gradlew connectedDebugAndroidTest` on a device/emulator. Tracked in
 * `docs/plans/2026-07-19 - desktop-companion-v1/reports/E/E2-completion.md`.
 *
 * Coverage:
 *  1. the migration validates against the exported 13.json schema (`runMigrationsAndValidate`);
 *  2. `subscriptions.kind`/`mode` Double-Enum CHECKs accept a valid value and reject an unknown one;
 *  3. deleting a peer CASCADEs its subscription rows.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTo13Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DictateDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun SupportSQLiteDatabase.readInt(sql: String): Int =
        query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    @Test
    fun migrate12To13_createsSubscriberTables_andValidatesSchema() {
        helper.createDatabase(TEST_DB, 12).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 13, true, MIGRATION_12_13)

        db.execSQL("INSERT INTO peers (peer_id, display_name, address, device_id, secret_ref, added_at) VALUES ('p1','PC','pc:8756','d1','ref1',0)")
        // A valid kind/mode is accepted …
        db.execSQL("INSERT INTO subscriptions (local_entity_id, peer_id, source_entity_id, kind, mode, last_hash) VALUES ('l1','p1','s1','PROMPT','SUBSCRIBE','h')")
        assertEquals(1, db.readInt("SELECT COUNT(*) FROM subscriptions"))
    }

    @Test
    fun migrate12To13_rejectsAnUnknownKindOrMode() {
        helper.createDatabase(TEST_DB, 12).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 13, true, MIGRATION_12_13)
        db.execSQL("INSERT INTO peers (peer_id, display_name, address, device_id, secret_ref, added_at) VALUES ('p1','PC','pc:8756','d1','ref1',0)")

        assertThrows(Exception::class.java) {
            db.execSQL("INSERT INTO subscriptions (local_entity_id, peer_id, source_entity_id, kind, mode, last_hash) VALUES ('l1','p1','s1','PROMPT','LOCAL','h')")
        }
        assertThrows(Exception::class.java) {
            db.execSQL("INSERT INTO subscriptions (local_entity_id, peer_id, source_entity_id, kind, mode, last_hash) VALUES ('l2','p1','s2','BOGUS','SUBSCRIBE','h')")
        }
    }

    @Test
    fun migrate12To13_peerDeletionCascadesSubscriptions() {
        helper.createDatabase(TEST_DB, 12).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 13, true, MIGRATION_12_13)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("INSERT INTO peers (peer_id, display_name, address, device_id, secret_ref, added_at) VALUES ('p1','PC','pc:8756','d1','ref1',0)")
        db.execSQL("INSERT INTO subscriptions (local_entity_id, peer_id, source_entity_id, kind, mode, last_hash) VALUES ('l1','p1','s1','PROMPT','SUBSCRIBE','h')")

        db.execSQL("DELETE FROM peers WHERE peer_id = 'p1'")

        assertTrue(db.readInt("SELECT COUNT(*) FROM subscriptions") == 0)
    }

    private companion object {
        const val TEST_DB = "migration-13-test"
    }
}
