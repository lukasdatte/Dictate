package net.devemperor.dictate.companion.data.memory

import net.devemperor.dictate.companion.domain.model.DefaultChords
import net.devemperor.dictate.companion.domain.model.KeyChord
import net.devemperor.dictate.companion.domain.model.KeyCommand
import net.devemperor.dictate.companion.domain.port.ChordMappingRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * A chord mapping with no database behind it.
 *
 * Two roles: the stand-in used while the DB-backed repository does not yet exist (§B, before §B2),
 * and the test fake for the input tests. Falls back to [DefaultChords] for any command not
 * explicitly overridden — the same contract the SQLDelight repository honours for a missing row.
 */
class InMemoryChordMapping : ChordMappingRepository {

    private val overrides = ConcurrentHashMap<KeyCommand, KeyChord>()

    override fun chordFor(command: KeyCommand): KeyChord =
        overrides[command] ?: DefaultChords.chordFor(command)

    override fun update(command: KeyCommand, chord: KeyChord) {
        overrides[command] = chord
    }

    override fun resetToDefaults() {
        overrides.clear()
    }
}
