package net.devemperor.dictate.state.render

import android.content.Context
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import androidx.appcompat.view.ContextThemeWrapper
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for [EmojiController] (B5 CR-EXTRACT — CR4-IMPL-1
 * resolution; sibling of [EditBarControllerTest]).
 *
 * Robolectric K-4 justified (real `setOnClickListener` / `setTag`);
 * K-1 handwritten fakes (no mocking framework).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmojiControllerTest {

    private lateinit var ctx: Context
    private lateinit var views: EmojiViews
    private lateinit var rec: Recorder
    private lateinit var ic: FakeInputConnection

    private class Recorder : EmojiController.Callback {
        val events = mutableListOf<String>()
        override fun onVibrate() { events += "vibrate" }
        override fun onEmojiToggleClicked() { events += "toggle" }
        override fun onEmojiCloseClicked() { events += "close" }
    }

    /**
     * K-1 handwritten InputConnection — extends the concrete
     * [BaseInputConnection] (a true editorless connection) and only
     * captures `commitText`. No mocking framework.
     */
    private class FakeInputConnection(view: View) :
        BaseInputConnection(view, false) {
        val committed = mutableListOf<String>()
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            committed += text.toString()
            return true
        }
    }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        ctx = ContextThemeWrapper(app, R.style.Theme_Dictate)
        views = EmojiViews(
            editEmojiButton = MaterialButton(ctx).apply { id = 2001 },
            emojiPickerCloseButton = MaterialButton(ctx).apply { id = 2002 },
            emojiPickerView = EmojiPickerView(ctx).apply { id = 2003 },
        )
        rec = Recorder()
        ic = FakeInputConnection(views.editEmojiButton)
    }

    private fun newController(connection: InputConnection? = ic) =
        EmojiController(views, rec) { connection }

    // ── 1. RR-1 single-owner invariant ────────────────────────────────

    @Test
    fun installDormant_attaches_no_click_listener_to_live_views() {
        val c = newController()
        c.installDormant()
        assertNull(shadowOf(views.editEmojiButton).onClickListener)
        assertNull(shadowOf(views.emojiPickerCloseButton).onClickListener)
    }

    @Test
    fun installDormant_ledger_reads_dormant() {
        val c = newController()
        c.installDormant()
        assertEquals(EmojiController.OWNER_DORMANT, c.ownerOf(views.editEmojiButton.id))
        assertEquals(EmojiController.OWNER_DORMANT, c.ownerOf(views.emojiPickerCloseButton.id))
        assertEquals(EmojiController.OWNER_DORMANT, c.ownerOf(views.emojiPickerView.id))
    }

    @Test
    fun ownerOf_is_null_before_installDormant() {
        assertNull(newController().ownerOf(views.editEmojiButton.id))
    }

    // ── 2. CR4 flip ───────────────────────────────────────────────────

    @Test
    fun attachToViews_attaches_cached_listener_and_transitions_ledger() {
        val c = newController()
        c.installDormant()
        val cached = c.cachedEditEmojiClick

        c.attachToViews()

        assertSame(cached, shadowOf(views.editEmojiButton).onClickListener)
        assertEquals(EmojiController.OWNER_ATTACHED_CR4, c.ownerOf(views.editEmojiButton.id))
        assertEquals(EmojiController.OWNER_ATTACHED_CR4, c.ownerOf(views.emojiPickerView.id))
    }

    @Test
    fun attachToViews_before_installDormant_is_a_safe_noop() {
        val c = newController()
        c.attachToViews()
        assertNull(shadowOf(views.editEmojiButton).onClickListener)
    }

    // ── 3. Byte-equivalent listener behaviour (parity) ────────────────

    @Test
    fun editEmoji_click_vibrates_and_toggles() {
        val c = newController()
        c.installDormant()
        c.attachToViews()
        views.editEmojiButton.performClick()
        assertEquals(listOf("vibrate", "toggle"), rec.events)
    }

    @Test
    fun emojiClose_click_vibrates_and_closes() {
        val c = newController()
        c.installDormant()
        c.attachToViews()
        views.emojiPickerCloseButton.performClick()
        assertEquals(listOf("vibrate", "close"), rec.events)
    }

    @Test
    fun emoji_picked_vibrates_and_commits_the_glyph() {
        val c = newController()
        c.installDormant()

        c.invokeEmojiPicked("😀")  // 😀

        assertEquals(listOf("vibrate"), rec.events)
        assertEquals(listOf("😀"), ic.committed)
    }

    @Test
    fun emoji_picked_null_vibrates_but_commits_nothing() {
        val c = newController()
        c.installDormant()

        c.invokeEmojiPicked(null)

        assertEquals(listOf("vibrate"), rec.events)
        assertEquals(emptyList<String>(), ic.committed)
    }

    @Test
    fun emoji_picked_with_no_inputconnection_does_not_crash() {
        val c = newController(connection = null)
        c.installDormant()

        c.invokeEmojiPicked("x")  // null-IC short-circuit

        assertEquals(listOf("vibrate"), rec.events)
        assertEquals(emptyList<String>(), ic.committed)
    }

    // ── 4. Double-build guard ─────────────────────────────────────────

    @Test
    fun second_installDormant_still_attaches_no_live_listener() {
        val c = newController()
        c.installDormant()
        c.installDormant()
        assertNull(shadowOf(views.editEmojiButton).onClickListener)
    }

    // ── CR-DEL — emoji-row applyTheme (the CR-RGATE-flagged residual) ──

    @Test
    fun applyTheme_paints_legacy_emoji_tiers_byte_equivalent() {
        val captured = HashMap<Int, Int>()
        fun cap(id: Int): MaterialButton = object : MaterialButton(ctx) {
            override fun setBackgroundColor(color: Int) {
                captured[id] = color
                super.setBackgroundColor(color)
            }
        }.apply { this.id = id }

        val v = EmojiViews(
            editEmojiButton = cap(3001),
            emojiPickerCloseButton = cap(3002),
            emojiPickerView = EmojiPickerView(ctx).apply { id = 3003 },
        )
        val accent = 0xFF3366CC.toInt()
        val medium = net.devemperor.dictate.DictateUtils.darkenColor(accent, 0.18f)

        EmojiController(v, rec) { ic }.applyTheme(accent)

        // Legacy MainButtonsController.applyTheme (:421/:424):
        // editEmojiButton = accentMedium; emojiPickerCloseButton = accent.
        assertEquals(medium, captured[3001])
        assertEquals(accent, captured[3002])
    }
}
