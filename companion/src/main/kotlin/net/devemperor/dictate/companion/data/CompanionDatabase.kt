package net.devemperor.dictate.companion.data

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.devemperor.dictate.companion.db.Catalog_access_log
import net.devemperor.dictate.companion.db.Conversation_messages
import net.devemperor.dictate.companion.db.DictateCompanionDb
import net.devemperor.dictate.companion.db.Dispatch_state
import net.devemperor.dictate.companion.db.Key_command_chords
import net.devemperor.dictate.companion.db.Model_refs
import net.devemperor.dictate.companion.db.Processing_steps
import net.devemperor.dictate.companion.db.Profiles
import net.devemperor.dictate.companion.db.Prompts
import net.devemperor.dictate.companion.db.Provider_configs
import net.devemperor.dictate.companion.db.Sessions
import net.devemperor.dictate.companion.db.Subscriptions
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
            // Config-entity tables (D3, §9). Every finite-set column is a Double-Enum backed by a
            // `:shared.config` enum — the SAME source C2 Room uses, so Android and the companion cannot
            // drift (pinned by ConfigEntityCheckParityTest). Booleans/JSON columns need no adapter.
            provider_configsAdapter = Provider_configs.Adapter(
                provider_typeAdapter = EnumColumnAdapter(),
                kindAdapter = EnumColumnAdapter(),
                visibilityAdapter = EnumColumnAdapter(),
                subscription_modeAdapter = EnumColumnAdapter(),
            ),
            model_refsAdapter = Model_refs.Adapter(
                functionAdapter = EnumColumnAdapter(),
                visibilityAdapter = EnumColumnAdapter(),
                subscription_modeAdapter = EnumColumnAdapter(),
            ),
            promptsAdapter = Prompts.Adapter(
                visibilityAdapter = EnumColumnAdapter(),
                subscription_modeAdapter = EnumColumnAdapter(),
            ),
            profilesAdapter = Profiles.Adapter(
                style_prompt_modeAdapter = EnumColumnAdapter(),
                system_prompt_modeAdapter = EnumColumnAdapter(),
                ambiguity_modeAdapter = EnumColumnAdapter(),
                visibilityAdapter = EnumColumnAdapter(),
                subscription_modeAdapter = EnumColumnAdapter(),
            ),
            // Peer-catalog audit (E1, §5.4). `kind` is a Double-Enum backed by the `:shared.protocol`
            // wire enum (pinned by CatalogAccessCheckParityTest).
            catalog_access_logAdapter = Catalog_access_log.Adapter(
                kindAdapter = EnumColumnAdapter(),
            ),
            // Consumer-side subscriptions (E3 Explorer queries pulled this adapter in; `peers` has no
            // typed column and stays adapter-less). `kind` is the wire enum, `mode` the SAME
            // `:shared.config.SubscriptionMode` the entity tables use (Companion.sq §5.3 note).
            subscriptionsAdapter = Subscriptions.Adapter(
                kindAdapter = EnumColumnAdapter(),
                modeAdapter = EnumColumnAdapter(),
            ),
        )
    }
}
