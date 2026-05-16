package net.devemperor.dictate.state.render

import android.content.Context
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for [EditBarController] (B5 CR-EXTRACT — CR4-IMPL-1
 * resolution).
 *
 * # Why Robolectric
 *
 * The class wires real Android `View.setOnClickListener` /
 * `setOnLongClickListener` / `setTag`. Hand-rolled fakes would have to
 * re-implement the View listener/tag contract — Robolectric is the K-4
 * justified exception (identical call to
 * [SpecialTouchHandlerInstallerTest]).
 *
 * # Coverage focus
 *
 *  1. **RR-1 single-owner invariant (load-bearing).** After
 *     `installDormant`, the edit-bar Views carry **no**
 *     `setOnClickListener` (the legacy `MainButtonsController` stays the
 *     sole live owner); the ledger reads `dormant-cr-extract`.
 *  2. **CR4 flip.** `attachToViews` is what actually attaches the
 *     cached listeners; ledger transitions to `attached-cr4`.
 *  3. **Byte-equivalent listener behaviour.** Every ported listener
 *     fires the same callback (parity with
 *     `MainButtonsController.registerEditBarListeners`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditBarControllerTest {

    private lateinit var ctx: Context
    private lateinit var views: EditBarViews
    private lateinit var rec: Recorder

    /** K-1 handwritten callback recorder — no mocking framework. */
    private class Recorder : EditBarController.Callback {
        val events = mutableListOf<String>()
        var editActionIds = mutableListOf<Int>()
        override fun onVibrate() { events += "vibrate" }
        override fun onSmallModeToggled() { events += "smallMode" }
        override fun onSingleRowModeToggled() { events += "singleRow" }
        override fun onSettingsClicked() { events += "settings" }
        override fun onHistoryClicked() { events += "history" }
        override fun onPipelineCancelClicked() { events += "pipelineCancel" }
        override fun onAudioFocusToggled() { events += "audioFocus" }
        override fun onKeyboardToggleClicked() { events += "kbToggle" }
        override fun onKeyboardLongClicked() { events += "kbLong" }
        override fun onEditAction(actionId: Int) {
            events += "editAction"
            editActionIds += actionId
        }
    }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        ctx = ContextThemeWrapper(app, R.style.Theme_Dictate)
        var nextId = 1000
        fun btn(): MaterialButton = MaterialButton(ctx).apply { id = nextId++ }
        views = EditBarViews(
            editNumbersButton = btn(),
            editSettingsButton = btn(),
            editHistoryButton = btn(),
            pipelineCancelButton = btn(),
            editAudioFocusButton = btn(),
            editKeyboardButton = btn(),
            editUndoButton = btn(),
            editRedoButton = btn(),
            editCutButton = btn(),
            editCopyButton = btn(),
            editPasteButton = btn(),
        )
        rec = Recorder()
    }

    private fun newController() = EditBarController(views, rec)

    // ── 1. RR-1 single-owner invariant ────────────────────────────────

    @Test
    fun installDormant_attaches_no_click_listener_to_live_views() {
        val c = newController()
        c.installDormant()

        // Load-bearing: CR-EXTRACT must NOT overwrite the legacy
        // MainButtonsController's live setOnClickListener.
        assertNull(
            "editNumbers must have NO click listener after installDormant (RR-1)",
            shadowOf(views.editNumbersButton).onClickListener,
        )
        assertNull(shadowOf(views.editSettingsButton).onClickListener)
        assertNull(shadowOf(views.editUndoButton).onClickListener)
        assertNull(
            "editKeyboard long-press must not be wired dormant",
            shadowOf(views.editKeyboardButton).onLongClickListener,
        )
    }

    @Test
    fun installDormant_ledger_reads_dormant_for_all_editbar_views() {
        val c = newController()
        c.installDormant()
        for (v in listOf(
            views.editNumbersButton, views.editSettingsButton,
            views.editHistoryButton, views.pipelineCancelButton,
            views.editAudioFocusButton, views.editKeyboardButton,
            views.editUndoButton, views.editRedoButton,
            views.editCutButton, views.editCopyButton, views.editPasteButton,
        )) {
            assertEquals(
                EditBarController.OWNER_DORMANT, c.ownerOf(v.id),
            )
        }
    }

    @Test
    fun ownerOf_is_null_before_installDormant() {
        val c = newController()
        assertNull(c.ownerOf(views.editNumbersButton.id))
    }

    // ── 2. CR4 flip ───────────────────────────────────────────────────

    @Test
    fun attachToViews_is_what_actually_attaches_and_transitions_ledger() {
        val c = newController()
        c.installDormant()
        val cachedClick = c.cachedEditNumbersClick

        c.attachToViews()

        assertSame(
            "CR4 attach must wire the SAME cached listener instance",
            cachedClick, shadowOf(views.editNumbersButton).onClickListener,
        )
        assertEquals(
            EditBarController.OWNER_ATTACHED_CR4,
            c.ownerOf(views.editNumbersButton.id),
        )
        assertEquals(
            EditBarController.OWNER_ATTACHED_CR4,
            c.ownerOf(views.editPasteButton.id),
        )
    }

    @Test
    fun attachToViews_before_installDormant_is_a_safe_noop() {
        val c = newController()
        c.attachToViews()  // no cached listeners yet
        assertNull(shadowOf(views.editNumbersButton).onClickListener)
    }

    // ── 3. Byte-equivalent listener behaviour (parity) ────────────────

    @Test
    fun editNumbers_click_vibrates_and_toggles_small_mode() {
        val c = newController()
        c.installDormant()
        c.attachToViews()

        views.editNumbersButton.performClick()

        assertEquals(listOf("vibrate", "smallMode"), rec.events)
    }

    @Test
    fun editNumbers_longpress_toggles_single_row_and_consumes_event() {
        val c = newController()
        c.installDormant()
        c.attachToViews()

        val consumed = views.editNumbersButton.performLongClick()

        assertTrue("long-press must return true (suppress the click)", consumed)
        assertEquals(listOf("vibrate", "singleRow"), rec.events)
    }

    @Test
    fun settings_history_pipelineCancel_forward_callbacks_without_vibrate() {
        val c = newController()
        c.installDormant()
        c.attachToViews()

        views.editSettingsButton.performClick()
        views.editHistoryButton.performClick()
        views.pipelineCancelButton.performClick()

        // Legacy parity: these three do NOT vibrate (MainButtonsController.kt:132-134).
        assertEquals(listOf("settings", "history", "pipelineCancel"), rec.events)
    }

    @Test
    fun edit_actions_forward_the_correct_android_action_ids() {
        val c = newController()
        c.installDormant()
        c.attachToViews()

        views.editUndoButton.performClick()
        views.editRedoButton.performClick()
        views.editCutButton.performClick()
        views.editCopyButton.performClick()
        views.editPasteButton.performClick()

        assertEquals(
            listOf(
                android.R.id.undo, android.R.id.redo, android.R.id.cut,
                android.R.id.copy, android.R.id.paste,
            ),
            rec.editActionIds,
        )
    }

    @Test
    fun audioFocus_click_uses_the_shared_listener_semantics() {
        val c = newController()
        c.installDormant()
        c.attachToViews()

        views.editAudioFocusButton.performClick()

        assertEquals(listOf("vibrate", "audioFocus"), rec.events)
    }

    @Test
    fun editKeyboard_click_and_longpress_parity() {
        val c = newController()
        c.installDormant()
        c.attachToViews()

        views.editKeyboardButton.performClick()
        rec.events.clear()
        val consumed = views.editKeyboardButton.performLongClick()

        assertTrue(consumed)
        assertEquals(listOf("vibrate", "kbLong"), rec.events)
    }

    // ── 4. Double-build guard (no live overwrite) ─────────────────────

    @Test
    fun second_installDormant_still_attaches_no_live_listener() {
        val c = newController()
        c.installDormant()
        c.installDormant()  // double-build → Log.wtf, but no overwrite
        assertNull(shadowOf(views.editNumbersButton).onClickListener)
        assertEquals(
            EditBarController.OWNER_DORMANT,
            c.ownerOf(views.editNumbersButton.id),
        )
    }
}
