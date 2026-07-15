package net.devemperor.dictate.companion.data

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.devemperor.dictate.companion.db.DictateCompanionDb
import net.devemperor.dictate.companion.db.Key_command_chords
import net.devemperor.dictate.companion.db.Received_texts
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
 */
class ChordMigrationSeedTest {

    @Test
    fun migratingFromV1_seedsEveryDefaultChord() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Pretend this is an existing v1 database so the migrator takes the migrate (not create) path.
        driver.execute(null, "PRAGMA user_version = 1", 0)

        SchemaMigrator.migrate(driver, DictateCompanionDb.Schema)

        val db = DictateCompanionDb(
            driver = driver,
            received_textsAdapter = Received_texts.Adapter(EnumColumnAdapter(), EnumColumnAdapter()),
            key_command_chordsAdapter = Key_command_chords.Adapter(EnumColumnAdapter()),
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
}
