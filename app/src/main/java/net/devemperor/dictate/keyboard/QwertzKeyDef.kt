package net.devemperor.dictate.keyboard

/**
 * Defines a single key in the keyboard layout.
 *
 * Immutable data class -- layouts are defined as lists of rows (List<List<QwertzKeyDef>>).
 *
 * @param label displayed text ("q", "1", "Shift")
 * @param output commitText value (null = functional key); must be set explicitly in QwertzLayoutProvider
 * @param keyAction the action to perform when this key is pressed
 * @param widthWeight relative width (1.0 = standard, 1.5 = wide, etc.)
 * @param iconResId drawable resource instead of text (0 = no icon)
 * @param repeatable whether long-press should repeat (Backspace, Delete)
 * @param shiftOutput explicit shift output (null = output.uppercase()); for special cases like eszett
 * @param colorTier color tier for theming
 */
data class QwertzKeyDef(
    val label: String,
    val output: String? = null,
    val keyAction: KeyAction = KeyAction.COMMIT_TEXT,
    val widthWeight: Float = 1f,
    val iconResId: Int = 0,
    val repeatable: Boolean = false,
    val shiftOutput: String? = null,
    val colorTier: ColorTier = ColorTier.MEDIUM
)

/**
 * Actions that a key can trigger.
 */
enum class KeyAction {
    /** inputConnection.commitText(output, 1) */
    COMMIT_TEXT,
    /** deleteOneCharacter() */
    BACKSPACE,
    /** performEnterAction() */
    ENTER,
    /** Toggle shift state (OFF -> SINGLE -> CAPS_LOCK -> OFF) */
    SHIFT,
    /** Switch to NUMBERS/SYMBOLS/QWERTZ layout */
    SWITCH_LAYOUT,
    /** Space with cursor swipe support */
    SPACE,
    /** Tab character */
    TAB,
    /** Toggle Ctrl modifier state */
    CTRL_MODIFIER
}

/**
 * Color tiers for keyboard key theming.
 */
enum class ColorTier {
    /** accentColor (Enter, active Shift) */
    ACCENT,
    /** accentColorMedium (letters, numbers) */
    MEDIUM,
    /** accentColorDark (Backspace, modifiers) */
    DARK
}

/**
 * Shift state machine: OFF -> SINGLE -> CAPS_LOCK -> OFF.
 */
enum class ShiftState {
    /** All letters lowercase */
    OFF,
    /** Next letter uppercase, then back to OFF */
    SINGLE,
    /** All letters uppercase until toggled off */
    CAPS_LOCK;

    /** Advances to the next state in the cycle. */
    fun next(): ShiftState = when (this) {
        OFF -> SINGLE
        SINGLE -> CAPS_LOCK
        CAPS_LOCK -> OFF
    }
}
