package net.devemperor.dictate.testutil

import android.view.inputmethod.InputConnection
import net.devemperor.dictate.state.insertion.HostSelection
import net.devemperor.dictate.state.insertion.HostTextReader

/**
 * Configurable [HostTextReader] fake shared by the insertion unit tests
 * (hand-rolled — no Mockito, see project CLAUDE.md).
 *
 * Defaults model the "nothing readable" host: [HostSelection.NONE] and empty
 * text before the cursor, which makes `InsertionService.control()` degrade to
 * the legacy raw primitives. Tests set [selection] / [beforeCursor] to drive
 * the selection-aware / grapheme-aware branches (F-018 / F-021).
 */
class FakeHostTextReader(
    var selection: HostSelection = HostSelection.NONE,
    var beforeCursor: String = "",
) : HostTextReader {
    /** maxChars values the service requested — lets tests assert the lookback. */
    val requestedLookbacks = mutableListOf<Int>()

    override fun selection(ic: InputConnection): HostSelection = selection

    override fun textBeforeCursor(ic: InputConnection, maxChars: Int): String {
        requestedLookbacks += maxChars
        return beforeCursor.takeLast(maxChars)
    }
}
