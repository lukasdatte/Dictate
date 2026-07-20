package net.devemperor.dictate.companion.data

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.devemperor.dictate.companion.db.Catalog_access_log
import net.devemperor.dictate.companion.db.DictateCompanionDb
import net.devemperor.dictate.companion.db.Conversation_messages
import net.devemperor.dictate.companion.db.Dispatch_state
import net.devemperor.dictate.companion.db.Key_command_chords
import net.devemperor.dictate.companion.db.Model_refs
import net.devemperor.dictate.companion.db.Processing_steps
import net.devemperor.dictate.companion.db.Profiles
import net.devemperor.dictate.companion.db.Prompts
import net.devemperor.dictate.companion.db.Provider_configs
import net.devemperor.dictate.companion.db.Sessions
import net.devemperor.dictate.companion.db.Subscriptions
import net.devemperor.dictate.companion.domain.model.ChordModifier
import net.devemperor.dictate.companion.domain.model.DefaultChords
import net.devemperor.dictate.companion.domain.model.KeyChord
import net.devemperor.dictate.companion.domain.model.KeyCommand
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The v1 → v2 migration **seeds the defaults** (criterion 11, §5.4).
 *
 * A fresh install runs `schema.create` and skips this seed (the repository's DefaultChords fallback
 * covers it), so the seed is proven on the *upgrade* path: an on-disk v1 database migrated forward
 * must end up with an explicit row per command, each equal to [DefaultChords] — which also pins the
 * SQL seed against the Kotlin SSoT (a drift on either side fails here).
 *
 * The fixture creates the **real** v1 tables (devices / received_texts / settings) before stamping
 * the version: `SchemaMigrator` migrates all the way to the current schema, so the run now also
 * replays 2.sqm — which backfills and drops `received_texts`. A version stamp alone, with no v1
 * tables, would make 2.sqm fail on the missing `received_texts`.
 */
class ChordMigrationSeedTest {

    @Test
    fun migratingFromV1_seedsEveryDefaultChord() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)

        // The real schema v1: what an on-disk database created by the first release looks like. The
        // migration chain (1.sqm chords, then 2.sqm session model) runs on top of exactly this.
        driver.execute(null, "CREATE TABLE devices (device_id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, secret_hash TEXT NOT NULL, paired_at INTEGER NOT NULL, last_seen_at INTEGER)", 0)
        driver.execute(null, V1_RECEIVED_TEXTS, 0)
        driver.execute(null, "CREATE INDEX received_texts_cursor ON received_texts(created_at, session_id)", 0)
        driver.execute(null, "CREATE TABLE settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)", 0)
        // Pretend this is an existing v1 database so the migrator takes the migrate (not create) path.
        driver.execute(null, "PRAGMA user_version = 1", 0)

        SchemaMigrator.migrate(driver, DictateCompanionDb.Schema)

        val db = DictateCompanionDb(
            driver = driver,
            sessionsAdapter = Sessions.Adapter(
                EnumColumnAdapter(), EnumColumnAdapter(), EnumColumnAdapter(), EnumColumnAdapter(), EnumColumnAdapter(),
            ),
            dispatch_stateAdapter = Dispatch_state.Adapter(EnumColumnAdapter()),
            key_command_chordsAdapter = Key_command_chords.Adapter(EnumColumnAdapter()),
            processing_stepsAdapter = Processing_steps.Adapter(
                EnumColumnAdapter(), EnumColumnAdapter(), EnumColumnAdapter(),
            ),
            conversation_messagesAdapter = Conversation_messages.Adapter(EnumColumnAdapter()),
            provider_configsAdapter = Provider_configs.Adapter(
                EnumColumnAdapter(), EnumColumnAdapter(), EnumColumnAdapter(), EnumColumnAdapter(),
            ),
            model_refsAdapter = Model_refs.Adapter(
                EnumColumnAdapter(), EnumColumnAdapter(), EnumColumnAdapter(),
            ),
            promptsAdapter = Prompts.Adapter(EnumColumnAdapter(), EnumColumnAdapter()),
            profilesAdapter = Profiles.Adapter(
                EnumColumnAdapter(), EnumColumnAdapter(), EnumColumnAdapter(), EnumColumnAdapter(), EnumColumnAdapter(),
            ),
            catalog_access_logAdapter = Catalog_access_log.Adapter(EnumColumnAdapter()),
            subscriptionsAdapter = Subscriptions.Adapter(EnumColumnAdapter(), EnumColumnAdapter()),
        )
        val seeded = db.companionQueries.allChords().executeAsList()

        // Explicit rows, not a fallback: exactly one per command, each equal to the default.
        assertEquals(KeyCommand.entries.size, seeded.size)
        seeded.forEach { row ->
            val modifiers = buildSet {
                if (row.ctrl) add(ChordModifier.CTRL)
                if (row.shift) add(ChordModifier.SHIFT)
                if (row.alt) add(ChordModifier.ALT)
                if (row.win) add(ChordModifier.WIN)
            }
            assertEquals(
                "seed for ${row.command} drifted from DefaultChords",
                DefaultChords.chordFor(row.command),
                KeyChord(modifiers, row.vk.toInt()),
            )
        }
    }

    private companion object {
        val V1_RECEIVED_TEXTS = """
            CREATE TABLE received_texts (
                session_id   TEXT NOT NULL PRIMARY KEY,
                device_id    TEXT NOT NULL,
                text         TEXT NOT NULL,
                created_at   INTEGER NOT NULL,
                received_at  INTEGER NOT NULL,
                origin       TEXT NOT NULL
                             CHECK (origin IN ('KEYBOARD','HISTORY_REPROCESS','POST_PROCESSING','REVIEW_REFINEMENT','UNKNOWN')),
                dispatched   INTEGER NOT NULL DEFAULT 0,
                last_outcome TEXT
                             CHECK (last_outcome IS NULL OR last_outcome IN ('TYPED_CTRL_V','CLIPBOARD_ONLY','FAILED')),
                FOREIGN KEY (device_id) REFERENCES devices(device_id) ON DELETE CASCADE
            )
        """.trimIndent()
    }
}
