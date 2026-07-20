package net.devemperor.dictate.companion.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.domain.model.ChordModifier
import net.devemperor.dictate.companion.domain.model.DefaultChords
import net.devemperor.dictate.companion.domain.model.KeyChord
import net.devemperor.dictate.companion.domain.model.KeyCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DB-backed chord repository (§B2): persistence, fallback, reset — and the SQL CHECK half of the
 * Double-Enum rule for the chord table (the parity of [CompanionSchemaParityTest], for chords).
 */
class SqlDelightChordMappingRepositoryTest {

    private val database = CompanionDatabase.inMemory()
    private val repository = SqlDelightChordMappingRepository(database)

    @Test
    fun aMissingRow_fallsBackToTheDefault() {
        // A fresh in-memory DB is created (not migrated), so it is unseeded — every read must still
        // return the default rather than crashing on a null row.
        KeyCommand.entries.forEach { command ->
            assertEquals(DefaultChords.chordFor(command), repository.chordFor(command))
        }
    }

    @Test
    fun anUpdate_persistsAndReadsBack() {
        val rebound = KeyChord(setOf(ChordModifier.CTRL, ChordModifier.SHIFT), vk = 0x5A) // Ctrl+Shift+Z

        repository.update(KeyCommand.REDO, rebound)

        assertEquals(rebound, repository.chordFor(KeyCommand.REDO))
        // A brand-new repository over the SAME database sees it too → it is really persisted.
        assertEquals(rebound, SqlDelightChordMappingRepository(database).chordFor(KeyCommand.REDO))
    }

    @Test
    fun aModifierlessRebind_roundTrips() {
        val rebound = KeyChord(emptySet(), vk = 0x2E) // VK_DELETE — no modifiers

        repository.update(KeyCommand.BACKSPACE, rebound)

        assertEquals(rebound, repository.chordFor(KeyCommand.BACKSPACE))
    }

    @Test
    fun reset_clearsEveryOverride_backToDefaults() {
        repository.update(KeyCommand.REDO, KeyChord(setOf(ChordModifier.ALT), vk = 0x59))
        repository.update(KeyCommand.UNDO, KeyChord(setOf(ChordModifier.ALT), vk = 0x5A))

        repository.resetToDefaults()

        assertEquals(DefaultChords.chordFor(KeyCommand.REDO), repository.chordFor(KeyCommand.REDO))
        assertEquals(DefaultChords.chordFor(KeyCommand.UNDO), repository.chordFor(KeyCommand.UNDO))
    }

    @Test
    fun aVkOutsideTheRange_isRejectedByTheColumnCheck() {
        // The typed API cannot even build such a chord (the KeyChord factory throws first), so the DB
        // CHECK is probed with raw SQL — the second half of the Double-Enum rule, pinned directly.
        val driver: SqlDriver = rawDriver()

        val failure = runCatching {
            driver.execute(null, "INSERT INTO key_command_chords(command, ctrl, shift, alt, win, vk) VALUES ('REDO', 1, 0, 0, 0, 300)", 0)
        }.exceptionOrNull()

        assertTrue("$failure", failure!!.message!!.contains("CHECK constraint failed"))
    }

    @Test
    fun anUnknownCommand_isRejectedByTheColumnCheck() {
        val driver: SqlDriver = rawDriver()

        val failure = runCatching {
            driver.execute(null, "INSERT INTO key_command_chords(command, ctrl, shift, alt, win, vk) VALUES ('TELEPORT', 0, 0, 0, 0, 65)", 0)
        }.exceptionOrNull()

        assertTrue("$failure", failure!!.message!!.contains("CHECK constraint failed"))
    }

    /** A driver with the real schema created (all tables), for raw-SQL CHECK probing. */
    private fun rawDriver(): SqlDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SchemaMigrator.migrate(driver)
        return driver
    }
}
