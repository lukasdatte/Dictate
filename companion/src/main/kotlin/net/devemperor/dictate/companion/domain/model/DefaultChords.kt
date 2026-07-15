package net.devemperor.dictate.companion.domain.model

/**
 * The default command → [KeyChord] map — the single place VK literals live (§5.4, SSoT).
 *
 * Used in exactly three roles, and nowhere else may hardcode a chord:
 *  1. the first-run **seed** of the `key_command_chords` table (the migration inserts these),
 *  2. the repository **fallback** for a missing row (defensive — e.g. a future [KeyCommand] added
 *     before its migration lands),
 *  3. the settings UI **"reset to defaults"** button.
 *
 * Windows conventions: clipboard/undo on Ctrl+letter (zeichenbasiert, so DE and EN layouts agree —
 * Ctrl+Z is Undo on both, §8), Redo = Ctrl+**Y** (D2), word selection = Ctrl+Shift+←/→ (D1). The
 * single keys (Backspace/Enter/Space/arrows) are modifier-less chords.
 */
object DefaultChords {

    // Virtual-key codes — kept here and ONLY here (the SSoT rule of §5.4).
    private const val VK_BACK = 0x08
    private const val VK_RETURN = 0x0D
    private const val VK_SPACE = 0x20
    private const val VK_LEFT = 0x25
    private const val VK_RIGHT = 0x27
    private const val VK_A = 0x41
    private const val VK_C = 0x43
    private const val VK_V = 0x56
    private const val VK_X = 0x58
    private const val VK_Y = 0x59
    private const val VK_Z = 0x5A

    private val map: Map<KeyCommand, KeyChord> = mapOf(
        KeyCommand.BACKSPACE to KeyChord(emptySet(), VK_BACK),
        KeyCommand.ENTER to KeyChord(emptySet(), VK_RETURN),
        KeyCommand.SPACE to KeyChord(emptySet(), VK_SPACE),
        KeyCommand.CURSOR_LEFT to KeyChord(emptySet(), VK_LEFT),
        KeyCommand.CURSOR_RIGHT to KeyChord(emptySet(), VK_RIGHT),
        KeyCommand.CURSOR_WORD_SELECT_BACK to KeyChord(setOf(ChordModifier.CTRL, ChordModifier.SHIFT), VK_LEFT),
        KeyCommand.CURSOR_WORD_SELECT_FORWARD to KeyChord(setOf(ChordModifier.CTRL, ChordModifier.SHIFT), VK_RIGHT),
        KeyCommand.SELECT_ALL to KeyChord(setOf(ChordModifier.CTRL), VK_A),
        KeyCommand.CUT to KeyChord(setOf(ChordModifier.CTRL), VK_X),
        KeyCommand.COPY to KeyChord(setOf(ChordModifier.CTRL), VK_C),
        KeyCommand.PASTE to KeyChord(setOf(ChordModifier.CTRL), VK_V),
        KeyCommand.UNDO to KeyChord(setOf(ChordModifier.CTRL), VK_Z),
        KeyCommand.REDO to KeyChord(setOf(ChordModifier.CTRL), VK_Y),
    )

    /** Total by construction — every [KeyCommand] has a default (a missing one would fail this). */
    fun chordFor(command: KeyCommand): KeyChord =
        map[command] ?: error("no default chord for $command — DefaultChords must cover every KeyCommand")

    /** All defaults, for the seed and the reset. */
    fun all(): Map<KeyCommand, KeyChord> = map
}
