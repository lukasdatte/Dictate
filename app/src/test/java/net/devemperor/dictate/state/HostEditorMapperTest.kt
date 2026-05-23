package net.devemperor.dictate.state

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [hostEditorStateFrom] — the Android-`EditorInfo` →
 * framework-free [HostEditorState] mapper.
 *
 * Robolectric is required because `EditorInfo` and `InputType` live in
 * `android.view.inputmethod` / `android.text` and the mapper consumes
 * their bit-field semantics directly. Pure-JVM stubs would re-implement
 * the masks and add nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HostEditorMapperTest {

    private fun infoWith(
        imeOptions: Int = 0,
        inputType: Int = 0,
        actionId: Int = 0,
        actionLabel: CharSequence? = null,
    ) = EditorInfo().apply {
        this.imeOptions = imeOptions
        this.inputType = inputType
        this.actionId = actionId
        this.actionLabel = actionLabel
    }

    @Test
    fun `null EditorInfo yields hasEditorInfo = false`() {
        val s = hostEditorStateFrom(null)
        assertEquals(HostEditorState(), s)
        assertFalse(s.hasEditorInfo)
    }

    @Test
    fun `IME_ACTION_SEND is extracted from imeOptions`() {
        val s = hostEditorStateFrom(infoWith(imeOptions = EditorInfo.IME_ACTION_SEND))
        assertEquals(EditorInfo.IME_ACTION_SEND, s.imeActionId)
        assertTrue(s.hasEditorInfo)
        assertFalse(s.hasNoEnterAction)
        assertFalse(s.isMultiLine)
    }

    @Test
    fun `IME_ACTION_GO mixed with unrelated imeOption flags still extracts cleanly`() {
        // Add IME_FLAG_NO_PERSONALIZED_LEARNING (non-action flag) to
        // verify the IME_MASK_ACTION isolation works.
        val flags = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        val s = hostEditorStateFrom(infoWith(imeOptions = flags))
        assertEquals(EditorInfo.IME_ACTION_GO, s.imeActionId)
        assertFalse(s.hasNoEnterAction)
    }

    @Test
    fun `IME_FLAG_NO_ENTER_ACTION is detected alongside an imeAction`() {
        val flags = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        val s = hostEditorStateFrom(infoWith(imeOptions = flags))
        assertEquals(EditorInfo.IME_ACTION_SEND, s.imeActionId)
        assertTrue(s.hasNoEnterAction)
    }

    @Test
    fun `TYPE_TEXT_FLAG_MULTI_LINE sets isMultiLine`() {
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        val s = hostEditorStateFrom(infoWith(inputType = type))
        assertTrue(s.isMultiLine)
    }

    @Test
    fun `TYPE_TEXT_FLAG_IME_MULTI_LINE sets isMultiLine`() {
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE
        val s = hostEditorStateFrom(infoWith(inputType = type))
        assertTrue(s.isMultiLine)
    }

    @Test
    fun `inputType without multi-line flags leaves isMultiLine = false`() {
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val s = hostEditorStateFrom(infoWith(inputType = type))
        assertFalse(s.isMultiLine)
    }

    @Test
    fun `custom actionId and actionLabel pass through verbatim`() {
        val s = hostEditorStateFrom(
            infoWith(actionId = 42, actionLabel = "Antworten"),
        )
        assertEquals(42, s.customActionId)
        assertNotNull(s.customActionLabel)
        assertEquals("Antworten", s.customActionLabel.toString())
    }

    @Test
    fun `fully populated EditorInfo round-trips every field`() {
        val info = infoWith(
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            actionId = 7,
            actionLabel = "Senden",
        )
        val s = hostEditorStateFrom(info)
        assertTrue(s.hasEditorInfo)
        assertEquals(EditorInfo.IME_ACTION_SEND, s.imeActionId)
        assertTrue(s.hasNoEnterAction)
        assertTrue(s.isMultiLine)
        assertEquals(7, s.customActionId)
        assertEquals("Senden", s.customActionLabel?.toString())
    }
}
