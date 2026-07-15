package net.devemperor.dictate.companion.ui.settings

import net.devemperor.dictate.companion.domain.model.ChordModifier
import net.devemperor.dictate.companion.domain.model.KeyChord
import net.devemperor.dictate.companion.domain.model.KeyCommand

/**
 * Human-readable labels for chords and commands — presentation only, pure and testable.
 *
 * Kept out of the Compose layer so a JVM test can pin "Ctrl+Shift+←" without a UI runtime.
 */
object ChordLabels {

    /** e.g. `{CTRL, SHIFT} + VK_LEFT` → "Ctrl + Shift + ←". Unknown VKs render as hex. */
    fun describe(chord: KeyChord): String {
        // Modifier order = declaration order, so the label is stable and matches the injected sequence.
        val parts = ChordModifier.entries.filter { it in chord.modifiers }.map { it.label() } + keyName(chord.vk)
        return parts.joinToString(" + ")
    }

    fun describe(command: KeyCommand): String = command.name
        .split('_')
        .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

    private fun ChordModifier.label(): String = when (this) {
        ChordModifier.CTRL -> "Ctrl"
        ChordModifier.SHIFT -> "Shift"
        ChordModifier.ALT -> "Alt"
        ChordModifier.WIN -> "Win"
    }

    private fun keyName(vk: Int): String = KEY_NAMES[vk] ?: "0x%02X".format(vk)

    private val KEY_NAMES: Map<Int, String> = buildMap {
        put(0x08, "Backspace")
        put(0x0D, "Enter")
        put(0x20, "Space")
        put(0x25, "←")
        put(0x27, "→")
        // Letters A–Z map 1:1 to their ASCII/VK code.
        for (c in 'A'..'Z') put(c.code, c.toString())
    }
}
