package net.devemperor.dictate.state.render

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.keyboard.KeyPressAnimator
import net.devemperor.dictate.state.insertion.AutoEnterScheduler
import net.devemperor.dictate.state.insertion.ClipboardGateway
import net.devemperor.dictate.state.insertion.ControlOp
import net.devemperor.dictate.state.insertion.EditAction
import net.devemperor.dictate.state.insertion.HostTarget
import net.devemperor.dictate.state.insertion.InsertionAuditLog
import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.state.insertion.InsertionService
import net.devemperor.dictate.state.insertion.KeyboardActionDispatcher
import net.devemperor.dictate.state.insertion.LocalImeSink
import net.devemperor.dictate.state.insertion.RecoveryHandler
import net.devemperor.dictate.state.layout.LogicalButtonId
import net.devemperor.dictate.testutil.FakeHostTextReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for [SpecialTouchHandlerInstaller] (B5 CR2).
 *
 * # Why Robolectric
 *
 * The class wires real Android `View.setOnTouchListener` /
 * `View.setTag` / `findViewById` and the §11.7 handlers mutate real
 * View state. Hand-rolled fakes would have to re-implement the View
 * touch/tag contract — Robolectric is the narrower K-4 justified
 * exception (same call as [ImeViewBackendTest] / `KeyboardUiControllerTest`).
 *
 * # Coverage focus
 *
 *  1. **RR-1 single-owner invariant (the load-bearing CR2 property).**
 *     After `installDormant`, SPACE/BACKSPACE/ENTER carry **no**
 *     `setOnTouchListener` (the legacy `MainButtonsController` stays the
 *     sole live owner) — proven via `ShadowView.getOnTouchListener()`.
 *     The owner ledger reads `dormant-cr2` (built, NOT attached).
 *  2. **The three §11.7 handlers are built** (CursorSwipe / Backspace /
 *     EnterOverlay) — non-null, distinct, cached.
 *  3. **CR4 flip** — `attachToViews` is what actually attaches; ledger
 *     transitions `dormant-cr2` → `attached-cr4`; the live listener is
 *     the cached handler instance.
 *  4. **Double-build guard** — a second `installDormant` without a
 *     detach still does not attach a live listener (no overwrite in
 *     CR2).
 *  5. **§11.7 SPACE behaviour wiring** — onTap commits a space via the
 *     InputConnection (verbatim §11.7 body).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpecialTouchHandlerInstallerTest {

    private lateinit var ctx: Context
    private lateinit var space: MaterialButton
    private lateinit var backspace: MaterialButton
    private lateinit var enter: MaterialButton
    private lateinit var buttons: Map<LogicalButtonId, View>
    private lateinit var ic: FakeInputConnection
    private val vibrations = mutableListOf<Unit>()
    private val deleteCancels = mutableListOf<Unit>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        ctx = ContextThemeWrapper(app, R.style.Theme_Dictate)

        // ENTER's rootView must carry an `overlay_characters_ll`
        // LinearLayout so buildEnterOverlayHandler can resolve it (the
        // legacy controller is passed the same view). Parent all three
        // buttons under one root so `enter.rootView` finds it.
        val root = LinearLayout(ctx)
        val overlay = LinearLayout(ctx).apply { id = R.id.overlay_characters_ll }
        space = MaterialButton(ctx)
        backspace = MaterialButton(ctx)
        enter = MaterialButton(ctx)
        root.addView(space)
        root.addView(backspace)
        root.addView(enter)
        root.addView(overlay)

        buttons = mapOf(
            LogicalButtonId.SPACE to space,
            LogicalButtonId.BACKSPACE to backspace,
            LogicalButtonId.ENTER to enter,
        )
        ic = FakeInputConnection()
    }

    /**
     * Minimal real [InsertionService] for the P4 keystroke-path: the
     * `committer` (space insert) and the cursor-move `controlExecutor` both
     * forward to the fake IC's `commitText`, so the existing parity
     * assertions on [FakeInputConnection.committed] / `cursorMoves` still
     * hold after the installer's writes were routed through the service. A
     * `null` IC makes `ic.live()` return `null`, reproducing the legacy
     * null-IC short-circuit. Other collaborators are inert no-ops. The inert
     * [FakeHostTextReader] reports no readable selection, so a `CursorMove`
     * intent resolves to the legacy `CursorNudge` empty-commit (F-021
     * fallback branch) — which is exactly the write the parity assertions
     * count.
     */
    private fun keystrokeInsertionService(connection: InputConnection?): InsertionService =
        InsertionService(
            ic = { connection?.let { HostTarget(it, null as EditorInfo?) } },
            guard = { true },
            committer = { target, text -> target.commitText(text, 1) },
            controlExecutor = { target, op ->
                if (op is ControlOp.CursorNudge) target.commitText("", op.offset) else true
            },
            autoEnter = object : AutoEnterScheduler {
                override fun isActive() = false
                override fun schedule(text: String) {}
            },
            audit = object : InsertionAuditLog {
                override fun captureReplaced(ic: InputConnection): String? = null
                override fun record(
                    text: String, replaced: String?, editor: EditorInfo?,
                    source: InsertionSource, sessionIdOverride: String?,
                ) {}
            },
            recovery = object : RecoveryHandler {
                override fun notifyFocusLost() {}
                override fun resume(sessionId: String) {}
            },
            clipboard = object : ClipboardGateway {
                override fun performHostAction(ic: InputConnection, action: EditAction) = true
                override fun fallback(ic: InputConnection, action: EditAction) {}
            },
            textReader = FakeHostTextReader(),
        )

    private fun newInstaller(
        connection: InputConnection? = ic,
    ): SpecialTouchHandlerInstaller =
        SpecialTouchHandlerInstaller(
            inputConnectionProvider = { connection },
            keyboardActions = { KeyboardActionDispatcher(LocalImeSink(keystrokeInsertionService(connection))) },
            insertionService = { keystrokeInsertionService(connection) },
            isPcMode = { false },
            accentColorProvider = { 0xFF0000FF.toInt() },
            onVibrate = { vibrations += Unit },
            onBackspaceDeleteCancelled = { deleteCancels += Unit },
            keyPressAnimator = KeyPressAnimator(),
        )

    // ── 1. RR-1 single-owner invariant ────────────────────────────────

    @Test
    fun installDormant_attaches_no_touch_listener_to_live_views() {
        val installer = newInstaller()

        installer.installDormant(buttons)

        // The load-bearing RR-1 assertion: CR2 must NOT overwrite the
        // legacy MainButtonsController's live setOnTouchListener.
        assertNull(
            "SPACE must have NO touch listener after CR2 installDormant (RR-1)",
            shadowOf(space).onTouchListener,
        )
        assertNull(
            "BACKSPACE must have NO touch listener after CR2 installDormant (RR-1)",
            shadowOf(backspace).onTouchListener,
        )
        assertNull(
            "ENTER must have NO touch listener after CR2 installDormant (RR-1)",
            shadowOf(enter).onTouchListener,
        )
    }

    @Test
    fun installDormant_ledger_reads_dormant_cr2_for_all_three() {
        val installer = newInstaller()

        installer.installDormant(buttons)

        assertEquals(
            SpecialTouchHandlerInstaller.OWNER_DORMANT_CR2,
            installer.ownerOf(LogicalButtonId.SPACE),
        )
        assertEquals(
            SpecialTouchHandlerInstaller.OWNER_DORMANT_CR2,
            installer.ownerOf(LogicalButtonId.BACKSPACE),
        )
        assertEquals(
            SpecialTouchHandlerInstaller.OWNER_DORMANT_CR2,
            installer.ownerOf(LogicalButtonId.ENTER),
        )
    }

    @Test
    fun ownerOf_is_null_before_installDormant() {
        val installer = newInstaller()
        assertNull(installer.ownerOf(LogicalButtonId.SPACE))
        assertNull(installer.ownerOf(LogicalButtonId.BACKSPACE))
        assertNull(installer.ownerOf(LogicalButtonId.ENTER))
    }

    // ── 2. The three §11.7 handlers are built ─────────────────────────

    @Test
    fun installDormant_builds_all_three_handlers_distinct() {
        val installer = newInstaller()

        installer.installDormant(buttons)

        assertNotNull("SPACE CursorSwipe handler built (G4)", installer.spaceHandler)
        assertNotNull("BACKSPACE swipe handler built (G3)", installer.backspaceHandler)
        assertNotNull("ENTER overlay handler built (G5)", installer.enterHandler)
        // Three distinct handler instances.
        val set = setOf(
            installer.spaceHandler,
            installer.backspaceHandler,
            installer.enterHandler,
        )
        assertEquals(3, set.size)
    }

    @Test
    fun handlers_are_null_before_installDormant() {
        val installer = newInstaller()
        assertNull(installer.spaceHandler)
        assertNull(installer.backspaceHandler)
        assertNull(installer.enterHandler)
    }

    // ── 3. CR4 flip ───────────────────────────────────────────────────

    @Test
    fun attachToViews_is_what_actually_attaches_the_cached_handlers() {
        val installer = newInstaller()
        installer.installDormant(buttons)
        val spaceH = installer.spaceHandler
        val backspaceH = installer.backspaceHandler
        val enterH = installer.enterHandler

        installer.attachToViews(buttons)

        // CR4: the live listener is now the cached §11.7 handler.
        assertSame(spaceH, shadowOf(space).onTouchListener)
        assertSame(backspaceH, shadowOf(backspace).onTouchListener)
        assertSame(enterH, shadowOf(enter).onTouchListener)
    }

    @Test
    fun attachToViews_transitions_ledger_dormant_to_attached() {
        val installer = newInstaller()
        installer.installDormant(buttons)
        assertEquals(
            SpecialTouchHandlerInstaller.OWNER_DORMANT_CR2,
            installer.ownerOf(LogicalButtonId.SPACE),
        )

        installer.attachToViews(buttons)

        assertEquals(
            SpecialTouchHandlerInstaller.OWNER_ATTACHED_CR4,
            installer.ownerOf(LogicalButtonId.SPACE),
        )
        assertEquals(
            SpecialTouchHandlerInstaller.OWNER_ATTACHED_CR4,
            installer.ownerOf(LogicalButtonId.BACKSPACE),
        )
        assertEquals(
            SpecialTouchHandlerInstaller.OWNER_ATTACHED_CR4,
            installer.ownerOf(LogicalButtonId.ENTER),
        )
    }

    // ── 4. Double-build guard ─────────────────────────────────────────

    @Test
    fun second_installDormant_still_attaches_no_live_listener() {
        val installer = newInstaller()

        installer.installDormant(buttons)
        // A double-build (e.g. a backend-lifecycle bug) logs Log.wtf but
        // must still NOT attach a live listener in CR2 (no overwrite).
        installer.installDormant(buttons)

        assertNull(shadowOf(space).onTouchListener)
        assertNull(shadowOf(backspace).onTouchListener)
        assertNull(shadowOf(enter).onTouchListener)
        assertEquals(
            SpecialTouchHandlerInstaller.OWNER_DORMANT_CR2,
            installer.ownerOf(LogicalButtonId.SPACE),
        )
    }

    // ── 5. §11.7 SPACE behaviour wiring (verbatim body) ───────────────

    @Test
    fun space_handler_onTap_commits_a_space_via_inputconnection() {
        val installer = newInstaller()
        installer.installDormant(buttons)
        installer.attachToViews(buttons)
        val handler = shadowOf(space).onTouchListener!!

        // Simulate a tap: ACTION_DOWN then ACTION_UP within threshold.
        val down = motionEvent(android.view.MotionEvent.ACTION_DOWN, 0f)
        val up = motionEvent(android.view.MotionEvent.ACTION_UP, 0f)
        handler.onTouch(space, down)
        handler.onTouch(space, up)

        assertEquals(
            "§11.7 onTap commits a single space",
            " ",
            ic.committed.toString(),
        )
    }

    @Test
    fun space_handler_clears_drawables_and_short_circuits_when_no_inputconnection() {
        val installer = newInstaller(connection = null)
        installer.installDormant(buttons)
        installer.attachToViews(buttons)
        val handler = shadowOf(space).onTouchListener!!

        // §11.7: when inputConnectionProvider() == null → return false
        // (and clear compound drawables). No crash, no commit.
        val down = motionEvent(android.view.MotionEvent.ACTION_DOWN, 0f)
        val consumed = handler.onTouch(space, down)

        assertEquals(false, consumed)
        assertEquals("", ic.committed.toString())
    }

    // ── 5b. F-1 (B5-VAL) — exactly-one-commit + swipe still moves ─────

    @Test
    fun `F-1 single SPACE tap commits exactly one space (no double-commit)`() {
        val installer = newInstaller()
        installer.installDormant(buttons)
        installer.attachToViews(buttons)
        val handler = shadowOf(space).onTouchListener!!

        // One physical tap (DOWN→UP, no swipe). The §11.7 onTap is the
        // ONLY commit path now (ImeViewBackend no longer wires a SPACE
        // click — F-1). The regression was TWO spaces (touch onTap +
        // performClick→SpaceKey). Assert EXACTLY one.
        handler.onTouch(space, motionEvent(android.view.MotionEvent.ACTION_DOWN, 0f))
        handler.onTouch(space, motionEvent(android.view.MotionEvent.ACTION_UP, 0f))

        assertEquals(
            "one SPACE tap must commit exactly one space (F-1)",
            " ",
            ic.committed.toString(),
        )
    }

    @Test
    fun `F-1 SPACE swipe moves the cursor and commits no space (G4 intact)`() {
        val installer = newInstaller()
        installer.installDormant(buttons)
        installer.attachToViews(buttons)
        val handler = shadowOf(space).onTouchListener!!

        // DOWN → MOVE past the swipe threshold → UP. CursorSwipe must
        // still propagate MOVE (consumeTouchEvents=false, G4) → a cursor
        // move (commitText("", 2)) and NO space commit on UP (hasSwiped).
        handler.onTouch(space, motionEvent(android.view.MotionEvent.ACTION_DOWN, 0f))
        handler.onTouch(
            space,
            motionEvent(
                android.view.MotionEvent.ACTION_MOVE,
                net.devemperor.dictate.keyboard.CursorSwipeTouchHandler
                    .DEFAULT_SWIPE_THRESHOLD + 50f,
            ),
        )
        handler.onTouch(space, motionEvent(android.view.MotionEvent.ACTION_UP, 0f))

        assertEquals(
            "a SPACE swipe must move the cursor, never commit a space (G4)",
            "",
            ic.committed.toString(),
        )
        assertEquals(
            "the cursor-move must have fired (G4 MOVE-propagation intact)",
            1,
            ic.cursorMoves,
        )
    }

    // ── 6. G3 — accel-delete-cascade-cancel wire (the half F-1 dropped) ─

    @Test
    fun backspace_handler_swipe_select_fires_onBackspaceDeleteCancelled() {
        val installer = newInstaller()
        installer.installDormant(buttons)
        installer.attachToViews(buttons)
        val handler = shadowOf(backspace).onTouchListener!!

        // §11.7 BackspaceSwipeHandler: a swipe-left past the activation
        // slop must call onDeleteCancelled() so a running accel-delete
        // cascade is interrupted. CR2 wires this to the REAL IME
        // onBackspaceDeleteCancelled() (deviation #2 — the half F-1
        // dropped: a pure no-op would NOT stop the cascade). A large
        // negative dx clears any device-dependent slop/touch-slop.
        handler.onTouch(backspace, motionEvent(android.view.MotionEvent.ACTION_DOWN, 500f))
        handler.onTouch(backspace, motionEvent(android.view.MotionEvent.ACTION_MOVE, 0f))

        assertEquals(
            "swipe-select must cancel the accel-delete cascade (G3, F-1 fix)",
            1,
            deleteCancels.size,
        )
    }

    // ── 7. PC-only mode (pc-dictation-activity) — gestures route to the PC, no IC ──
    //
    // The PC-dictation Activity installs the handlers with `pcOnlyMode = true`, a null IC provider,
    // and a keyboardActions dispatcher wrapping the PC sink. These tests prove the gestures emit the
    // expected PC KeyboardActions and never crash on the absent InputConnection.

    /** Records every KeyboardAction the dispatcher submits (the PC sink's stand-in). */
    private class RecordingSink : net.devemperor.dictate.state.insertion.KeyboardActionSink {
        val actions = mutableListOf<net.devemperor.dictate.state.insertion.KeyboardAction>()
        override fun submit(
            action: net.devemperor.dictate.state.insertion.KeyboardAction,
        ): net.devemperor.dictate.state.insertion.SubmitResult {
            actions += action
            return net.devemperor.dictate.state.insertion.SubmitResult.Accepted
        }
    }

    private fun pcOnlyInstaller(sink: RecordingSink): SpecialTouchHandlerInstaller =
        SpecialTouchHandlerInstaller(
            // Null IC: the Activity has no InputConnection. A dereference here would NPE — the
            // gestures must never read it in PC-only mode.
            inputConnectionProvider = { null },
            keyboardActions = { KeyboardActionDispatcher(sink) },
            insertionService = { null },
            isPcMode = { true },
            accentColorProvider = { 0xFF0000FF.toInt() },
            onVibrate = { vibrations += Unit },
            onBackspaceDeleteCancelled = { deleteCancels += Unit },
            keyPressAnimator = KeyPressAnimator(),
            pcOnlyMode = true,
        )

    @Test
    fun `pcOnly SPACE tap emits a type-space to the PC without touching the IC`() {
        val sink = RecordingSink()
        val installer = pcOnlyInstaller(sink)
        installer.installDormant(buttons)
        installer.attachToViews(buttons)
        val handler = shadowOf(space).onTouchListener!!

        handler.onTouch(space, motionEvent(android.view.MotionEvent.ACTION_DOWN, 0f))
        handler.onTouch(space, motionEvent(android.view.MotionEvent.ACTION_UP, 0f))

        // The null-IC short-circuit is skipped in pcOnly mode, so the tap reaches the PC sink.
        assertEquals(1, sink.actions.size)
        val typed = sink.actions.single() as net.devemperor.dictate.state.insertion.KeyboardAction.TypeText
        assertEquals(" ", typed.request.text)
    }

    @Test
    fun `pcOnly SPACE swipe emits a cursor move to the PC`() {
        val sink = RecordingSink()
        val installer = pcOnlyInstaller(sink)
        installer.installDormant(buttons)
        installer.attachToViews(buttons)
        val handler = shadowOf(space).onTouchListener!!

        handler.onTouch(space, motionEvent(android.view.MotionEvent.ACTION_DOWN, 0f))
        handler.onTouch(space, motionEvent(android.view.MotionEvent.ACTION_MOVE, 500f))

        val moves = sink.actions
            .filterIsInstance<net.devemperor.dictate.state.insertion.KeyboardAction.Control>()
            .map { it.op }
        assertEquals(
            "the horizontal swipe must emit at least one CursorMove to the PC",
            true,
            moves.any { it is ControlOp.CursorMove },
        )
    }

    @Test
    fun `pcOnly BACKSPACE swipe emits word selection then delete on release`() {
        val sink = RecordingSink()
        val installer = pcOnlyInstaller(sink)
        installer.installDormant(buttons)
        installer.attachToViews(buttons)
        val handler = shadowOf(backspace).onTouchListener!!

        // Swipe left past the activation slop (PC word-selection), then release to delete.
        handler.onTouch(backspace, motionEvent(android.view.MotionEvent.ACTION_DOWN, 500f))
        handler.onTouch(backspace, motionEvent(android.view.MotionEvent.ACTION_MOVE, 0f))
        handler.onTouch(backspace, motionEvent(android.view.MotionEvent.ACTION_UP, 0f))

        val ops = sink.actions
            .filterIsInstance<net.devemperor.dictate.state.insertion.KeyboardAction.Control>()
            .map { it.op }
        assertEquals(
            "the swipe must select at least one word on the PC (Ctrl+Shift+Left)",
            true,
            ops.any { it is ControlOp.SelectWord },
        )
        assertEquals(
            "releasing a standing selection must delete it on the PC (Backspace)",
            true,
            ops.any { it is ControlOp.DeleteSelection },
        )
        // No IC exists — the gesture must have driven the PC entirely (no crash reaching here proves it).
    }

    @Test
    fun `pcOnly gates the ENTER overlay handler (no PC path for overlay chars)`() {
        val installer = pcOnlyInstaller(RecordingSink())
        installer.installDormant(buttons)
        installer.attachToViews(buttons)

        assertNull(
            "the ENTER overlay-char handler has no PC path and must not be installed in pcOnly mode",
            installer.enterHandler,
        )
        assertNull("ENTER keeps no touch listener in pcOnly mode", shadowOf(enter).onTouchListener)
        // SPACE + BACKSPACE ARE installed.
        assertNotNull(installer.spaceHandler)
        assertNotNull(installer.backspaceHandler)
    }

    private fun motionEvent(action: Int, x: Float): android.view.MotionEvent =
        android.view.MotionEvent.obtain(0L, 0L, action, x, 0f, 0)

    /** Minimal handwritten K-1 InputConnection fake — captures commitText. */
    private class FakeInputConnection : InputConnection {
        val committed = StringBuilder()

        /**
         * F-1 (B5-VAL): the §11.7 cursor-move is a
         * `commitText("", ±)` (empty text, cursor reposition). Count
         * those separately from a real space commit so the swipe-vs-tap
         * paths are distinguishable in tests.
         */
        var cursorMoves = 0
            private set

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text.isNullOrEmpty()) {
                cursorMoves++
            } else {
                committed.append(text)
            }
            return true
        }

        // ── unused surface (handwritten K-1; no mocking framework) ──
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = null
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = null
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun getExtractedText(
            request: android.view.inputmethod.ExtractedTextRequest?,
            flags: Int,
        ): android.view.inputmethod.ExtractedText? = null
        override fun deleteSurroundingText(before: Int, after: Int): Boolean = false
        override fun deleteSurroundingTextInCodePoints(before: Int, after: Int): Boolean = false
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = false
        override fun setComposingRegion(start: Int, end: Int): Boolean = false
        override fun finishComposingText(): Boolean = false
        override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean = false
        override fun commitCorrection(
            correctionInfo: android.view.inputmethod.CorrectionInfo?,
        ): Boolean = false
        override fun setSelection(start: Int, end: Int): Boolean = false
        override fun performEditorAction(editorAction: Int): Boolean = false
        override fun performContextMenuAction(id: Int): Boolean = false
        override fun beginBatchEdit(): Boolean = false
        override fun endBatchEdit(): Boolean = false
        override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean = false
        override fun clearMetaKeyStates(states: Int): Boolean = false
        override fun reportFullscreenMode(enabled: Boolean): Boolean = false
        override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = false
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
        override fun getHandler(): android.os.Handler? = null
        override fun closeConnection() {}
        override fun commitContent(
            inputContentInfo: android.view.inputmethod.InputContentInfo,
            flags: Int,
            opts: android.os.Bundle?,
        ): Boolean = false
    }
}
