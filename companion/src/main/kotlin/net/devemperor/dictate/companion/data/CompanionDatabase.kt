package net.devemperor.dictate.companion.data

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.devemperor.dictate.companion.db.Conversation_messages
import net.devemperor.dictate.companion.db.DictateCompanionDb
import net.devemperor.dictate.companion.db.Dispatch_state
import net.devemperor.dictate.companion.db.Key_command_chords
import net.devemperor.dictate.companion.db.Processing_steps
import net.devemperor.dictate.companion.db.Sessions
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opens the SQLite file and hands back a migrated, ready database.
 *
 * The [EnumColumnAdapter]s are the Kotlin half of the Double-Enum rule: the column is a `TEXT` with
 * a `CHECK`, the field is an enum, and the adapter is the only conversion between them. The session
 * vocabularies are the companion mirrors of Room's enums (pinned by `CompanionSchemaParityTest`);
 * `last_error_type` is `:shared-ai`'s error taxonomy directly, so the shared runners and the archive
 * cannot drift apart (docs/DATABASE-PATTERNS.md, desktop-host.md §3.2).
 */
object CompanionDatabase {

    fun open(path: Path): DictateCompanionDb {
        Files.createDirectories(path.parent)
        return build(JdbcSqliteDriver("jdbc:sqlite:$path"))
    }

    /** Used by the whole test suite — a real SQLite engine, a real schema, no file. */
    fun inMemory(): DictateCompanionDb = build(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

    private fun build(driver: SqlDriver): DictateCompanionDb {
        // SQLite has foreign keys OFF by default, per connection. Without this every ON DELETE CASCADE
        // in the schema is decoration: un-pairing a device would leave its dispatch_state rows behind
        // as orphans, and deleting a session would strand its transcriptions/steps/messages.
        driver.execute(identifier = null, sql = "PRAGMA foreign_keys = ON", parameters = 0)

        SchemaMigrator.migrate(driver)

        return DictateCompanionDb(
            driver = driver,
            // An adapter is generated per table the queries touch: SQLDelight omits an adapter from
            // the DB constructor until a query references its table. `processing_steps` and
            // `conversation_messages` were schema-only in D1a — the desktop-pipeline write queries
            // (D1b, Companion.sq) pull their adapters in here now. `transcriptions` needs none: its
            // only typed column is `is_current AS kotlin.Boolean`, a SQLDelight built-in.
            sessionsAdapter = Sessions.Adapter(
                typeAdapter = EnumColumnAdapter(),
                statusAdapter = EnumColumnAdapter(),
                originAdapter = EnumColumnAdapter(),
                last_error_typeAdapter = EnumColumnAdapter(),
                host_originAdapter = EnumColumnAdapter(),
            ),
            dispatch_stateAdapter = Dispatch_state.Adapter(
                last_outcomeAdapter = EnumColumnAdapter(),
            ),
            key_command_chordsAdapter = Key_command_chords.Adapter(
                commandAdapter = EnumColumnAdapter(),
            ),
            processing_stepsAdapter = Processing_steps.Adapter(
                step_typeAdapter = EnumColumnAdapter(),
                statusAdapter = EnumColumnAdapter(),
                response_formatAdapter = EnumColumnAdapter(),
            ),
            conversation_messagesAdapter = Conversation_messages.Adapter(
                roleAdapter = EnumColumnAdapter(),
            ),
        )
    }
}
