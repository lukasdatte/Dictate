package net.devemperor.dictate.companion.domain.model

/**
 * A configurable keyboard action the companion can perform — the domain vocabulary of `/v1/input`
 * (D6, §5.4).
 *
 * A **separate enum** from the wire's `InputCommandKindWire` on purpose (as `InsertionOutcome` is
 * separate from `InsertionOutcomeWire`): `TYPE_TEXT` is deliberately absent — typing a text is not a
 * chord and is never user-configurable (it goes through the `TextInserter`'s Ctrl+V, ADR-0018).
 * Everything here maps command → [KeyChord] through the [net.devemperor.dictate.companion.domain.port.ChordMappingRepository].
 */
enum class KeyCommand {
    BACKSPACE, ENTER, SPACE,
    CURSOR_LEFT, CURSOR_RIGHT,
    CURSOR_WORD_SELECT_BACK, CURSOR_WORD_SELECT_FORWARD,
    SELECT_ALL, CUT, COPY, PASTE, UNDO, REDO,
}

/** A chord modifier held down while the main key is pressed. `vk` is its Win32 virtual-key code. */
enum class ChordModifier(val vk: Int) {
    CTRL(0x11),
    SHIFT(0x10),
    ALT(0x12),
    WIN(0x5B),
}

/**
 * One resolved key combination: a set of held [modifiers] plus a main-key virtual-key code [vk].
 *
 * The *only* representation of "which keys REDO presses" in the companion — resolved once, in
 * `Win32InputPerformer`, from the [net.devemperor.dictate.companion.domain.port.ChordMappingRepository]
 * (SSoT, §5.3). The wire never carries VK codes; this is where semantics become keystrokes.
 */
data class KeyChord(
    val modifiers: Set<ChordModifier>,
    val vk: Int,
) {
    init {
        // The DB CHECK pins the same range (Double-Enum, §5.4); the factory pins it in memory so a
        // hand-built or reset chord can never carry a bogus VK past construction.
        require(vk in MIN_VK..MAX_VK) { "vk must be in $MIN_VK..$MAX_VK, was $vk" }
    }

    companion object {
        const val MIN_VK = 1
        const val MAX_VK = 254
    }
}
