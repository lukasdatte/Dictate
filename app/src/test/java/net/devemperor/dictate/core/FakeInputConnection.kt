package net.devemperor.dictate.core

import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo

/**
 * Hand-rolled [InputConnection] stub shared across the insertion unit tests
 * (no Mockito — see project CLAUDE.md).
 *
 * Records the most recent commitText() argument so individual tests can verify
 * which IC actually received the write. Subclasses override the few methods
 * they care about (e.g. failing commits, recording all calls). All other
 * interface methods are no-ops returning safe defaults — exercising them in a
 * test means the surface broadened unintentionally and a new test is warranted.
 */
internal open class FakeInputConnection : InputConnection {
    var lastCommittedText: CharSequence? = null
    var commitTextResult: Boolean = true

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        lastCommittedText = text
        return commitTextResult
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = null
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = null
    override fun getSelectedText(flags: Int): CharSequence? = null
    override fun getCursorCapsMode(reqModes: Int): Int = 0
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = false
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = false
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = false
    override fun setComposingRegion(start: Int, end: Int): Boolean = false
    override fun finishComposingText(): Boolean = false
    override fun commitCompletion(text: CompletionInfo?): Boolean = false
    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = false
    override fun setSelection(start: Int, end: Int): Boolean = false
    override fun performEditorAction(editorAction: Int): Boolean = false
    override fun performContextMenuAction(id: Int): Boolean = false
    override fun beginBatchEdit(): Boolean = false
    override fun endBatchEdit(): Boolean = false
    override fun sendKeyEvent(event: KeyEvent?): Boolean = false
    override fun clearMetaKeyStates(states: Int): Boolean = false
    override fun reportFullscreenMode(enabled: Boolean): Boolean = false
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    override fun getHandler(): Handler? = null
    override fun closeConnection() { }
    override fun commitContent(
        inputContentInfo: InputContentInfo,
        flags: Int,
        opts: Bundle?,
    ): Boolean = false
}
