package net.devemperor.dictate.companion.data

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.devemperor.dictate.companion.db.DictateCompanionDb
import net.devemperor.dictate.companion.db.Key_command_chords
import net.devemperor.dictate.companion.db.Received_texts
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opens the SQLite file and hands back a migrated, ready database.
 *
 * The [EnumColumnAdapter]s are the Kotlin half of the Double-Enum rule: the column is a `TEXT` with
 * a `CHECK`, the field is an enum, and the adapter is the only conversion between them. There is no
 * second enum anywhere — `origin` is `:shared`'s wire enum, so the protocol and the database can
 * never drift apart (docs/DATABASE-PATTERNS.md).
 */
object CompanionDatabase {

    fun open(path: Path): DictateCompanionDb {
        Files.createDirectories(path.parent)
        return build(JdbcSqliteDriver("jdbc:sqlite:$path"))
    }

    /** Used by the whole test suite — a real SQLite engine, a real schema, no file. */
    fun inMemory(): DictateCompanionDb = build(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

    private fun build(driver: SqlDriver): DictateCompanionDb {
        // SQLite has foreign keys OFF by default, per connection. Without this the ON DELETE CASCADE
        // on received_texts is decoration: un-pairing a device would leave its texts behind as
        // orphans, readable by the next device that happens to take the same id.
        driver.execute(identifier = null, sql = "PRAGMA foreign_keys = ON", parameters = 0)

        SchemaMigrator.migrate(driver)

        return DictateCompanionDb(
            driver = driver,
            received_textsAdapter = Received_texts.Adapter(
                originAdapter = EnumColumnAdapter(),
                last_outcomeAdapter = EnumColumnAdapter(),
            ),
            key_command_chordsAdapter = Key_command_chords.Adapter(
                commandAdapter = EnumColumnAdapter(),
            ),
        )
    }
}
