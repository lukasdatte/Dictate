package net.devemperor.dictate.keyboard

import net.devemperor.dictate.R

/**
 * Provides key definitions for each keyboard layout.
 * Pure data class without UI logic.
 *
 * Each layout is a List<List<QwertzKeyDef>> (rows of keys).
 */
object QwertzLayoutProvider {

    /**
     * Returns the key layout for the given keyboard mode and shift state.
     */
    fun getLayout(layout: QwertzKeyboardLayout, shiftActive: Boolean): List<List<QwertzKeyDef>> {
        return when (layout) {
            QwertzKeyboardLayout.QWERTZ -> getQwertzLayout(shiftActive)
            QwertzKeyboardLayout.NUMBERS -> getNumbersLayout()
            QwertzKeyboardLayout.SYMBOLS -> getSymbolsLayout()
        }
    }

    private fun getQwertzLayout(shiftActive: Boolean): List<List<QwertzKeyDef>> {
        fun letter(label: String): QwertzKeyDef {
            val display = if (shiftActive) label.uppercase() else label
            return QwertzKeyDef(
                label = display,
                output = label,
                shiftOutput = label.uppercase()
            )
        }

        // Row 0: Number row
        val row0 = listOf(
            QwertzKeyDef(label = "1", output = "1"),
            QwertzKeyDef(label = "2", output = "2"),
            QwertzKeyDef(label = "3", output = "3"),
            QwertzKeyDef(label = "4", output = "4"),
            QwertzKeyDef(label = "5", output = "5"),
            QwertzKeyDef(label = "6", output = "6"),
            QwertzKeyDef(label = "7", output = "7"),
            QwertzKeyDef(label = "8", output = "8"),
            QwertzKeyDef(label = "9", output = "9"),
            QwertzKeyDef(label = "0", output = "0"),
            QwertzKeyDef(
                label = "\u00DF",
                output = "\u00DF",
                shiftOutput = "\u00DF"
            )
        )

        // Row 1: q w e r t z u i o p ue
        val row1 = listOf(
            letter("q"), letter("w"), letter("e"), letter("r"), letter("t"),
            letter("z"), letter("u"), letter("i"), letter("o"), letter("p"),
            QwertzKeyDef(
                label = if (shiftActive) "\u00DC" else "\u00FC",
                output = "\u00FC",
                shiftOutput = "\u00DC"
            )
        )

        // Row 2: a s d f g h j k l oe ae
        val row2 = listOf(
            letter("a"), letter("s"), letter("d"), letter("f"), letter("g"),
            letter("h"), letter("j"), letter("k"), letter("l"),
            QwertzKeyDef(
                label = if (shiftActive) "\u00D6" else "\u00F6",
                output = "\u00F6",
                shiftOutput = "\u00D6"
            ),
            QwertzKeyDef(
                label = if (shiftActive) "\u00C4" else "\u00E4",
                output = "\u00E4",
                shiftOutput = "\u00C4"
            )
        )

        // Row 3: [Shift] y x c v b n m [Backspace]
        val row3 = listOf(
            QwertzKeyDef(
                label = "",
                keyAction = KeyAction.SHIFT,
                widthWeight = 1.5f,
                iconResId = R.drawable.ic_keyboard_shift_24,
                colorTier = ColorTier.DARK
            ),
            letter("y"), letter("x"), letter("c"), letter("v"),
            letter("b"), letter("n"), letter("m"),
            QwertzKeyDef(
                label = "",
                keyAction = KeyAction.BACKSPACE,
                widthWeight = 1.5f,
                iconResId = R.drawable.ic_baseline_keyboard_backspace_24,
                repeatable = true,
                colorTier = ColorTier.DARK
            )
        )

        // Row 4: [↓] [123] [Ctrl] [Tab] [Space] [.] [Enter]
        val row4 = listOf(
            closeKeyDef(),
            QwertzKeyDef(
                label = "123",
                keyAction = KeyAction.SWITCH_LAYOUT,
                widthWeight = 1.2f,
                colorTier = ColorTier.DARK
            ),
            QwertzKeyDef(
                label = "Ctrl",
                keyAction = KeyAction.CTRL_MODIFIER,
                widthWeight = 1f,
                colorTier = ColorTier.DARK
            ),
            QwertzKeyDef(
                label = "",
                keyAction = KeyAction.TAB,
                widthWeight = 1f,
                iconResId = R.drawable.ic_keyboard_tab_24,
                colorTier = ColorTier.DARK
            ),
            QwertzKeyDef(
                label = "",
                output = " ",
                keyAction = KeyAction.SPACE,
                widthWeight = 4f,
                colorTier = ColorTier.MEDIUM
            ),
            QwertzKeyDef(
                label = ".",
                output = ".",
                widthWeight = 1f
            ),
            QwertzKeyDef(
                label = "",
                keyAction = KeyAction.ENTER,
                widthWeight = 1.2f,
                iconResId = R.drawable.ic_baseline_subdirectory_arrow_left_24,
                colorTier = ColorTier.ACCENT
            )
        )

        return listOf(row0, row1, row2, row3, row4)
    }

    private fun getNumbersLayout(): List<List<QwertzKeyDef>> {
        // Row 0: 1 2 3 4 5 6 7 8 9 0
        val row0 = listOf(
            QwertzKeyDef(label = "1", output = "1"),
            QwertzKeyDef(label = "2", output = "2"),
            QwertzKeyDef(label = "3", output = "3"),
            QwertzKeyDef(label = "4", output = "4"),
            QwertzKeyDef(label = "5", output = "5"),
            QwertzKeyDef(label = "6", output = "6"),
            QwertzKeyDef(label = "7", output = "7"),
            QwertzKeyDef(label = "8", output = "8"),
            QwertzKeyDef(label = "9", output = "9"),
            QwertzKeyDef(label = "0", output = "0")
        )

        // Row 1: @ # € & _ - + ( ) /
        val row1 = listOf(
            QwertzKeyDef(label = "@", output = "@"),
            QwertzKeyDef(label = "#", output = "#"),
            QwertzKeyDef(label = "\u20AC", output = "\u20AC"),
            QwertzKeyDef(label = "&", output = "&"),
            QwertzKeyDef(label = "_", output = "_"),
            QwertzKeyDef(label = "-", output = "-"),
            QwertzKeyDef(label = "+", output = "+"),
            QwertzKeyDef(label = "(", output = "("),
            QwertzKeyDef(label = ")", output = ")"),
            QwertzKeyDef(label = "/", output = "/")
        )

        // Row 2: [=<] * " ' : ; ! ? [Backspace]
        val row2 = listOf(
            QwertzKeyDef(
                label = "=\\<",
                keyAction = KeyAction.SWITCH_LAYOUT,
                widthWeight = 1.5f,
                colorTier = ColorTier.DARK
            ),
            QwertzKeyDef(label = "*", output = "*"),
            QwertzKeyDef(label = "\"", output = "\""),
            QwertzKeyDef(label = "'", output = "'"),
            QwertzKeyDef(label = ":", output = ":"),
            QwertzKeyDef(label = ";", output = ";"),
            QwertzKeyDef(label = "!", output = "!"),
            QwertzKeyDef(label = "?", output = "?"),
            QwertzKeyDef(
                label = "",
                keyAction = KeyAction.BACKSPACE,
                widthWeight = 1.5f,
                iconResId = R.drawable.ic_baseline_keyboard_backspace_24,
                repeatable = true,
                colorTier = ColorTier.DARK
            )
        )

        // Row 3: [↓] [ABC] [,] [Space] [.] [Enter]
        val row3 = listOf(
            closeKeyDef(),
            QwertzKeyDef(
                label = "ABC",
                keyAction = KeyAction.SWITCH_LAYOUT,
                widthWeight = 1.2f,
                colorTier = ColorTier.DARK
            ),
            QwertzKeyDef(
                label = ",",
                output = ",",
                widthWeight = 1f
            ),
            QwertzKeyDef(
                label = "",
                output = " ",
                keyAction = KeyAction.SPACE,
                widthWeight = 4f,
                colorTier = ColorTier.MEDIUM
            ),
            QwertzKeyDef(
                label = ".",
                output = ".",
                widthWeight = 1f
            ),
            QwertzKeyDef(
                label = "",
                keyAction = KeyAction.ENTER,
                widthWeight = 1.2f,
                iconResId = R.drawable.ic_baseline_subdirectory_arrow_left_24,
                colorTier = ColorTier.ACCENT
            )
        )

        return listOf(row0, row1, row2, row3)
    }

    private fun getSymbolsLayout(): List<List<QwertzKeyDef>> {
        // Row 0: ~ ` | ^ % $ £ ¥ ¢ °
        val row0 = listOf(
            QwertzKeyDef(label = "~", output = "~"),
            QwertzKeyDef(label = "`", output = "`"),
            QwertzKeyDef(label = "|", output = "|"),
            QwertzKeyDef(label = "^", output = "^"),
            QwertzKeyDef(label = "%", output = "%"),
            QwertzKeyDef(label = "$", output = "$"),
            QwertzKeyDef(label = "\u00A3", output = "\u00A3"),
            QwertzKeyDef(label = "\u00A5", output = "\u00A5"),
            QwertzKeyDef(label = "\u00A2", output = "\u00A2"),
            QwertzKeyDef(label = "\u00B0", output = "\u00B0")
        )

        // Row 1: { } [ ] < > = \ §
        val row1 = listOf(
            QwertzKeyDef(label = "{", output = "{"),
            QwertzKeyDef(label = "}", output = "}"),
            QwertzKeyDef(label = "[", output = "["),
            QwertzKeyDef(label = "]", output = "]"),
            QwertzKeyDef(label = "<", output = "<"),
            QwertzKeyDef(label = ">", output = ">"),
            QwertzKeyDef(label = "=", output = "="),
            QwertzKeyDef(label = "\\", output = "\\"),
            QwertzKeyDef(label = "\u00A7", output = "\u00A7")
        )

        // Row 2: [123] © ® ™ ¿ ¡ … [Backspace]
        val row2 = listOf(
            QwertzKeyDef(
                label = "123",
                keyAction = KeyAction.SWITCH_LAYOUT,
                widthWeight = 1.5f,
                colorTier = ColorTier.DARK
            ),
            QwertzKeyDef(label = "\u00A9", output = "\u00A9"),
            QwertzKeyDef(label = "\u00AE", output = "\u00AE"),
            QwertzKeyDef(label = "\u2122", output = "\u2122"),
            QwertzKeyDef(label = "\u00BF", output = "\u00BF"),
            QwertzKeyDef(label = "\u00A1", output = "\u00A1"),
            QwertzKeyDef(label = "\u2026", output = "\u2026"),
            QwertzKeyDef(
                label = "",
                keyAction = KeyAction.BACKSPACE,
                widthWeight = 1.5f,
                iconResId = R.drawable.ic_baseline_keyboard_backspace_24,
                repeatable = true,
                colorTier = ColorTier.DARK
            )
        )

        // Row 3: [↓] [ABC] [,] [Space] [.] [Enter]
        val row3 = listOf(
            closeKeyDef(),
            QwertzKeyDef(
                label = "ABC",
                keyAction = KeyAction.SWITCH_LAYOUT,
                widthWeight = 1.2f,
                colorTier = ColorTier.DARK
            ),
            QwertzKeyDef(
                label = ",",
                output = ",",
                widthWeight = 1f
            ),
            QwertzKeyDef(
                label = "",
                output = " ",
                keyAction = KeyAction.SPACE,
                widthWeight = 4f,
                colorTier = ColorTier.MEDIUM
            ),
            QwertzKeyDef(
                label = ".",
                output = ".",
                widthWeight = 1f
            ),
            QwertzKeyDef(
                label = "",
                keyAction = KeyAction.ENTER,
                widthWeight = 1.2f,
                iconResId = R.drawable.ic_baseline_subdirectory_arrow_left_24,
                colorTier = ColorTier.ACCENT
            )
        )

        return listOf(row0, row1, row2, row3)
    }

    /** Shared close-keyboard key definition used in all layouts' bottom row. */
    private fun closeKeyDef() = QwertzKeyDef(
        label = "",
        keyAction = KeyAction.CLOSE_KEYBOARD,
        widthWeight = 1f,
        iconResId = R.drawable.ic_baseline_keyboard_hide_24,
        colorTier = ColorTier.DARK
    )
}
