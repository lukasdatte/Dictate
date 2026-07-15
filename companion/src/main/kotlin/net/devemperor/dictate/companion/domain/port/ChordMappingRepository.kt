package net.devemperor.dictate.companion.domain.port

import net.devemperor.dictate.companion.domain.model.KeyChord
import net.devemperor.dictate.companion.domain.model.KeyCommand

/**
 * The command → [KeyChord] mapping — user-configurable, persisted typed in the companion DB (D6).
 *
 * `Win32InputPerformer` is the **only** caller of [chordFor] (the single resolution point, §5.3);
 * the settings UI is the only caller of [update] / [resetToDefaults]. Implementations must return a
 * `DefaultChords` chord for a command with no stored row, so a missing row can never leave a command
 * unresolvable.
 */
interface ChordMappingRepository {

    fun chordFor(command: KeyCommand): KeyChord

    fun update(command: KeyCommand, chord: KeyChord)

    fun resetToDefaults()
}
