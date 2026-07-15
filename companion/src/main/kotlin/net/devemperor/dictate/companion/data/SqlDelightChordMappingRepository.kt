package net.devemperor.dictate.companion.data

import net.devemperor.dictate.companion.db.DictateCompanionDb
import net.devemperor.dictate.companion.db.Key_command_chords
import net.devemperor.dictate.companion.domain.model.ChordModifier
import net.devemperor.dictate.companion.domain.model.DefaultChords
import net.devemperor.dictate.companion.domain.model.KeyChord
import net.devemperor.dictate.companion.domain.model.KeyCommand
import net.devemperor.dictate.companion.domain.port.ChordMappingRepository

/**
 * The DB-backed command → chord mapping (D6, §5.4) — persists a rebinding across companion restarts.
 *
 * A missing row is **not** an error: it falls back to [DefaultChords]. That covers a fresh install
 * (whose `schema.create` skips the migration seed) and any [KeyCommand] added before its own
 * migration lands — the same contract [net.devemperor.dictate.companion.data.memory.InMemoryChordMapping]
 * honours. The `vk`/modifier CHECKs plus the [KeyChord] factory keep a bogus mapping from ever being
 * stored or read.
 */
class SqlDelightChordMappingRepository(database: DictateCompanionDb) : ChordMappingRepository {

    private val queries = database.companionQueries

    override fun chordFor(command: KeyCommand): KeyChord =
        queries.chordByCommand(command).executeAsOneOrNull()?.toChord() ?: DefaultChords.chordFor(command)

    override fun update(command: KeyCommand, chord: KeyChord) {
        queries.upsertChord(
            command = command,
            ctrl = ChordModifier.CTRL in chord.modifiers,
            shift = ChordModifier.SHIFT in chord.modifiers,
            alt = ChordModifier.ALT in chord.modifiers,
            win = ChordModifier.WIN in chord.modifiers,
            vk = chord.vk.toLong(),
        )
    }

    override fun resetToDefaults() {
        queries.deleteAllChords()
    }

    private fun Key_command_chords.toChord(): KeyChord {
        val modifiers = buildSet {
            if (ctrl) add(ChordModifier.CTRL)
            if (shift) add(ChordModifier.SHIFT)
            if (alt) add(ChordModifier.ALT)
            if (win) add(ChordModifier.WIN)
        }
        return KeyChord(modifiers = modifiers, vk = vk.toInt())
    }
}
